package com.things.link.ota.infrastructure.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.things.link.ota.application.OtaCanonicalJson;
import com.things.link.ota.application.OtaReleaseSignatureVerifier;
import com.things.link.ota.application.OtaReleaseSigner;
import com.things.link.ota.application.OtaSignatureProfile;
import com.things.link.ota.application.OtaSigningControl;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR0139适配器在真实本地HTTP服务上的结构合同验证。
 *
 * <p>本地替身是**外部服务的测试替身**：它按线格式回执真实Ed25519签名，使协调器的密码学复验可执行，
 * 但不代表KMS/HSM资格，也不构成G3硬件证据。测试覆盖线格式、预算、取消、拒绝分类、TLS策略与
 * 凭据不外泄；不覆盖任何供应商实现。</p>
 */
class HttpControlledReleaseSignerTests {
    /** 发布签名域分离前缀，与OtaReleaseSignatureVerifier内部完全一致，独立拼装防止同源错误。 */
    private static final byte[] DOMAIN = "thingslink-ota-release-manifest-v1\0".getBytes(StandardCharsets.UTF_8);
    /** 最小规范manifest字节，仅用于验证域分离签名字节。 */
    private static final byte[] CANONICAL = "{\"firmwareVersion\":\"1.0.0\"}".getBytes(StandardCharsets.UTF_8);
    /** 固定测试凭据；失败消息中不允许出现该字符串。 */
    private static final String TOKEN = "test-signing-credential-DO-NOT-LEAK";
    /** 响应正文标记；失败消息中不允许出现该字符串。 */
    private static final String RESPONSE_BODY_MARKER = "vendor-body-marker-DO-NOT-LEAK";
    /** 固定请求身份，回显不一致反例据此构造。 */
    private static final UUID REQUEST_ID = UUID.fromString("018f0000-0000-7000-8000-000000000013");
    /** 固定不可变密钥版本。 */
    private static final String KEY_VERSION = "release-key/1";
    /** 固定可信签名域。 */
    private static final String TRUST_DOMAIN = "domain-1";
    /** 供应商回执标识。 */
    private static final String RECEIPT = "stub-receipt/1";
    /** 冻结签名Profile。 */
    private static final OtaSignatureProfile PROFILE = OtaSignatureProfile.ED25519_V1;
    /** 仅解析测试替身自己的请求/响应，不进入生产路径。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 真实测试密钥对，私钥只在替身内使用。 */
    private static final KeyPair RELEASE = keyPair();

    /** 真实本地HTTP服务。 */
    private static HttpServer server;
    /** 阻塞替身需要独立线程，不能占住服务派发线程。 */
    private static ExecutorService executor;
    /** 服务真实监听端口。 */
    private static int port;
    /** 每例替换的替身行为，默认回执真实签名。 */
    private static final AtomicReference<Responder> responder = new AtomicReference<>();
    /** 最后一次收到的原始请求正文，用于断言冻结线格式。 */
    private static final AtomicReference<byte[]> lastBody = new AtomicReference<>();
    /** 最后一次收到的认证头与内容类型。 */
    private static final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    /** 最后一次收到的内容类型。 */
    private static final AtomicReference<String> lastContentType = new AtomicReference<>();
    /** 物理调用次数，证明不重试。 */
    private static final AtomicInteger calls = new AtomicInteger();

