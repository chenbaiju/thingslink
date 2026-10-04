package com.things.link.bootstrap.ota;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.ota.application.OtaCanonicalJson;
import com.things.link.ota.application.OtaControlledReleaseSigner;
import com.things.link.ota.application.OtaPublicationProcessor;
import com.things.link.ota.application.OtaPublicationService;
import com.things.link.ota.application.OtaUploadProcessor;
import com.things.link.ota.application.OtaUploadService;
import com.things.link.ota.domain.OtaUploadSession;
import com.things.link.ota.infrastructure.signing.HttpControlledReleaseSigner;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketVersioningArgs;
import io.minio.messages.VersioningConfiguration;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR0139生产适配器在真实PG+MinIO+本地HTTP signer替身上的端到端接线。
 *
 * <p>配置{@code things-link.ota.signing.*}后，属性门控的{@link HttpControlledReleaseSigner}
 * 必须成为上下文里唯一的受控signer并驱动发布到READY；把配置移除后必须回到零个bean的
 * 70016 fail-closed。本地替身只签名平台给出的字节，是外部服务的测试替身，不代表KMS/HSM资格。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OtaExternalSignerPublicationHttpIntegrationTests extends OtaExternalSignerHarness {
    /** 生产适配器只由这三项真实配置装配，测试不注入任何本地私钥bean。 */
    @DynamicPropertySource
    static void signing(DynamicPropertyRegistry registry) {
        registry.add("things-link.ota.signing.endpoint", () -> signerEndpoint());
        registry.add("things-link.ota.signing.token", () -> SIGNING_TOKEN);
        registry.add("things-link.ota.signing.allow-insecure-loopback", () -> true);
    }

    /** 必须恰好一个受控signer，且就是属性门控的生产适配器。 */
    @Autowired private ObjectProvider<OtaControlledReleaseSigner> signers;
    /** 真实发布短事务服务。 */
    @Autowired private OtaPublicationService publications;
    /** 生产签名与对象复验编排。 */
    @Autowired private OtaPublicationProcessor publicationProcessor;

    /** 配置生效时，真实HTTP signer替身把一次发布推进到READY并采用对象。 */
    @Test
    void productionAdapterDrivesPublicationToReady() throws Exception {
        assertThat(signers.orderedStream().toList()).hasSize(1);
        assertThat(signers.getIfAvailable()).isInstanceOf(HttpControlledReleaseSigner.class);
        Prepared prepared = prepare();

        var created = ok(send(prepared.fixture(), "POST", prepared.path(), "external-signer", prepared.body(), false), 202);
        var claimed = publications.claim().orElseThrow();
        assertThat(claimed.id().toString()).isEqualTo(created.path("id").asText());
        UUID publicationId = claimed.id();
        byte[] canonicalManifest = claimed.canonicalManifest();
        publicationProcessor.process(claimed);
        var committed = scoped(prepared.fixture(), () -> publications.find(prepared.fixture().projectId(),
                prepared.firmware(), publicationId));

        assertThat(committed.status()).isEqualTo("COMMITTED");
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware WHERE id=?", String.class,
                prepared.firmware())).isEqualTo("READY");
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware_upload_session WHERE id=?", String.class,
                prepared.upload().id())).isEqualTo("ADOPTED");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_firmware_release WHERE firmware_id=?", Long.class,
                prepared.firmware())).isEqualTo(1);
        assertThat(SIGNER_CALLS.get()).isEqualTo(1);

        // 线格式与域分离字节必须与实际发布尝试完全一致，回执签名由协调器复验后才READY。
        assertThat(LAST_AUTHORIZATION.get()).isEqualTo("Bearer " + SIGNING_TOKEN);
        assertThat(LAST_CONTENT_TYPE.get()).isEqualTo("application/json");
        assertThat(LAST_CONTRACT_VERSION.get()).isEqualTo("tc-ota-sign-request/v1");
        assertThat(LAST_REQUEST_ID.get()).isEqualTo(claimed.requestId().toString());
        assertThat(LAST_TRUST_DOMAIN.get()).isEqualTo(DOMAIN);
        assertThat(LAST_KEY_VERSION.get()).isEqualTo("release");
        assertThat(LAST_SIGNATURE_PROFILE.get()).isEqualTo("TC_OTA_ED25519_V1");
        assertThat(LAST_KEY_FINGERPRINT.get()).isEqualTo(sha(RELEASE.getPublic().getEncoded()));
        assertThat(LAST_SIGNING_INPUT.get()).isEqualTo(signingInput(canonicalManifest));
    }
}

