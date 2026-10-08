package com.things.link.bootstrap.ota;

import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketVersioningArgs;
import io.minio.messages.VersioningConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实PG与MinIO管理下载专项；仅证明Console管理权限，不代表设备下载或更新资格。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(OtaReleaseDownloadHttpIntegrationTests.SigningConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OtaReleaseDownloadHttpIntegrationTests extends AbstractIntegrationTest {
    /** 仅本测试内存持有私钥，显式测试配置不进入生产装配。 */
    private static final java.security.KeyPair ROOT = keyPair(), RELEASE = keyPair(), ROTATED = keyPair();
    /** 每例根签名冻结的发布key到期；仅过期专项缩短，不能修改已登记有效期。 */
    private static long releaseNotAfter = 253402300799L;
    /** 在根配置初始化前冻结独占身份。 */
    private static final Fixture CONFIGURED = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
    /** 本类独占信任域，避免与共享数据库其他验收互相授权。 */
    private static final String DOMAIN = "pub-" + UUID.randomUUID();
    /** 独立生成精确根签名包与发布manifest的规范字节。 */
    private static final com.things.link.ota.application.OtaCanonicalJson CANONICAL = new com.things.link.ota.application.OtaCanonicalJson();
    /** 只供独占容器使用的测试凭据。 */
    private static final String ACCESS = "release-download-http-test", SECRET = "release-download-http-secret";
    /** 本测试唯一桶，不触碰开发存储。 */
    private static final String BUCKET = "release-download-http-" + UUID.randomUUID();
    /** 独占真实存储，启动失败不降级。 */
    private static final GenericContainer<?> MINIO = new GenericContainer<>(
            "minio/minio:RELEASE.2025-04-22T22-12-26Z")
            .withEnv("MINIO_ROOT_USER", ACCESS).withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .withCommand("server", "/data").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    /** Spring读取配置前先建立版本化私桶，失败时关闭本容器。 */
    static {
        MINIO.start();
        try (MinioClient admin = admin()) {
            admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
            admin.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(BUCKET)
                    .config(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED, null, null, null)).build());
        } catch (Exception failure) { MINIO.stop(); throw new ExceptionInInitializerError(failure); }
    }
    /** 真实适配器由四项配置装配，关闭自动领取以独立观察发布与上传状态。 */
    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("things-link.storage.internal-endpoint", OtaReleaseDownloadHttpIntegrationTests::endpoint);
        registry.add("things-link.storage.external-endpoint", OtaReleaseDownloadHttpIntegrationTests::endpoint);
        registry.add("things-link.storage.access-key", () -> ACCESS);
        registry.add("things-link.storage.secret-key", () -> SECRET);
        registry.add("things-link.ota.storage.bucket", () -> BUCKET);
        registry.add("things-link.ota.upload.recovery-enabled", () -> false);
        registry.add("things-link.ota.publication.enabled", () -> false);
        registry.add("things-link.ota.download.allow-insecure-loopback", () -> true);
        registry.add("things-link.ota.trust.anchors-json", () -> new String(CANONICAL.writeObject(java.util.Map.of(
                "anchors", List.of(java.util.Map.of("tenantId", CONFIGURED.tenantId().toString(),
                "projectId", CONFIGURED.projectId().toString(), "trustDomain", DOMAIN,
                "allowedDeviceTypeIds", List.of(CONFIGURED.typeId().toString()), "rootProfile", "TC_OTA_ED25519_V1",
                "rootSpki", b64(ROOT.getPublic().getEncoded()), "rootFingerprint", sha(ROOT.getPublic().getEncoded()),
                "policyRevision", 1L)))), StandardCharsets.UTF_8));
    }
    /** 真正随机监听端口。 */ @Value("${local.server.port}") private int port;
    /** JWT仍经过真实验签与数据库角色读取。 */ @Autowired private TokenIssuer tokens;
    /** 响应只作断言，不能伪造生产事实。 */ private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 每例自有项目图。 */ private final List<Fixture> fixtures = new ArrayList<>();


    /** 真实发布短事务服务，不以测试代码直接插入尝试。 */
    @Autowired private com.things.link.ota.application.OtaPublicationService publications;
    /** 生产签名与对象复验编排，测试显式调用受限领取结果。 */
    @Autowired private com.things.link.ota.application.OtaPublicationProcessor publicationProcessor;
    /** 真实上传身份与取消服务。 */
    @Autowired private com.things.link.ota.application.OtaUploadService uploads;
    /** 真实MinIO写入和固定版本验真编排。 */
    @Autowired private com.things.link.ota.application.OtaUploadProcessor uploadProcessor;
    /** 真实对象回收worker，不用删除数据库行冒充物理清理。 */
    @Autowired private com.things.link.ota.application.OtaUploadRecoveryWorker uploadRecovery;
    /** 根签名包授权及当前发布键资格。 */
    @Autowired private com.things.link.ota.application.OtaTrustService trust;
    /** 仅本类显式装配的可控JCA测试签名器。 */
    @Autowired private TestSigner signer;
    /** 项目受限清理批次所需的普通APP实际事务。 */
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    /** 当前普通APP连接，不能由owner连接代替生产清理入口。 */
    @Autowired private JdbcTemplate jdbc;
    /** JUnit独占上传临时目录，测试结束自动清理。 */
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    /** 每例重置调用计数及故障接缝，避免共享测试Bean泄漏上例行为。 */
    @org.junit.jupiter.api.BeforeEach void resetSigner() { releaseNotAfter = 253402300799L; signer.calls.set(0); signer.beforeReturn = () -> { }; signer.unknown = false; signer.corrupt = false; }
    /** 对真实存储Bean施加返回前接缝，所有签名URL仍由真实MinIO适配器产生。 */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.things.link.support.storage.VersionedPrivateObjectStorage storage;

    /** 发布公开字段和下载都经真实管理认证，字节采用固定对象版本，范围拼接还原摘要。 */
    @Test
    void readsPublicProofAndDownloadsOriginalVersionWithRealRanges() throws Exception {
        Prepared prepared = published();
        var read = send(prepared.fixture(), "GET", releasePath(prepared), null, null, false);
        JsonNode proof = ok(read, 200);
        assertThat(read.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(proof.propertyNames()).containsExactlyInAnyOrder("firmwareId", "publicationId", "manifestBase64",
                "signatureBase64", "publicKeySpkiBase64", "signatureProfile", "keyFingerprint", "artifactSize",
                "artifactSha256", "releaseCreatedAt");
        assertThat(proof.path("signatureProfile").asText()).isEqualTo("TC_OTA_ED25519_V1");
        assertThat(proof.path("artifactSize").asText()).isEqualTo("5");
        byte[] manifest = java.util.Base64.getDecoder().decode(proof.path("manifestBase64").asText());
        var verifier = java.security.Signature.getInstance("Ed25519");
        verifier.initVerify(RELEASE.getPublic());
        verifier.update("thingslink-ota-release-manifest-v1\0".getBytes(StandardCharsets.UTF_8));
        verifier.update(manifest);
        assertThat(verifier.verify(java.util.Base64.getDecoder().decode(proof.path("signatureBase64").asText()))).isTrue();
        try (MinioClient admin = admin()) {
            byte[] newer = {1,2,3};
            admin.putObject(io.minio.PutObjectArgs.builder().bucket(BUCKET).object(prepared.upload().objectKey())
                    .stream(new java.io.ByteArrayInputStream(newer), (long) newer.length, -1L).build());
        }
        String requestKey = key();
        var response = send(prepared.fixture(), "POST", releasePath(prepared) + "/downloads", requestKey, null, false);
        JsonNode download = ok(response, 200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(download.propertyNames()).containsExactlyInAnyOrder("firmwareId", "downloadUrl", "expiresAt");
        URI url = URI.create(download.path("downloadUrl").asText());
        // 失败诊断也不打印签名query；只报告固定合同是否满足。
        assertThat(url.getRawQuery().contains("X-Amz-Expires=60") && url.getRawQuery().contains("versionId="))
                .as("管理票据使用60秒TTL并绑定固定对象版本").isTrue();
        var first = bytes(url, "bytes=0-1");
        var rest = bytes(url, "bytes=2-4");
        assertThat(first.statusCode()).isEqualTo(206);
        assertThat(first.headers().firstValue("Content-Range")).contains("bytes 0-1/5");
        assertThat(rest.statusCode()).isEqualTo(206);
        assertThat(rest.headers().firstValue("Content-Range")).contains("bytes 2-4/5");
        byte[] complete = new byte[5];
        System.arraycopy(first.body(), 0, complete, 0, 2);
        System.arraycopy(rest.body(), 0, complete, 2, 3);
        assertThat(sha(complete)).isEqualTo(proof.path("artifactSha256").asText());
        assertThat(bytes(url, "bytes=5-9").statusCode()).isEqualTo(416);
        error(send(prepared.fixture(), "POST", releasePath(prepared) + "/downloads", requestKey, null, false), 409, 10014);
        assertThat(bytes(URI.create(url.toString().replaceAll("X-Amz-Signature=[0-9a-f]+",
                "X-Amz-Signature=" + "0".repeat(64))), null).statusCode()).isEqualTo(403);
        assertThat(bytes(new URI(url.getScheme(), url.getAuthority(), url.getPath(), null, null), null).statusCode()).isEqualTo(403);
    }

    /** 技术端口使用一秒TTL独立验证实际过期，不为每张管理票据固定等待一分钟。 */
    @Test
    void rejectsActuallyExpiredShortTechnicalTicket() throws Exception {
        Prepared prepared = published();
        var ref = new com.things.link.support.storage.VersionedPrivateObjectStorage.VersionRef(
                BUCKET, prepared.upload().objectKey(), prepared.upload().versionId());
        URI url = storage.presignGet(ref, Duration.ofSeconds(1),
                new com.things.link.support.storage.VersionedStorageControl(Duration.ofSeconds(5), () -> false));
        assertThat(bytes(url, null).statusCode()).isEqualTo(200);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(bytes(url, null).statusCode()).isEqualTo(403));
    }

    /** 缺认证、越项目、低权限及客户端正文均不能签发；OWNER仍可管理读取。 */
    @Test
    void enforcesAuthenticationRoleScopeAndEmptyDownloadBody() throws Exception {
        Prepared prepared = published();
        assertThat(send(null, "GET", releasePath(prepared), null, null, false).statusCode()).isEqualTo(401);
        assertThat(send(prepared.fixture(), "GET", releasePath(prepared).replace(prepared.fixture().projectId().toString(),
                Uuid7.generate().toString()), null, null, false).statusCode()).isIn(403, 404);
        error(send(prepared.fixture(), "POST", releasePath(prepared) + "/downloads", null, null, false), 400, 10001);
        error(send(prepared.fixture(), "POST", releasePath(prepared) + "/downloads", key(), "{}".getBytes(StandardCharsets.UTF_8), false), 400, 10001);
        owner().update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",
                prepared.fixture().projectId(), prepared.fixture().accountId());
        error(send(prepared.fixture(), "GET", releasePath(prepared), null, null, false), 403, 70024);
        error(send(prepared.fixture(), "POST", releasePath(prepared) + "/downloads", key(), null, false), 403, 70024);
        Fixture f = prepared.fixture();
        Fixture actualOwner = new Fixture(f.tenantId(), f.projectId(), f.ownerId(), f.ownerId(), f.typeId(), f.modelId());
        ok(send(actualOwner, "GET", releasePath(prepared), null, null, false), 200);
    }

    /** VERIFY_ONLY继续验证既有发布；根签名撤销后不再生成新的管理票据。 */
    @Test
    void acceptsVerifyOnlyButRejectsRevokedOriginalSigningKey() throws Exception {
        Prepared prepared = published();
        importState(prepared, "1", 2, "VERIFY_ONLY");
        ok(send(prepared.fixture(), "GET", releasePath(prepared), null, null, false), 200);
        ok(send(prepared.fixture(), "POST", releasePath(prepared) + "/downloads", key(), null, false), 200);
        importState(prepared, "2", 3, "REVOKED");
        error(send(prepared.fixture(), "GET", releasePath(prepared), null, null, false), 409, 70014);
        error(send(prepared.fixture(), "POST", releasePath(prepared) + "/downloads", key(), null, false), 409, 70014);
    }

    /** 合法根包从导入时即冻结短有效期，真实时间到期后既有READY也不可下载。 */
    @Test
    void rejectsPublishedKeyAfterItsOriginalValidityExpires() throws Exception {
        releaseNotAfter = java.time.Instant.now().getEpochSecond() + 12;
        Prepared prepared = published();
        ok(send(prepared.fixture(), "GET", releasePath(prepared), null, null, false), 200);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300))
                .until(() -> java.time.Instant.now().getEpochSecond() >= releaseNotAfter);
        error(send(prepared.fixture(), "GET", releasePath(prepared), null, null, false), 409, 70014);
        error(send(prepared.fixture(), "POST", releasePath(prepared) + "/downloads", key(), null, false), 409, 70014);
    }

    /** 签发网络窗口内角色、成员、信任修订或项目资格变化，二次事务拒绝返回bearer地址。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"role", "removed", "rotate", "archived", "project"})
    void refusesTicketWhenEligibilityChangesDuringPresigning(String change) throws Exception {
        Prepared prepared = published();
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Object url = invocation.callRealMethod();
            switch (change) {
                case "role" -> owner().update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",
                        prepared.fixture().projectId(), prepared.fixture().accountId());
                case "removed" -> owner().update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?",
                        prepared.fixture().projectId(), prepared.fixture().accountId());
                case "rotate" -> importState(prepared, "1", 2, "VERIFY_ONLY");
                case "archived" -> owner().update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",
                        prepared.fixture().projectId());
                case "project" -> owner().update("UPDATE sys_project SET status='DELETING',deleted_at=now(),lifecycle_generation=1 WHERE id=?",
                        prepared.fixture().projectId());
                default -> throw new AssertionError(change);
            }
            return url;
        }).when(storage).presignGet(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        var rejected = send(prepared.fixture(), "POST", releasePath(prepared) + "/downloads", key(), null, false);
        switch (change) {
            case "role" -> error(rejected, 403, 70024);
            case "removed" -> error(rejected, 404, 50001);
            case "rotate" -> error(rejected, 409, 70022);
            case "archived" -> error(rejected, 403, 50017);
            case "project" -> error(rejected, 404, 50001);
            default -> throw new AssertionError(change);
        }
        assertThat(rejected.body().contains("downloadUrl") || rejected.body().contains("X-Amz")
                || rejected.body().contains(prepared.upload().versionId()))
                .as("资格变化拒绝响应不得泄漏票据或对象版本").isFalse();
    }

    /** 以真实发布流程取得READY，不直接改写发布、上传或信任事实。 */
    private Prepared published() throws Exception {
        Prepared prepared = prepare();
        ok(send(prepared.fixture(), "POST", prepared.path(), key(), prepared.body(), false), 202);
        publicationProcessor.process(publications.claim().orElseThrow());
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware WHERE id=?", String.class, prepared.firmware())).isEqualTo("READY");
        return prepared;
    }

    /** 真实根签名更高版本包改变原key状态，保留不可变身份及有效期。 */
    private void importState(Prepared prepared, String revision, long version, String state) throws Exception {
        var bundle = java.util.Map.<String,Object>of("contractVersion", "tc-ota-trust-bundle/v1", "trustDomain", DOMAIN,
                "bundleVersion", version, "keys", List.of(trustKey(RELEASE, "release", state), trustKey(ROTATED, "rotated", "ACTIVE")));
        var signature = java.security.Signature.getInstance("Ed25519");
        signature.initSign(ROOT.getPrivate());
        signature.update("thingslink-ota-trust-bundle-v1\0".getBytes(StandardCharsets.UTF_8));
        byte[] canonical = CANONICAL.writeObject(bundle);
        signature.update(canonical);
        byte[] signed = signature.sign();
        scoped(prepared.fixture(), () -> trust.importBundle(prepared.fixture().projectId(), DOMAIN, key(), revision, canonical, signed));
    }

    /** 发布物唯一管理路径。 */
    private static String releasePath(Prepared prepared) { return base(prepared.fixture()) + "/" + prepared.firmware() + "/release"; }

    /** 真实存储HTTP读取，返回原始字节以免字符串解码损坏摘要。 */
    private static HttpResponse<byte[]> bytes(URI url, String range) throws Exception {
        var request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5)).GET();
        if (range != null) request.header("Range", range);
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        }
    }

    /** 真实业务API建草稿和导入信任，真实受控文件上传使VERIFIED不是owner伪造状态。 */
    private Prepared prepare() throws Exception {
        Fixture fixture = seed(ProjectRole.ADMIN);
        JsonNode firmware = ok(send(fixture, "POST", base(fixture), key(), body(fixture, "publication-v1").getBytes(StandardCharsets.UTF_8), false), 201);
        UUID firmwareId = UUID.fromString(firmware.path("id").asText());
        ok(send(fixture, "POST", "/api/v1/projects/" + fixture.projectId() + "/ota/trust-domains/" + DOMAIN + "/bundles",
                key(), trustEnvelope("0", 1, false), false), 200);
        byte[] content = new byte[] {9,8,7,6,5};
        var created = scoped(fixture, () -> uploads.create(fixture.projectId(), firmwareId, key(), content.length, sha(content)));
        var receiving = scoped(fixture, () -> uploads.prepare(fixture.projectId(), firmwareId, created.id()));
        var file = temporary.resolve(created.id() + ".bin"); java.nio.file.Files.write(file, content);
        var uploaded = scoped(fixture, () -> { try (var lease = uploadProcessor.begin(receiving)) {
            return uploadProcessor.process(receiving, file, lease, () -> false);
        }});
        assertThat(uploaded.status()).isEqualTo("VERIFIED");
        var manifest = new java.util.LinkedHashMap<String,Object>();
        manifest.put("contractVersion", "tc-ota-manifest/v1"); manifest.put("firmwareId", firmwareId.toString());
        manifest.put("firmwareVersion", "publication-v1"); manifest.put("trustDomain", DOMAIN);
        manifest.put("deviceTypeId", fixture.typeId().toString()); manifest.put("productKey", "product_" + fixture.typeId());
        manifest.put("hardware", java.util.Map.of("model", "board-v1", "boardRevisionMin", 0L, "boardRevisionMax", 1L));
        manifest.put("bootloaderMinimumVersion", "1.0.0"); manifest.put("artifactSize", (long) content.length);
        manifest.put("artifactSha256", sha(content)); manifest.put("compression", "NONE");
        manifest.put("delta", java.util.Map.of("mode", "NONE")); manifest.put("securityVersion", 1L);
        manifest.put("thingModelVersionId", fixture.modelId().toString()); manifest.put("thingModelSchemaDigestAlgorithm", "PG_JSONB_TEXT_V1_SHA256");
        manifest.put("thingModelSchemaDigest", "a".repeat(64)); manifest.put("allowedSourceThingModelVersionIds", List.of(fixture.modelId().toString()));
        manifest.put("requirements", java.util.Map.of("profile", "TC_PROPERTY_COMPOSITE_V1", "minimumRamBytes", 1L, "minimumFlashBytes", 1L,
                "requiresAbSlots", true, "requiresRangeDownload", true, "requiresProtectedSecurityCounter", true));
        manifest.put("signatureProfile", "TC_OTA_ED25519_V1"); manifest.put("signingKeyFingerprint", sha(RELEASE.getPublic().getEncoded()));
        manifest.put("minimumTrustBundleVersion", 1L);
        byte[] request = CANONICAL.writeObject(java.util.Map.of("expectedRevision", firmware.path("revision").asText(),
                "uploadSessionId", uploaded.id().toString(), "manifest", manifest));
        return new Prepared(fixture, firmwareId, uploaded, base(fixture) + "/" + firmwareId + "/publications", request);
    }
    /** 根签名包来自独立JCA，测试发布key不等于根。 */
    private static byte[] trustEnvelope(String revision, long version, boolean rotate) throws Exception {
        var keys = rotate ? List.of(trustKey(RELEASE, "release", "VERIFY_ONLY"), trustKey(ROTATED, "rotated", "ACTIVE"))
                : List.of(trustKey(RELEASE, "release", "ACTIVE"));
        var bundle = java.util.Map.<String,Object>of("contractVersion", "tc-ota-trust-bundle/v1", "trustDomain", DOMAIN, "bundleVersion", version, "keys", keys);
        var signature = java.security.Signature.getInstance("Ed25519"); signature.initSign(ROOT.getPrivate());
        signature.update("thingslink-ota-trust-bundle-v1\0".getBytes(StandardCharsets.UTF_8)); signature.update(CANONICAL.writeObject(bundle));
        return CANONICAL.writeObject(java.util.Map.of("expectedRevision", revision, "bundle", bundle, "signature", b64(signature.sign())));
    }
    /** 不可变公钥身份和有效期。 */
    private static java.util.Map<String,Object> trustKey(java.security.KeyPair pair, String version, String state) {
        return java.util.Map.of("keyVersion", version, "signatureProfile", "TC_OTA_ED25519_V1", "spki", b64(pair.getPublic().getEncoded()),
                "fingerprint", sha(pair.getPublic().getEncoded()), "state", state, "notBefore", 0L, "notAfter", pair == RELEASE ? releaseNotAfter : 253402300799L);
    }
    /** 物理对象身份仍存在，不把签名拒绝误作对象删除授权。 */
    private static void assertObjectExists(com.things.link.ota.domain.OtaUploadSession upload) throws Exception {
        try (MinioClient admin = admin()) {
            assertThat(admin.statObject(io.minio.StatObjectArgs.builder().bucket(BUCKET).object(upload.objectKey())
                    .versionId(upload.versionId()).build()).size()).isEqualTo(upload.expectedLength());
        }
    }
    /** 异步/服务调用都显式绑定本例的真实scope。 */
    private static <T> T scoped(Fixture f, java.util.function.Supplier<T> work) {
        return com.things.link.support.tenant.ScopedTenantWork.call(new com.things.link.shared.tenant.TenantScope(
                f.tenantId(), f.projectId(), f.accountId()), work);
    }
    /** 测试专属公私钥。 */
    private static java.security.KeyPair keyPair() {
        try { return java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    /** 标准Base64。 */ private static String b64(byte[] bytes) { return java.util.Base64.getEncoder().encodeToString(bytes); }
    /** 完整字节SHA256。 */ private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    /** 仅测试显式装配signer，无生产默认或配置成功开关。 */
    @org.springframework.boot.test.context.TestConfiguration
    static class SigningConfiguration {
        /** 只由本测试Import启用，生产扫描不提供本地私钥实现。 */
        @org.springframework.context.annotation.Bean TestSigner testSigner() { return new TestSigner(); }
    }
    /** 真实JCA测试签名器，回执由生产协调器验证。 */
    static class TestSigner implements com.things.link.ota.application.OtaControlledReleaseSigner {
        /** 实际签名调用次数，证明UNKNOWN和SIGNED恢复不重发。 */
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        /** 已签名未返回窗口的受控资格变更，不在签名事务内伪造状态。 */
        volatile Runnable beforeReturn = () -> { };
        /** 模拟供应商调用结果未知，不能解释为未执行。 */
        volatile boolean unknown;
        /** 注入实际签名字节损坏，以验证生产验签拒绝。 */
        volatile boolean corrupt;
        /** 使用测试私钥签真实域分离输入，再施加本例故障；不替代供应商物理取消验收。 */
        @Override public com.things.link.ota.application.OtaReleaseSigner.Response sign(
                com.things.link.ota.application.OtaReleaseSigner.Request request,
                com.things.link.ota.application.OtaSigningControl control) {
            calls.incrementAndGet();
            control.check();
            if (unknown) throw new IllegalStateException("测试供应商结果未知");
            try {
                var signature = java.security.Signature.getInstance("Ed25519"); signature.initSign(RELEASE.getPrivate());
                signature.update(request.signingInput()); byte[] signed = signature.sign();
                if (corrupt) signed[0] ^= 1;
                beforeReturn.run();
                return new com.things.link.ota.application.OtaReleaseSigner.Response(request.requestId(), request.keyVersion(),
                        request.profile(), RELEASE.getPublic().getEncoded(), signed, "test-receipt-" + request.requestId());
            } catch (RuntimeException failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException("测试签名失败", failure); }
        }
    }
    /** 本例精确资源，不携带其他项目事实。
     * @param fixture 本例真实成员和项目
     * @param firmware 通过HTTP创建的固件身份
     * @param upload 已通过真实存储验真的上传
     * @param path 发布尝试HTTP集合路径
     * @param body 完整严格发布请求
     */
    private record Prepared(Fixture fixture, UUID firmware, com.things.link.ota.domain.OtaUploadSession upload,
                            String path, byte[] body) { }


    /** 完整HTTP/1.1往返预算，客户端关闭后不留连接线程。 */
    private HttpResponse<String> send(Fixture f, String method, String path, String key, byte[] body, boolean binary)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(45));
        if (f != null) builder.header("Authorization", "Bearer " + tokens.issue(
                new AuthenticatedPrincipal(f.accountId(), f.tenantId(), f.projectId())).value());
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", binary ? "application/octet-stream" : "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    /** 冻结真实长度和摘要。 */
    private static byte[] declaration(byte[] content) throws Exception {
        return ("{\"expectedLength\":" + content.length + ",\"expectedSha256\":\""
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }
    /** 唯一请求键。 */ private static String key() { return UUID.randomUUID().toString(); }
    /** 明确HTTP状态；响应可能含bearer地址，失败诊断不能打印原文。 */
    private static JsonNode ok(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as("HTTP状态；可能含签名地址的响应正文不进入诊断").isEqualTo(status);
        return JSON.readTree(response.body());
    }
    /** HTTP状态和领域码均须匹配。 */
    private static void error(HttpResponse<String> response, int status, int code) {
        assertThat(ok(response, status).path("code").asInt()).isEqualTo(code);
    }
    /** 只连接独占测试存储。 */
    private static String endpoint() { return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000); }
    /** 管理端只用于前提和物理证据观察。 */
    private static MinioClient admin() { return MinioClient.builder().endpoint(endpoint()).credentials(ACCESS, SECRET).build(); }
    /** 测试全部结束后销毁自有容器。 */ @AfterAll static void stopStorage() { MINIO.stop(); }
    /** 新OTA引用必须先清理，再删除本例模型与项目；不修改触发器或禁用外键。 */
    @AfterEach
    void clearOwnedFacts() throws Exception {
        try (MinioClient admin = admin()) {
            for (var item : admin.listObjects(ListObjectsArgs.builder().bucket(BUCKET).recursive(true).includeVersions(true).build())) {
                var version = item.get();
                admin.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(version.objectName()).versionId(version.versionId()).build());
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
    }

    /** 独立身份骨架与已发布类型/不可变模型，owner只准备夹具。 */
    private Fixture seed(ProjectRole role) {
        Fixture fixture = CONFIGURED;
        fixtures.add(fixture);
        JdbcTemplate owner = owner();
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'OTA测试租户')", fixture.tenantId());
        for (UUID account : List.of(fixture.accountId(), fixture.ownerId())) {
            owner.update("INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at) VALUES (?,?,'{noop}unused','OTA测试',now())",
                    account, account + "@example.invalid");
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
                """, fixture.typeId(), fixture.tenantId(), fixture.projectId(), "type_" + fixture.typeId(), "product_" + fixture.typeId());
        owner.update("""
                INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                    version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                VALUES (?,?,?,?,'1.0.0',1,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1',
                    '{"properties":{},"events":{},"commands":{}}'::jsonb,?,'PG_JSONB_TEXT_V1_SHA256')
                """, fixture.modelId(), fixture.tenantId(), fixture.projectId(), fixture.typeId(), "a".repeat(64));
        return fixture;
    }

    /** owner连接仅用于准备/清理与观察，不走生产读取断言。 */
    private static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
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

    /** 每例全部归属，确保失败后也能精确清理。
     * @param tenantId 本例租户
     * @param projectId 本例项目
     * @param accountId 被测请求账号
     * @param ownerId 保留项目所有者的独立账号
     * @param typeId 发布类型
     * @param modelId 不可变模型版本
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID ownerId, UUID typeId, UUID modelId) { }
}