    /** 建立真实监听服务与独立执行器。 */
    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/sign", HttpControlledReleaseSignerTests::handle);
        server.start();
        port = server.getAddress().getPort();
    }

    /** 关闭服务并中断仍在阻塞的替身线程。 */
    @AfterAll
    static void stopStub() {
        server.stop(0);
        executor.shutdownNow();
    }

    /** 每例重置替身行为与观察槽，避免共享静态状态泄漏。 */
    @BeforeEach
    void resetStub() {
        responder.set(HttpControlledReleaseSignerTests::validResponse);
        lastBody.set(null);
        lastAuthorization.set(null);
        lastContentType.set(null);
        calls.set(0);
    }

    /** 成功路径：冻结请求字段、固定认证头、真实签名回执可被平台自身验签接受。 */
    @Test
    void sendsFrozenRequestShapeAndReturnsVerifiableResponse() throws Exception {
        OtaSigningControl control = new OtaSigningControl(Duration.ofSeconds(10), () -> false);

        OtaReleaseSigner.Response response = signer(true).sign(request(), control);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(lastAuthorization.get()).isEqualTo("Bearer " + TOKEN);
        assertThat(lastContentType.get()).isEqualTo("application/json");
        Map<String, Object> sent = new OtaCanonicalJson().parseObject(lastBody.get());
        assertThat(sent.keySet()).containsExactlyInAnyOrder("contractVersion", "requestId", "trustDomain",
                "keyVersion", "signatureProfile", "keyFingerprint", "signingInput");
        assertThat(sent).containsEntry("contractVersion", "tc-ota-sign-request/v1")
                .containsEntry("requestId", REQUEST_ID.toString())
                .containsEntry("trustDomain", TRUST_DOMAIN)
                .containsEntry("keyVersion", KEY_VERSION)
                .containsEntry("signatureProfile", PROFILE.value())
                .containsEntry("keyFingerprint", fingerprint());
        assertThat(Base64.getDecoder().decode((String) sent.get("signingInput"))).isEqualTo(signingInput());

        assertThat(response.requestId()).isEqualTo(REQUEST_ID);
        assertThat(response.keyVersion()).isEqualTo(KEY_VERSION);
        assertThat(response.profile()).isEqualTo(PROFILE);
        assertThat(response.receipt()).isEqualTo(RECEIPT);
        assertThat(new OtaReleaseSignatureVerifier().verify(PROFILE, response.spki(), fingerprint(),
                CANONICAL, response.signature())).isTrue();
        assertThat(new OtaReleaseSignatureVerifier().verify(PROFILE, response.spki(), fingerprint(),
                "{\"firmwareVersion\":\"2.0.0\"}".getBytes(StandardCharsets.UTF_8), response.signature())).isFalse();
        // 成功路径也必须注销取消钩子，否则长生命周期的control会持有已结束的Call。
        assertThat(hooks(control)).isEmpty();
    }

    /** 预算耗尽：替身阻塞超过90秒预算的子集时，调用必须在剩余预算内失败且分类明确。 */
    @Test
    void abortsWithinBudgetWhenSignerExceedsDeadline() {
        responder.set(body -> {
            Thread.sleep(10_000);
            return validResponse(body);
        });
        OtaSigningControl control = new OtaSigningControl(Duration.ofSeconds(2), () -> false);
        long started = System.nanoTime();

        Throwable failure = catchThrowable(() -> signer(true).sign(request(), control));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(failure).isInstanceOf(HttpControlledReleaseSigner.Failure.class);
        assertThat(((HttpControlledReleaseSigner.Failure) failure).reason())
                .isEqualTo(HttpControlledReleaseSigner.Reason.TIMEOUT);
        assertThat(hooks(control)).isEmpty();
    }

    /** 剩余预算不足收尾余量时不得发起物理调用。 */
    @Test
    void refusesCallWhenRemainingBudgetCannotCoverFinishingReserve() {
        OtaSigningControl control = new OtaSigningControl(Duration.ofMillis(100), () -> false);

        HttpControlledReleaseSigner.Failure failure = signFailure(control);

        assertThat(failure.reason()).isEqualTo(HttpControlledReleaseSigner.Reason.BUDGET_EXHAUSTED);
        assertThat(calls.get()).isZero();
    }

    /** 取消信号必须中断在途HTTP调用，并在finally注销钩子，不留悬挂引用。 */
    @Test
    void cancelAbortsInFlightCallAndDeregistersHook() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        responder.set(body -> {
            received.countDown();
            Thread.sleep(10_000);
            return validResponse(body);
        });
        OtaSigningControl control = new OtaSigningControl(Duration.ofSeconds(30), () -> false);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> outcome = caller.submit(() -> {
                try {
                    signer(true).sign(request(), control);
                    return null;
                } catch (RuntimeException failure) {
                    return failure;
                }
            });
            assertThat(received.await(2, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            control.cancel();
            Throwable failure = outcome.get(2, TimeUnit.SECONDS);

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            assertThat(failure).isInstanceOf(HttpControlledReleaseSigner.Failure.class);
            assertThat(((HttpControlledReleaseSigner.Failure) failure).reason())
                    .isEqualTo(HttpControlledReleaseSigner.Reason.CANCELLED);
            // 控制信号不代表供应商物理结束；这里只证明平台的在途调用已被中断。
            assertThat(hooks(control)).isEmpty();
        } finally {
            caller.shutdownNow();
        }
    }

    /** 非2xx即使携带合法正文也必须拒绝，且不得把供应商正文写入消息。 */
    @Test
    void rejectsNonSuccessStatus() {
        responder.set(body -> new Reply(500, RESPONSE_BODY_MARKER.getBytes(StandardCharsets.UTF_8)));

        HttpControlledReleaseSigner.Failure failure = signFailure();

        assertThat(failure.reason()).isEqualTo(HttpControlledReleaseSigner.Reason.HTTP_REJECTED);
        assertThat(failure.getCause()).isNull();
    }

    /** 超限正文、畸形JSON与重复字段都必须在领域层之前拒绝。 */
    @Test
    void rejectsOversizedMalformedAndDuplicateBodies() {
        responder.set(body -> new Reply(200, new byte[HttpControlledReleaseSigner.MAX_RESPONSE_BYTES + 1]));
        assertThat(signFailure().reason()).isEqualTo(HttpControlledReleaseSigner.Reason.INVALID_RESPONSE);

        responder.set(body -> new Reply(200, "{\"contractVersion\":".getBytes(StandardCharsets.UTF_8)));
        assertThat(signFailure().reason()).isEqualTo(HttpControlledReleaseSigner.Reason.INVALID_RESPONSE);

        responder.set(body -> {
            String json = validJson(body);
            return new Reply(200, (json.substring(0, json.length() - 1) + ",\"receipt\":\"duplicate\"}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        assertThat(signFailure().reason()).isEqualTo(HttpControlledReleaseSigner.Reason.INVALID_RESPONSE);
        assertThat(calls.get()).isEqualTo(3);
    }

    /** 未知/缺失字段、错误版本、回显身份不一致与超限字段全部判为无效响应。 */
    @Test
    void rejectsStructurallyInvalidResponseFields() {
        Map<String, Consumer<Map<String, Object>>> mutations = new LinkedHashMap<>();
        mutations.put("unknown-field", fields -> fields.put("extra", "x"));
        mutations.put("missing-field", fields -> fields.remove("receipt"));
        mutations.put("wrong-contract-version", fields -> fields.put("contractVersion", "tc-ota-sign-response/v2"));
        mutations.put("mismatched-request-id", fields -> fields.put("requestId", UUID.randomUUID().toString()));
        mutations.put("mismatched-key-version", fields -> fields.put("keyVersion", "other-key/1"));
        mutations.put("mismatched-profile", fields -> fields.put("signatureProfile",
                OtaSignatureProfile.ES256_P1363_V1.value()));
        mutations.put("type-error", fields -> fields.put("receipt", 7L));
        mutations.put("oversized-spki", fields -> fields.put("spkiBase64", base64(new byte[129])));
        mutations.put("unpadded-base64", fields -> fields.put("spkiBase64",
                base64(RELEASE.getPublic().getEncoded()).replace("=", "")));
        mutations.put("illegal-base64", fields -> fields.put("signatureBase64", "not*base64"));
        mutations.put("empty-receipt", fields -> fields.put("receipt", ""));
        mutations.put("oversized-receipt", fields -> fields.put("receipt", "r".repeat(129)));
        mutations.put("control-character-receipt", fields -> fields.put("receipt", "bad\u0001receipt"));

        for (Map.Entry<String, Consumer<Map<String, Object>>> mutation : mutations.entrySet()) {
            Consumer<Map<String, Object>> change = mutation.getValue();
            responder.set(body -> mutate(body, change));
            assertThat(signFailure().reason()).as(mutation.getKey())
                    .isEqualTo(HttpControlledReleaseSigner.Reason.INVALID_RESPONSE);
        }
        assertThat(calls.get()).isEqualTo(mutations.size());
    }

    /** TLS策略：非回环明文拒绝，回环默认拒绝，显式开关后才允许；HTTPS始终允许。 */
    @Test
    void enforcesTlsAndLoopbackPolicy() {
        assertThatThrownBy(() -> new HttpControlledReleaseSigner("http://192.0.2.1:8080/sign", TOKEN, true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new HttpControlledReleaseSigner("http://127.0.0.1:" + port + "/sign", TOKEN, false))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new HttpControlledReleaseSigner("http://127.0.0.1:" + port + "/sign", TOKEN, true)).isNotNull();
        assertThat(new HttpControlledReleaseSigner("https://signer.example.com/sign", TOKEN, false)).isNotNull();
        assertThatThrownBy(() -> new HttpControlledReleaseSigner("ftp://signer.example.com/sign", TOKEN, true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new HttpControlledReleaseSigner("http://user:pass@127.0.0.1/sign", TOKEN, true))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 端点已配置但凭据缺失或含控制字符时启动失败，不静默降级成"每次都失败"的适配器。 */
    @Test
    void failsFastWhenEndpointIsConfiguredWithoutUsableCredential() {
        assertThatThrownBy(() -> new HttpControlledReleaseSigner("http://127.0.0.1:" + port + "/sign", "  ", true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new HttpControlledReleaseSigner("", TOKEN, true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new HttpControlledReleaseSigner("http://127.0.0.1:" + port + "/sign",
                "bad\r\nheader", true)).isInstanceOf(IllegalStateException.class);
    }

    /** 凭据实际随Authorization发出，但任何失败消息都不得包含凭据、请求正文或响应正文。 */
    @Test
    void neverLeaksCredentialOrBodiesInFailureMessages() {
        responder.set(body -> new Reply(500, RESPONSE_BODY_MARKER.getBytes(StandardCharsets.UTF_8)));
        HttpControlledReleaseSigner.Failure rejected = signFailure();
        assertThat(lastAuthorization.get()).isEqualTo("Bearer " + TOKEN);
        assertThat(rejected.getMessage()).isEqualTo(HttpControlledReleaseSigner.Reason.HTTP_REJECTED.name())
                .doesNotContain(TOKEN).doesNotContain(RESPONSE_BODY_MARKER)
                .doesNotContain(base64(signingInput()));

        responder.set(body -> new Reply(200,
                ("{\"receipt\":\"" + RESPONSE_BODY_MARKER + "\"}").getBytes(StandardCharsets.UTF_8)));
        HttpControlledReleaseSigner.Failure invalid = signFailure();
        assertThat(invalid.getMessage()).doesNotContain(TOKEN).doesNotContain(RESPONSE_BODY_MARKER)
                .doesNotContain(base64(signingInput()));
        assertThat(invalid.getCause()).isNull();

        responder.set(body -> {
            throw new IllegalStateException(TOKEN);
        });
        HttpControlledReleaseSigner.Failure brokenStub = signFailure();
        assertThat(brokenStub.getMessage()).doesNotContain(TOKEN);
        assertThat(brokenStub.getCause()).isNull();
    }

    /** 真实本地服务：记录请求并按配置行为产生回执。 */
    private static void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        byte[] body = exchange.getRequestBody().readAllBytes();
        lastBody.set(body);
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
        Reply reply;
        try {
            reply = responder.get().reply(body);
        } catch (Exception failure) {
            reply = new Reply(500, RESPONSE_BODY_MARKER.getBytes(StandardCharsets.UTF_8));
        }
        try {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), reply.body().length);
            try (var output = exchange.getResponseBody()) {
                output.write(reply.body());
            }
        } catch (IOException ignored) {
            // 客户端在响应前超时或取消时socket已关闭，测试替身静默结束。
        } finally {
            exchange.close();
        }
    }

    /** 按接收到的请求字段生成真实Ed25519回执。 */
    private static Reply validResponse(byte[] requestBody) throws Exception {
        return new Reply(200, validJson(requestBody).getBytes(StandardCharsets.UTF_8));
    }

    /** 回执正文的封闭字段集合，逐字回显请求身份。 */
    private static Map<String, Object> validFields(byte[] requestBody) throws Exception {
        JsonNode request = JSON.readTree(requestBody);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(RELEASE.getPrivate());
        signature.update(Base64.getDecoder().decode(request.path("signingInput").asText()));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("contractVersion", "tc-ota-sign-response/v1");
        fields.put("requestId", request.path("requestId").asText());
        fields.put("keyVersion", request.path("keyVersion").asText());
        fields.put("signatureProfile", request.path("signatureProfile").asText());
        fields.put("spkiBase64", base64(RELEASE.getPublic().getEncoded()));
        fields.put("signatureBase64", base64(signature.sign()));
        fields.put("receipt", RECEIPT);
        return fields;
    }

    /** 规范JSON文本。 */
    private static String validJson(byte[] requestBody) throws Exception {
        return JSON.writeValueAsString(validFields(requestBody));
    }

    /** 在合法回执上施加单一结构变异。 */
    private static Reply mutate(byte[] requestBody, Consumer<Map<String, Object>> mutation) throws Exception {
        Map<String, Object> fields = validFields(requestBody);
        mutation.accept(fields);
        return new Reply(200, JSON.writeValueAsString(fields).getBytes(StandardCharsets.UTF_8));
    }

    /** 发起一次调用并断言适配器自己的固定失败分类。 */
    private static HttpControlledReleaseSigner.Failure signFailure() {
        return signFailure(new OtaSigningControl(Duration.ofSeconds(10), () -> false));
    }

    /** 使用指定预算控制调用并断言失败类型。 */
    private static HttpControlledReleaseSigner.Failure signFailure(OtaSigningControl control) {
        Throwable failure = catchThrowable(() -> signer(true).sign(request(), control));
        assertThat(failure).isInstanceOf(HttpControlledReleaseSigner.Failure.class);
        return (HttpControlledReleaseSigner.Failure) failure;
    }

    /** 明文回环测试适配器，端口来自真实服务。 */
    private static HttpControlledReleaseSigner signer(boolean allowInsecureLoopback) {
        return new HttpControlledReleaseSigner("http://127.0.0.1:" + port + "/sign", TOKEN, allowInsecureLoopback);
    }

    /** 协调器会构造的固定身份请求。 */
    private static OtaReleaseSigner.Request request() {
        return new OtaReleaseSigner.Request(REQUEST_ID, TRUST_DOMAIN, KEY_VERSION, PROFILE, fingerprint(), signingInput());
    }

    /** 域分离签名字节。 */
    private static byte[] signingInput() {
        byte[] input = new byte[DOMAIN.length + CANONICAL.length];
        System.arraycopy(DOMAIN, 0, input, 0, DOMAIN.length);
        System.arraycopy(CANONICAL, 0, input, DOMAIN.length, CANONICAL.length);
        return input;
    }

    /** 测试发布公钥的完整SPKI指纹。 */
    private static String fingerprint() {
        return sha256(RELEASE.getPublic().getEncoded());
    }

    /**
     * 通过反射读取私有钩子集合：{@code OtaSigningControl}刻意不暴露注册计数，
     * 而ADR0139要求证明适配器在finally注销钩子；测试只读该集合，不修改控制状态。
     */
    private static Set<?> hooks(OtaSigningControl control) {
        try {
            Field field = OtaSigningControl.class.getDeclaredField("hooks");
            field.setAccessible(true);
            return (Set<?>) field.get(control);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法读取OtaSigningControl取消钩子集合", failure);
        }
    }

    /** 标准Base64。 */
    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** 完整字节SHA-256小写十六进制。 */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** 真实Ed25519测试密钥对。 */
    private static KeyPair keyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /** 可替换的测试替身行为。 */
    @FunctionalInterface
    private interface Responder {
        /** 根据原始请求正文产生状态与正文。 */
        Reply reply(byte[] requestBody) throws Exception;
    }

    /** 替身固定响应。 */
    private record Reply(int status, byte[] body) {
    }
}