/**
 * 与配置生效用例相反：本上下文不设置{@code things-link.ota.signing.endpoint}，
 * 证明"移除配置"后生产适配器不存在，发布仍在创建前按70016失败关闭。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OtaExternalSignerRemovedConfigurationHttpTests extends OtaExternalSignerHarness {
    /** 移除配置后必须回到零个受控signer。 */
    @Autowired private ObjectProvider<OtaControlledReleaseSigner> signers;

    /** 端点未配置时不得装配生产适配器，也不得创建任何发布尝试。 */
    @Test
    void absentEndpointConfigurationKeepsPublicationFailClosed() throws Exception {
        assertThat(signers.orderedStream().toList()).isEmpty();
        assertThat(signers.stream().noneMatch(HttpControlledReleaseSigner.class::isInstance)).isTrue();
        Prepared prepared = prepare();

        error(send(prepared.fixture(), "POST", prepared.path(), "external-signer-absent", prepared.body(), false),
                503, 70016);

        assertThat(owner().queryForObject("SELECT count(*) FROM ota_firmware_publication WHERE firmware_id=?",
                Long.class, prepared.firmware())).isZero();
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware WHERE id=?", String.class,
                prepared.firmware())).isEqualTo("DRAFT");
        assertThat(SIGNER_CALLS.get()).isZero();
    }
}

/**
 * 两个用例共享的真实中间件与夹具：独占MinIO、真实本地HTTP signer替身、PG身份骨架与发布准备。
 *
 * <p>不显式stop MinIO：与{@link AbstractIntegrationTest}的PG/Redis单例一致，由Ryuk在JVM退出后清理，
 * 避免第一个子类的{@code @AfterAll}提前关闭第二个子类仍要使用的容器。</p>
 */
abstract class OtaExternalSignerHarness extends AbstractIntegrationTest {
    /** 根密钥只签信任包，与发布密钥分离。 */
    static final KeyPair ROOT = keyPair();
    /** 发布密钥只在本地替身内持有私钥，用于证明协调器复验的是真实签名。 */
    static final KeyPair RELEASE = keyPair();
    /** 在根配置初始化前冻结独占身份。 */
    private static final Fixture CONFIGURED = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
    /** 本类独占信任域。 */
    static final String DOMAIN = "ext-" + UUID.randomUUID();
    /** 域分离签名字节前缀，与生产验签内部一致。 */
    private static final byte[] DOMAIN_PREFIX =
            "thingslink-ota-release-manifest-v1\0".getBytes(StandardCharsets.UTF_8);
    /** 独立生成精确根签名包与发布manifest的规范字节。 */
    private static final OtaCanonicalJson CANONICAL = new OtaCanonicalJson();
    /** 只供独占容器使用的测试凭据。 */
    private static final String ACCESS = "external-signer-test", SECRET = "external-signer-secret";
    /** 本测试唯一桶。 */
    private static final String BUCKET = "external-signer-" + UUID.randomUUID();
    /** 只供测试替身使用的固定认证凭据。 */
    static final String SIGNING_TOKEN = "bootstrap-signing-credential";
    /** 替身固定路径。 */
    private static final String SIGN_PATH = "/sign";
    /** 响应只作断言，不伪造生产事实。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 替身物理调用次数，证明不重试。 */
    static final AtomicInteger SIGNER_CALLS = new AtomicInteger();
    /** 替身最近一次观察到的冻结请求字段。 */
    static final AtomicReference<String> LAST_AUTHORIZATION = new AtomicReference<>();
    /** 替身最近一次观察到的内容类型。 */
    static final AtomicReference<String> LAST_CONTENT_TYPE = new AtomicReference<>();
    /** 替身最近一次观察到的请求合同版本。 */
    static final AtomicReference<String> LAST_CONTRACT_VERSION = new AtomicReference<>();
    /** 替身最近一次观察到的请求身份。 */
    static final AtomicReference<String> LAST_REQUEST_ID = new AtomicReference<>();
    /** 替身最近一次观察到的可信域。 */
    static final AtomicReference<String> LAST_TRUST_DOMAIN = new AtomicReference<>();
    /** 替身最近一次观察到的密钥版本。 */
    static final AtomicReference<String> LAST_KEY_VERSION = new AtomicReference<>();
    /** 替身最近一次观察到的签名Profile。 */
    static final AtomicReference<String> LAST_SIGNATURE_PROFILE = new AtomicReference<>();
    /** 替身最近一次观察到的密钥指纹。 */
    static final AtomicReference<String> LAST_KEY_FINGERPRINT = new AtomicReference<>();
    /** 替身最近一次观察到的域分离签名字节。 */
    static final AtomicReference<byte[]> LAST_SIGNING_INPUT = new AtomicReference<>();
    /** 独占真实存储，启动失败不降级。 */
    private static final GenericContainer<?> MINIO = new GenericContainer<>(
            "minio/minio:RELEASE.2025-04-22T22-12-26Z")
            .withEnv("MINIO_ROOT_USER", ACCESS).withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .withCommand("server", "/data").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    /** 真实本地signer替身，独立线程池允许阻塞反例不占住派发线程。 */
    private static final HttpServer SIGNER = startSigner();

