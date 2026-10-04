package com.things.link.ota.infrastructure.signing;

import com.things.link.ota.application.OtaCanonicalJson;
import com.things.link.ota.application.OtaControlledReleaseSigner;
import com.things.link.ota.application.OtaReleaseSigner;
import com.things.link.ota.application.OtaSigningControl;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * ADR0139冻结的受控外部signer生产适配器：单次HTTPS POST，剩余预算转真实网络预算，取消信号中断在途调用。
 *
 * <p>为什么必须以属性门控且缺凭据即拒绝启动：ADR0119要求"无受控signer适配器时503拒绝创建"。
 * 若端点已配置而凭据缺失却仍装配成功，运行期会退化成"每次都失败的适配器"，把明显的配置错误
 * 伪装成供应商不可用；因此在启动时fail-fast，而完全未配置时保持零个bean的既有70016语义。</p>
 *
 * <p>为什么只做结构校验：ADR0139明确双层检查——适配器拒绝未知/重复/缺字段与超限响应，
 * 规范签名字节的密码学复验仍由{@link com.things.link.ota.application.OtaSigningCoordinator}
 * 用可信绑定完成。适配器不得因为"供应商说签好了"就跳过协调器。</p>
 *
 * <p>为什么不携带底层异常链：凭据、请求正文与响应正文都不得进入日志、审计或错误消息。
 * 所有失败只暴露固定{@link Reason}名称，cause保持为空，避免任何供应商正文经异常链外泄。</p>
 */
@Component
@ConditionalOnProperty(prefix = "things-link.ota.signing", name = "endpoint")
public class HttpControlledReleaseSigner implements OtaControlledReleaseSigner {
    /** 只记录固定分类与明文回环开关留痕，禁止凭据和正文。 */
    private static final Logger LOG = LoggerFactory.getLogger(HttpControlledReleaseSigner.class);
    /** 请求合同版本，ADR0139固定值。 */
    private static final String REQUEST_CONTRACT_VERSION = "tc-ota-sign-request/v1";
    /** 响应合同版本，ADR0139固定值。 */
    private static final String RESPONSE_CONTRACT_VERSION = "tc-ota-sign-response/v1";
    /** 固定认证方案；凭据只从配置读取，不进入消息。 */
    private static final String AUTHORIZATION_SCHEME = "Bearer ";
    /** ADR0139冻结的signingInput解码后长度上限：64KiB。 */
    static final int MAX_SIGNING_INPUT_BYTES = 65_536;
    /** 响应正文硬上限：封闭对象各字段最大长度之和远小于它，超出即无效响应。 */
    static final int MAX_RESPONSE_BYTES = 8_192;
    /** ADR0139冻结的完整SPKI DER上限：≤128字节。 */
    static final int MAX_SPKI_BYTES = 128;
    /** 两种冻结Profile的签名字节都是64字节，结构上限留出余量但拒绝无界正文。 */
    static final int MAX_SIGNATURE_BYTES = 128;
    /** ADR0139冻结的回执长度上限：1..128字符。 */
    static final int MAX_RECEIPT_CHARS = 128;
    /** 网络收尾余量；剩余预算不足它时直接拒绝调用，绝不越过control预算。 */
    static final long FINISHING_RESERVE_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    /** 请求身份字段结构上限，防止无界配置值撑大请求体。 */
    private static final int MAX_IDENTITY_CHARS = 1_024;
    /** 响应封闭字段集合，未知与缺失都判无效。 */
    private static final Set<String> RESPONSE_FIELDS = Set.of("contractVersion", "requestId", "keyVersion",
            "signatureProfile", "spkiBase64", "signatureBase64", "receipt");
    /** 固定JSON媒体类型，不允许供应商协商其他编码。 */
    private static final MediaType JSON = MediaType.get("application/json");
    /** 已校验的HTTPS端点；构造期即拒绝明文与凭据URL。 */
    private final URI endpoint;
    /** 仅用于Authorization头的固定凭据，永不落日志。 */
    private final String authorization;
    /** 关闭隐式重试/重定向的共享客户端；单次调用再派生专属超时。 */
    private final OkHttpClient client;