    /** Spring读取配置前先建立版本化私桶，失败时关闭本容器。 */
    static {
        MINIO.start();
        try (MinioClient admin = admin()) {
            admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
            admin.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(BUCKET)
                    .config(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED, null, null, null)).build());
        } catch (Exception failure) {
            MINIO.stop();
            throw new ExceptionInInitializerError(failure);
        }
    }

    /** 真实适配器由四项存储配置装配，关闭自动领取以独立观察发布与上传状态。 */
    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("things-link.storage.internal-endpoint", OtaExternalSignerHarness::endpoint);
        registry.add("things-link.storage.external-endpoint", OtaExternalSignerHarness::endpoint);
        registry.add("things-link.storage.access-key", () -> ACCESS);
        registry.add("things-link.storage.secret-key", () -> SECRET);
        registry.add("things-link.ota.storage.bucket", () -> BUCKET);
        registry.add("things-link.ota.upload.recovery-enabled", () -> false);
        registry.add("things-link.ota.publication.enabled", () -> false);
        registry.add("things-link.ota.trust.anchors-json", () -> new String(CANONICAL.writeObject(Map.of(
                "anchors", List.of(Map.of("tenantId", CONFIGURED.tenantId().toString(),
                "projectId", CONFIGURED.projectId().toString(), "trustDomain", DOMAIN,
                "allowedDeviceTypeIds", List.of(CONFIGURED.typeId().toString()), "rootProfile", "TC_OTA_ED25519_V1",
                "rootSpki", b64(ROOT.getPublic().getEncoded()), "rootFingerprint", sha(ROOT.getPublic().getEncoded()),
                "policyRevision", 1L)))), StandardCharsets.UTF_8));
    }

    /** 真正随机监听端口。 */ @Value("${local.server.port}") private int port;
    /** JWT仍经过真实验签与数据库角色读取。 */ @Autowired private TokenIssuer tokens;
    /** 真实上传身份与取消服务。 */ @Autowired private OtaUploadService uploads;
    /** 真实MinIO写入和固定版本验真编排。 */ @Autowired private OtaUploadProcessor uploadProcessor;
    /** JUnit独占上传临时目录。 */ @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    /** 每例自有项目图。 */ private final List<Fixture> fixtures = new ArrayList<>();

    /** 新OTA引用必须先清理，再删除本例模型与项目；不修改触发器或禁用外键。 */
    @AfterEach
    void clearOwnedFacts() throws Exception {
        try (MinioClient admin = admin()) {
            for (var item : admin.listObjects(ListObjectsArgs.builder().bucket(BUCKET)
                    .recursive(true).includeVersions(true).build())) {
                var version = item.get();
                admin.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(version.objectName())
                        .versionId(version.versionId()).build());
            }
        }
        JdbcTemplate owner = owner();
        for (Fixture fixture : fixtures) {
            owner.update("DELETE FROM ota_firmware_release WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_firmware_publication WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_trust_bundle WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_trust_domain WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_firmware_upload_session WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_firmware_creation_request WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_firmware WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM dev_thing_model_version WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM dev_type WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM sys_project_member WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM sys_project WHERE id = ?", fixture.projectId());
            owner.update("DELETE FROM sys_tenant_member WHERE tenant_id = ?", fixture.tenantId());
            owner.update("DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
            owner.update("DELETE FROM sys_account WHERE id IN (?, ?)", fixture.accountId(), fixture.ownerId());
        }
        fixtures.clear();
        SIGNER_CALLS.set(0);
    }

    /** 真实业务API建草稿、导入信任并完成受控文件上传，使发布准备不是测试伪造状态。 */
    Prepared prepare() throws Exception {
        Fixture fixture = seed(ProjectRole.ADMIN);
        var firmware = ok(send(fixture, "POST", base(fixture), key(),
                body(fixture, "external-signer-v1").getBytes(StandardCharsets.UTF_8), false), 201);
        UUID firmwareId = UUID.fromString(firmware.path("id").asText());
        ok(send(fixture, "POST", "/api/v1/projects/" + fixture.projectId() + "/ota/trust-domains/" + DOMAIN + "/bundles",
                key(), trustEnvelope("0", 1, false), false), 200);
        byte[] content = new byte[] {4,3,2,1,0};
        var created = scoped(fixture, () -> uploads.create(fixture.projectId(), firmwareId, key(), content.length, sha(content)));
        var receiving = scoped(fixture, () -> uploads.prepare(fixture.projectId(), firmwareId, created.id()));
        var file = temporary.resolve(created.id() + ".bin");
        java.nio.file.Files.write(file, content);
        var uploaded = scoped(fixture, () -> {
            try (var lease = uploadProcessor.begin(receiving)) {
                return uploadProcessor.process(receiving, file, lease, () -> false);
            }
        });
        assertThat(uploaded.status()).isEqualTo("VERIFIED");
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("contractVersion", "tc-ota-manifest/v1");
        manifest.put("firmwareId", firmwareId.toString());
        manifest.put("firmwareVersion", "external-signer-v1");
        manifest.put("trustDomain", DOMAIN);
        manifest.put("deviceTypeId", fixture.typeId().toString());
        manifest.put("productKey", "product_" + fixture.typeId());
        manifest.put("hardware", Map.of("model", "board-v1", "boardRevisionMin", 0L, "boardRevisionMax", 1L));
        manifest.put("bootloaderMinimumVersion", "1.0.0");
        manifest.put("artifactSize", (long) content.length);
        manifest.put("artifactSha256", sha(content));
        manifest.put("compression", "NONE");
        manifest.put("delta", Map.of("mode", "NONE"));
        manifest.put("securityVersion", 1L);
        manifest.put("thingModelVersionId", fixture.modelId().toString());
        manifest.put("thingModelSchemaDigestAlgorithm", "PG_JSONB_TEXT_V1_SHA256");
        manifest.put("thingModelSchemaDigest", "a".repeat(64));
        manifest.put("allowedSourceThingModelVersionIds", List.of(fixture.modelId().toString()));
        manifest.put("requirements", Map.of("profile", "TC_PROPERTY_COMPOSITE_V1", "minimumRamBytes", 1L,
                "minimumFlashBytes", 1L, "requiresAbSlots", true, "requiresRangeDownload", true,
                "requiresProtectedSecurityCounter", true));
        manifest.put("signatureProfile", "TC_OTA_ED25519_V1");
        manifest.put("signingKeyFingerprint", sha(RELEASE.getPublic().getEncoded()));
        manifest.put("minimumTrustBundleVersion", 1L);
        byte[] request = CANONICAL.writeObject(Map.of("expectedRevision", firmware.path("revision").asText(),
                "uploadSessionId", uploaded.id().toString(), "manifest", manifest));
        return new Prepared(fixture, firmwareId, uploaded,
                base(fixture) + "/" + firmwareId + "/publications", request);
    }

    /** 根签名包来自独立JCA，测试发布key不等于根。 */
    private static byte[] trustEnvelope(String revision, long version, boolean rotate) throws Exception {
        var keys = rotate
                ? List.of(trustKey(RELEASE, "release", "VERIFY_ONLY"), trustKey(RELEASE, "rotated", "ACTIVE"))
                : List.of(trustKey(RELEASE, "release", "ACTIVE"));
        Map<String, Object> bundle = Map.of("contractVersion", "tc-ota-trust-bundle/v1", "trustDomain", DOMAIN,
                "bundleVersion", version, "keys", keys);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(ROOT.getPrivate());
        signature.update("thingslink-ota-trust-bundle-v1\0".getBytes(StandardCharsets.UTF_8));
        signature.update(CANONICAL.writeObject(bundle));
        return CANONICAL.writeObject(Map.of("expectedRevision", revision, "bundle", bundle,
                "signature", b64(signature.sign())));
    }

    /** 不可变公钥身份和有效期。 */
    private static Map<String, Object> trustKey(KeyPair pair, String version, String state) {
        return Map.of("keyVersion", version, "signatureProfile", "TC_OTA_ED25519_V1",
                "spki", b64(pair.getPublic().getEncoded()), "fingerprint", sha(pair.getPublic().getEncoded()),
                "state", state, "notBefore", 0L, "notAfter", 253402300799L);
    }

    /** 异步/服务调用都显式绑定本例的真实scope。 */
    static <T> T scoped(Fixture fixture, java.util.function.Supplier<T> work) {
        return com.things.link.support.tenant.ScopedTenantWork.call(new com.things.link.shared.tenant.TenantScope(
                fixture.tenantId(), fixture.projectId(), fixture.accountId()), work);
    }

    /** 域分离签名字节，与OtaReleaseSignatureVerifier内部构造一致。 */
    static byte[] signingInput(byte[] canonical) {
        byte[] input = new byte[DOMAIN_PREFIX.length + canonical.length];
        System.arraycopy(DOMAIN_PREFIX, 0, input, 0, DOMAIN_PREFIX.length);
        System.arraycopy(canonical, 0, input, DOMAIN_PREFIX.length, canonical.length);
        return input;
    }

    /** 属性门控的适配器端点，指向真实本地替身。 */
    static String signerEndpoint() {
        return "http://127.0.0.1:" + SIGNER.getAddress().getPort() + SIGN_PATH;
    }

    /** 真实本地signer替身；只签名平台给出的字节，不代表KMS/HSM资格。 */
    private static HttpServer startSigner() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext(SIGN_PATH, OtaExternalSignerHarness::handleSign);
            server.start();
            return server;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /** 记录冻结请求字段并返回真实Ed25519回执；失败时不泄露任何请求正文。 */
    private static void handleSign(HttpExchange exchange) throws IOException {
        SIGNER_CALLS.incrementAndGet();
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        LAST_AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
        LAST_CONTENT_TYPE.set(exchange.getRequestHeaders().getFirst("Content-Type"));
        int status = 200;
        byte[] response;
        try {
            var request = JSON.readTree(requestBody);
            LAST_CONTRACT_VERSION.set(request.path("contractVersion").asText());
            LAST_REQUEST_ID.set(request.path("requestId").asText());
            LAST_TRUST_DOMAIN.set(request.path("trustDomain").asText());
            LAST_KEY_VERSION.set(request.path("keyVersion").asText());
            LAST_SIGNATURE_PROFILE.set(request.path("signatureProfile").asText());
            LAST_KEY_FINGERPRINT.set(request.path("keyFingerprint").asText());
            byte[] signingInput = Base64.getDecoder().decode(request.path("signingInput").asText());
            LAST_SIGNING_INPUT.set(signingInput);
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(RELEASE.getPrivate());
            signature.update(signingInput);
            response = JSON.writeValueAsString(Map.of(
                    "contractVersion", "tc-ota-sign-response/v1",
                    "requestId", request.path("requestId").asText(),
                    "keyVersion", request.path("keyVersion").asText(),
                    "signatureProfile", request.path("signatureProfile").asText(),
                    "spkiBase64", b64(RELEASE.getPublic().getEncoded()),
                    "signatureBase64", b64(signature.sign()),
                    "receipt", "stub-receipt-" + SIGNER_CALLS.get())).getBytes(StandardCharsets.UTF_8);
        } catch (Exception failure) {
            status = 500;
            response = "{\"error\":\"stub-failure\"}".getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        } finally {
            exchange.close();
        }
    }

    /** 完整HTTP/1.1往返预算，客户端关闭后不留连接线程。 */
    HttpResponse<String> send(Fixture fixture, String method, String path, String idempotencyKey,
            byte[] body, boolean binary) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(45));
        if (fixture != null) {
            builder.header("Authorization", "Bearer " + tokens.issue(
                    new AuthenticatedPrincipal(fixture.accountId(), fixture.tenantId(), fixture.projectId())).value());
        }
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        if (body != null) builder.header("Content-Type", binary ? "application/octet-stream" : "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /** 唯一请求键。 */
    static String key() {
        return UUID.randomUUID().toString();
    }

    /** 明确HTTP状态并保留响应首因。 */
    static tools.jackson.databind.JsonNode ok(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }

    /** HTTP状态和领域码均须匹配。 */
    static void error(HttpResponse<String> response, int status, int code) {
        assertThat(ok(response, status).path("code").asInt()).isEqualTo(code);
    }

    /** 只连接独占测试存储。 */
    private static String endpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    /** 管理端只用于前提和物理证据观察。 */
    private static MinioClient admin() {
        return MinioClient.builder().endpoint(endpoint()).credentials(ACCESS, SECRET).build();
    }

    /** owner连接仅用于准备/清理与观察，不走生产读取断言。 */
    static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
    }

    /** 独立身份骨架与已发布类型/不可变模型，owner只准备夹具。 */
    private Fixture seed(ProjectRole role) {
        Fixture fixture = CONFIGURED;
        fixtures.add(fixture);
        JdbcTemplate owner = owner();
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'OTA测试租户')", fixture.tenantId());
        for (UUID account : List.of(fixture.accountId(), fixture.ownerId())) {
            owner.update("INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at) "
                    + "VALUES (?,?,'{noop}unused','OTA测试',now())", account, account + "@example.invalid");
            owner.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",
                    Uuid7.generate(), fixture.tenantId(), account);
        }
        owner.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'OTA测试项目','sh-1',?)",
                fixture.projectId(), fixture.tenantId(), "ota_" + fixture.projectId().toString().replace("-", ""));
        UUID ownerId = role == ProjectRole.OWNER ? fixture.accountId() : fixture.ownerId();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",
                Uuid7.generate(), fixture.projectId(), ownerId);
        if (role != ProjectRole.OWNER) {
            owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,?)",
                    Uuid7.generate(), fixture.projectId(), fixture.accountId(), role.name());
        }
        owner.update("""
                INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status,product_key,product_secret_hash)
                VALUES (?,?,?,?,'OTA测试类型','DIRECT','STANDARD','WIFI','PUBLISHED',?,repeat('a',64))
                """, fixture.typeId(), fixture.tenantId(), fixture.projectId(), "type_" + fixture.typeId(),
                "product_" + fixture.typeId());
        owner.update("""
                INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                    version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                VALUES (?,?,?,?,'1.0.0',1,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1',
                    '{"properties":{},"events":{},"commands":{}}'::jsonb,?,'PG_JSONB_TEXT_V1_SHA256')
                """, fixture.modelId(), fixture.tenantId(), fixture.projectId(), fixture.typeId(), "a".repeat(64));
        return fixture;
    }

    /** 原样拼接固定安全fixture UUID，保留版本反例中的JSON词法。 */
    private static String body(Fixture fixture, String version) {
        return "{\"deviceTypeId\":\"" + fixture.typeId() + "\",\"thingModelVersionId\":\""
                + fixture.modelId() + "\",\"firmwareVersion\":\"" + version + "\"}";
    }

    /** 唯一冻结资源根，不扩展到设备原生程序。 */
    private static String base(Fixture fixture) {
        return "/api/v1/projects/" + fixture.projectId() + "/ota/firmwares";
    }

    /** 标准Base64。 */
    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** 完整字节SHA256。 */
    static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** 测试专属公私钥。 */
    private static KeyPair keyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /** 本例精确资源，不携带其他项目事实。
     * @param fixture 本例真实成员和项目
     * @param firmware 通过HTTP创建的固件身份
     * @param upload 已通过真实存储验真的上传
     * @param path 发布尝试HTTP集合路径
     * @param body 完整严格发布请求
     */
    record Prepared(Fixture fixture, UUID firmware, OtaUploadSession upload, String path, byte[] body) {
    }

    /** 每例全部归属，确保失败后也能精确清理。
     * @param tenantId 本例租户
     * @param projectId 本例项目
     * @param accountId 被测请求账号
     * @param ownerId 保留项目所有者的独立账号
     * @param typeId 发布类型
     * @param modelId 不可变模型版本
     */
    record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID ownerId, UUID typeId, UUID modelId) {
    }
}