    /**
     * 装配生产适配器；端点存在但凭据缺失或传输策略不合法时启动失败。
     *
     * @param endpoint ADR0139配置键{@code things-link.ota.signing.endpoint}
     * @param token ADR0139配置键{@code things-link.ota.signing.token}
     * @param allowInsecureLoopback 仅测试用的明文回环开关，默认false
     */
    public HttpControlledReleaseSigner(
            @Value("${things-link.ota.signing.endpoint:}") String endpoint,
            @Value("${things-link.ota.signing.token:}") String token,
            @Value("${things-link.ota.signing.allow-insecure-loopback:false}") boolean allowInsecureLoopback) {
        this.endpoint = requireEndpoint(endpoint, allowInsecureLoopback);
        this.authorization = AUTHORIZATION_SCHEME + requireToken(token);
        if (allowInsecureLoopback && "http".equals(this.endpoint.getScheme())) {
            // 明文回环是测试专用降级，必须在日志中留痕，避免被误当成生产配置。
            LOG.warn("OTA受控signer允许明文回环端点，仅限测试环境");
        }
        this.client = new OkHttpClient.Builder().retryOnConnectionFailure(false)
                .followRedirects(false).followSslRedirects(false).build();
    }

    /**
     * 单次受控调用：把剩余预算转成连接/写入/读取/总调用超时，取消钩子物理中断在途Call。
     *
     * @param request 协调器构造的固定身份与域分离签名字节
     * @param control 单调预算与可注销取消信号
     * @return 仅通过结构校验的供应商响应，密码学复验仍由协调器完成
     */
    @Override
    public OtaReleaseSigner.Response sign(OtaReleaseSigner.Request request, OtaSigningControl control) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(control, "control");
        byte[] body = requestBody(request);
        control.check();
        long remaining = control.remainingNanos();
        if (remaining <= FINISHING_RESERVE_NANOS) {
            throw new Failure(Reason.BUDGET_EXHAUSTED);
        }
        long available = remaining - FINISHING_RESERVE_NANOS;
        Request httpRequest = new Request.Builder().url(endpoint.toString())
                .header("Authorization", authorization)
                .post(RequestBody.create(body, JSON)).build();
        AtomicBoolean exchanged = new AtomicBoolean();
        OkHttpClient callClient = client.newBuilder()
                .connectTimeout(available, TimeUnit.NANOSECONDS)
                .writeTimeout(available, TimeUnit.NANOSECONDS)
                .readTimeout(available, TimeUnit.NANOSECONDS)
                .callTimeout(available, TimeUnit.NANOSECONDS)
                .addNetworkInterceptor(chain -> {
                    // 即使OkHttp内部针对503等状态尝试follow-up，也不能产生第二次物理交换。
                    if (!exchanged.compareAndSet(false, true)) throw new IOException("OTA受控签名禁止隐式重发");
                    Response network = chain.proceed(chain.request());
                    return network.code() == 503
                            ? network.newBuilder().header("Retry-After", "2147483647").build() : network;
                }).build();
        Call call = callClient.newCall(httpRequest);
        long started = System.nanoTime();
        // finally注销钩子；取消在途Call是非阻塞动作，符合控制端口对钩子的要求。
        try (OtaSigningControl.Registration registration = control.onCancel(call::cancel)) {
            control.check();
            try (Response response = call.execute()) {
                if (!response.isSuccessful()) throw new Failure(Reason.HTTP_REJECTED);
                byte[] payload = readBounded(response);
                return decodeResponse(payload, request);
            } catch (IOException failure) {
                if (control.cancelled()) throw new Failure(Reason.CANCELLED);
                if (System.nanoTime() - started >= available) throw new Failure(Reason.TIMEOUT);
                throw new Failure(Reason.TRANSPORT_FAILURE);
            }
        }
    }

    /** 关闭共享连接池与派发线程，不等价于供应商物理取消。 */
    @PreDestroy
    public void close() {
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }

    /** 手工构造封闭JSON对象：只写ADR0139冻结字段，值严格转义后不产生注入。 */
    private static byte[] requestBody(OtaReleaseSigner.Request request) {
        byte[] signingInput = request.signingInput();
        if (request.requestId() == null || request.profile() == null
                || signingInput.length == 0 || signingInput.length > MAX_SIGNING_INPUT_BYTES) {
            throw new Failure(Reason.INVALID_REQUEST);
        }
        String trustDomain = identity(request.trustDomain());
        String keyVersion = identity(request.keyVersion());
        String fingerprint = identity(request.fingerprint());
        StringBuilder json = new StringBuilder(256 + signingInput.length * 2);
        json.append('{');
        appendField(json, "contractVersion", REQUEST_CONTRACT_VERSION, true);
        appendField(json, "requestId", request.requestId().toString(), false);
        appendField(json, "trustDomain", trustDomain, false);
        appendField(json, "keyVersion", keyVersion, false);
        appendField(json, "signatureProfile", request.profile().value(), false);
        appendField(json, "keyFingerprint", fingerprint, false);
        appendField(json, "signingInput", Base64.getEncoder().encodeToString(signingInput), false);
        json.append('}');
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 身份字段必须非空且有界；配置错误在拨号前失败，不浪费预算。 */
    private static String identity(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_IDENTITY_CHARS) {
            throw new Failure(Reason.INVALID_REQUEST);
        }
        return value;
    }

    /** 追加一个JSON字符串字段，按需转义引号、反斜杠和控制字符。 */
    private static void appendField(StringBuilder json, String name, String value, boolean first) {
        if (!first) json.append(',');
        appendQuoted(json, name);
        json.append(':');
        appendQuoted(json, value);
    }

    /** 最小但完整的JSON字符串转义，禁止控制字符以原文进入线格式。 */
    private static void appendQuoted(StringBuilder json, String value) {
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        // 手工十六进制，避免String.format依赖默认Locale而写出非ASCII数字。
                        json.append("\\u00").append("0123456789abcdef".charAt(character >> 4))
                                .append("0123456789abcdef".charAt(character & 15));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        json.append('"');
    }

    /** 有界读取响应正文；超出硬上限立即无效，不把大正文留在内存或错误消息里。 */
    private static byte[] readBounded(Response response) throws IOException {
        var body = response.body();
        if (body == null) throw new Failure(Reason.INVALID_RESPONSE);
        try (InputStream input = body.byteStream()) {
            ByteArrayOutputStream collected = new ByteArrayOutputStream();
            byte[] buffer = new byte[1_024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (collected.size() + read > MAX_RESPONSE_BYTES) throw new Failure(Reason.INVALID_RESPONSE);
                collected.write(buffer, 0, read);
            }
            return collected.toByteArray();
        }
    }

    /** 严格解析封闭响应对象：未知/重复/缺字段、类型错误、身份不一致与超限全部拒绝。 */
    private static OtaReleaseSigner.Response decodeResponse(byte[] payload, OtaReleaseSigner.Request request) {
        Map<String, Object> fields;
        try {
            fields = new OtaCanonicalJson().parseObject(payload);
        } catch (IllegalArgumentException failure) {
            // 解析器已拒绝重复字段、null、尾随内容与畸形UTF-8；这里只做固定分类。
            throw new Failure(Reason.INVALID_RESPONSE);
        }
        if (!fields.keySet().equals(RESPONSE_FIELDS)) throw new Failure(Reason.INVALID_RESPONSE);
        String contractVersion = string(fields, "contractVersion");
        String requestId = string(fields, "requestId");
        String keyVersion = string(fields, "keyVersion");
        String signatureProfile = string(fields, "signatureProfile");
        String spkiBase64 = string(fields, "spkiBase64");
        String signatureBase64 = string(fields, "signatureBase64");
        String receipt = string(fields, "receipt");
        if (!RESPONSE_CONTRACT_VERSION.equals(contractVersion)
                || !request.requestId().toString().equals(requestId)
                || !request.keyVersion().equals(keyVersion)
                || !request.profile().value().equals(signatureProfile)) {
            throw new Failure(Reason.INVALID_RESPONSE);
        }
        byte[] spki = decodeCanonicalBase64(spkiBase64);
        if (spki.length == 0 || spki.length > MAX_SPKI_BYTES) throw new Failure(Reason.INVALID_RESPONSE);
        byte[] signature = decodeCanonicalBase64(signatureBase64);
        if (signature.length == 0 || signature.length > MAX_SIGNATURE_BYTES) throw new Failure(Reason.INVALID_RESPONSE);
        if (!validReceipt(receipt)) throw new Failure(Reason.INVALID_RESPONSE);
        // 回显身份已逐字校验，这里仍用供应商回显值构造响应，避免把请求值当作"已签事实"。
        return new OtaReleaseSigner.Response(request.requestId(), keyVersion, request.profile(), spki, signature, receipt);
    }

    /** 字段只接受字符串；数字、布尔、数组、对象都是类型错误。 */
    private static String string(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        if (!(value instanceof String text)) throw new Failure(Reason.INVALID_RESPONSE);
        return text;
    }

    /** 严格填充的规范Base64：长度对齐、字母表合法且重新编码逐字一致。 */
    private static byte[] decodeCanonicalBase64(String value) {
        if (value == null || value.isEmpty() || value.length() % 4 != 0) throw new Failure(Reason.INVALID_RESPONSE);
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException failure) {
            throw new Failure(Reason.INVALID_RESPONSE);
        }
        if (!Base64.getEncoder().encodeToString(decoded).equals(value)) throw new Failure(Reason.INVALID_RESPONSE);
        return decoded;
    }

    /** 回执为1..128字符且不含控制字符；允许Unicode但不允许换行/制表等注入。 */
    private static boolean validReceipt(String receipt) {
        if (receipt == null || receipt.isEmpty() || receipt.length() > MAX_RECEIPT_CHARS) return false;
        for (int index = 0; index < receipt.length(); index++) {
            if (Character.isISOControl(receipt.charAt(index))) return false;
        }
        return true;
    }

    /** 端点必须HTTPS；仅当主机确为回环且显式开启开关时才允许明文。 */
    private static URI requireEndpoint(String value, boolean allowInsecureLoopback) {
        URI uri;
        try {
            uri = URI.create(value == null ? "" : value.trim());
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("OTA受控signer端点配置无效");
        }
        boolean secure = "https".equals(uri.getScheme());
        boolean insecure = "http".equals(uri.getScheme());
        if ((!secure && !insecure) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException("OTA受控signer端点配置无效");
        }
        if (!secure && !(allowInsecureLoopback && isLoopback(uri.getHost()))) {
            throw new IllegalStateException("OTA受控signer端点必须使用HTTPS，明文仅限显式允许的回环地址");
        }
        return uri;
    }

    /** 解析主机名判断回环；无法解析按非回环处理，失败方向必须保守。 */
    private static boolean isLoopback(String host) {
        if (host == null || host.isEmpty()) return false;
        String literal = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        try {
            return InetAddress.getByName(literal).isLoopbackAddress();
        } catch (IOException failure) {
            return false;
        }
    }

    /** 端点已配置时凭据必须存在且不含会破坏HTTP头的控制字符。 */
    private static String requireToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("OTA受控signer已配置端点但缺少认证凭据");
        }
        for (int index = 0; index < token.length(); index++) {
            if (Character.isISOControl(token.charAt(index))) {
                throw new IllegalStateException("OTA受控signer认证凭据不合法");
            }
        }
        return token;
    }

    /**
     * 只含固定分类的适配器失败；无cause，避免凭据、请求正文或响应正文经异常链外泄。
     */
    public static final class Failure extends RuntimeException {
        /** 稳定序列化标识。 */
        private static final long serialVersionUID = 1L;
        /** 可供上层持久化或映射的固定失败分类。 */
        private final Reason reason;

        /** 构造安全消息，绝不携带供应商数据。 */
        private Failure(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        /** 返回固定分类，调用方不得据此自动重试或回退其他签名器。 */
        public Reason reason() {
            return reason;
        }
    }

    /** 单次受控调用失败的有界分类。 */
    public enum Reason {
        /** 请求身份或signingInput不满足适配器结构合同。 */
        INVALID_REQUEST,
        /** 剩余预算不足收尾余量，未发起物理调用。 */
        BUDGET_EXHAUSTED,
        /** 外部取消或失权信号中断了在途调用。 */
        CANCELLED,
        /** 网络预算耗尽，调用超时。 */
        TIMEOUT,
        /** 连接中断、TLS失败等传输层失败。 */
        TRANSPORT_FAILURE,
        /** 供应商返回非2xx状态。 */
        HTTP_REJECTED,
        /** 响应超限、无法解析或结构无效。 */
        INVALID_RESPONSE
    }
}
