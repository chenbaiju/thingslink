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

/** 真实PG、HTTP与MinIO证明退役撤销保留发布责任，软终态不替代物理清理。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(OtaFirmwareLifecycleHttpIntegrationTests.SigningConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OtaFirmwareLifecycleHttpIntegrationTests extends AbstractIntegrationTest {
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
    private static final String ACCESS = "firmware-lifecycle-http-test", SECRET = "firmware-lifecycle-http-secret";
    /** 本测试唯一桶，不触碰开发存储。 */
    private static final String BUCKET = "firmware-lifecycle-http-" + UUID.randomUUID();
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
        registry.add("things-link.storage.internal-endpoint", OtaFirmwareLifecycleHttpIntegrationTests::endpoint);
        registry.add("things-link.storage.external-endpoint", OtaFirmwareLifecycleHttpIntegrationTests::endpoint);
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

    /** 真实代理服务补证被前置REST计量守卫截断的领域非ACTIVE边界。 */
    @Autowired private com.things.link.ota.application.OtaFirmwareLifecycleService lifecycleService;

    /** 仅对目标生命周期审计注入失败，真实INSERT仍参与同一事务。 */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.things.link.support.audit.AuditLogService audit;

    /** 审计已写后失败必须回滚状态和审计；重试仅留一条真实操作者事实。 */
    @Test
    void rollsBackLifecycleAndAuditTogetherWhenAuditCompletionFails() throws Exception {
        Prepared prepared = published();
        String reason = "审计与状态共同提交";
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("测试生命周期审计写入后失败");
        }).when(audit).record(org.mockito.ArgumentMatchers.argThat(entry ->
                entry != null && "ota.firmware.deprecated".equals(entry.action())
                        && prepared.firmware().equals(entry.targetId())));
        error(send(prepared.fixture(), "POST", firmwarePath(prepared) + "/deprecations", key(),
                transition("2", reason), false), 500, 90000);
        JsonNode state = ok(send(prepared.fixture(), "GET", firmwarePath(prepared) + "/lifecycle", null, null, false), 200);
        assertThat(state.path("status").asText()).isEqualTo("READY");
        assertThat(state.path("revision").asText()).isEqualTo("2");
        assertThat(state.path("deprecation").isNull()).isTrue();
        assertThat(state.path("revocation").isNull()).isTrue();
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE target_id=? AND action='ota.firmware.deprecated'",
                Long.class, prepared.firmware())).isZero();
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware_upload_session WHERE id=?",
                String.class, prepared.upload().id())).isEqualTo("ADOPTED");
        assertObjectExists(prepared.upload());
        org.mockito.Mockito.doCallRealMethod().when(audit).record(org.mockito.ArgumentMatchers.any());
        ok(send(prepared.fixture(), "POST", firmwarePath(prepared) + "/deprecations", key(), transition("2", reason), false), 200);
        var records = owner().queryForList("""
                SELECT actor_account_id, details->>'reason' AS reason, details->>'revision' AS revision,
                       details->>'status' AS status
                FROM sys_audit_log WHERE target_id=? AND action='ota.firmware.deprecated'
                """, prepared.firmware());
        assertThat(records).hasSize(1);
        assertThat(records.getFirst()).containsEntry("actor_account_id", prepared.fixture().accountId())
                .containsEntry("reason", reason).containsEntry("revision", "3").containsEntry("status", "DEPRECATED");
    }

    /** 三条合法状态路径都保留原发布关系、固定对象与首次操作者记录。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"deprecations", "revocations", "deprecations/revocations"})
    void recordsLegalTransitionsWithoutDeletingAdoptedObjects(String route) throws Exception {
        Prepared prepared = published();
        var initial = ok(send(prepared.fixture(), "GET", firmwarePath(prepared) + "/lifecycle", null, null, false), 200);
        assertThat(initial.path("status").asText()).isEqualTo("READY");
        assertThat(initial.path("revision").asText()).isEqualTo("2");
        assertThat(initial.path("deprecation").isNull()).isTrue();
        assertThat(initial.path("revocation").isNull()).isTrue();
        JsonNode deprecated = null;
        String revision = "2";
        for (String action : route.split("/")) {
            String reason = "管理员明确" + action;
            var response = send(prepared.fixture(), "POST", firmwarePath(prepared) + "/" + action, key(), transition(revision, reason), false);
            JsonNode state = ok(response, 200);
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            revision = Long.toString(Long.parseLong(revision) + 1);
            assertThat(state.path("revision").asText()).isEqualTo(revision);
            assertThat(state.propertyNames()).containsExactlyInAnyOrder("firmwareId", "status", "revision", "deprecation", "revocation");
            String record = action.equals("deprecations") ? "deprecation" : "revocation";
            assertThat(state.path("status").asText()).isEqualTo(action.equals("deprecations") ? "DEPRECATED" : "REVOKED");
            JsonNode metadata = state.path(record);
            assertThat(metadata.propertyNames()).containsExactlyInAnyOrder("reason", "actorId", "occurredAt");
            assertThat(metadata.path("reason").asText()).isEqualTo(reason);
            assertThat(metadata.path("actorId").asText()).isEqualTo(prepared.fixture().accountId().toString());
            assertThat(java.time.Instant.parse(metadata.path("occurredAt").asText()).getNano() % 1000).isZero();
            if (action.equals("deprecations")) deprecated = metadata;
            if (action.equals("revocations")) assertThat(state.path("deprecation"))
                    .isEqualTo(deprecated == null ? initial.path("deprecation") : deprecated);
            var read = send(prepared.fixture(), "GET", firmwarePath(prepared) + "/lifecycle", null, null, false);
            assertThat(ok(read, 200)).isEqualTo(state);
            assertThat(read.headers().firstValue("Cache-Control")).contains("no-store");
        }
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware_upload_session WHERE id=?", String.class,
                prepared.upload().id())).isEqualTo("ADOPTED");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_firmware_release WHERE firmware_id=?", Long.class,
                prepared.firmware())).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware_publication WHERE firmware_id=?", String.class,
                prepared.firmware())).isEqualTo("COMMITTED");
        assertObjectExists(prepared.upload());
        error(send(prepared.fixture(), "GET", firmwarePath(prepared) + "/release", null, null, false), 409, 70022);
        error(send(prepared.fixture(), "POST", firmwarePath(prepared) + "/release/downloads", key(), null, false), 409, 70022);
    }

    /** 完成墓碑、陈旧CAS与封闭终态不重复修改首次记录。 */
    @Test
    void preservesFirstLifecycleFactAcrossReplayStaleRevisionAndTerminalAttempts() throws Exception {
        Prepared prepared = published();
        String requestKey = key();
        byte[] body = transition("2", "停用旧固件");
        JsonNode first = ok(send(prepared.fixture(), "POST", firmwarePath(prepared) + "/deprecations", requestKey, body, false), 200);
        error(send(prepared.fixture(), "POST", firmwarePath(prepared) + "/deprecations", requestKey, body, false), 409, 10014);
        error(send(prepared.fixture(), "POST", firmwarePath(prepared) + "/revocations", key(), transition("2", "陈旧修订"), false), 409, 70026);
        error(send(prepared.fixture(), "POST", firmwarePath(prepared) + "/deprecations", key(), transition("3", "重复退役"), false), 409, 70026);
        assertThat(ok(send(prepared.fixture(), "GET", firmwarePath(prepared) + "/lifecycle", null, null, false), 200)).isEqualTo(first);
        JsonNode terminal = ok(send(prepared.fixture(), "POST", firmwarePath(prepared) + "/revocations", key(), transition("3", "撤销漏洞固件"), false), 200);
        for (String action : List.of("deprecations", "revocations")) {
            error(send(prepared.fixture(), "POST", firmwarePath(prepared) + "/" + action, key(), transition("4", "不允许重开"), false), 409, 70026);
        }
        assertThat(ok(send(prepared.fixture(), "GET", firmwarePath(prepared) + "/lifecycle", null, null, false), 200)).isEqualTo(terminal);
    }

    /** 两个真实HTTP请求共享同一修订，仅一个转移能提交，另一请求明确CAS冲突。 */
    @Test
    void permitsOnlyOneConcurrentTransitionAtTheSameRevision() throws Exception {
        Prepared prepared = published();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var deprecate = executor.submit(() -> { start.await(); return send(prepared.fixture(), "POST",
                    firmwarePath(prepared) + "/deprecations", key(), transition("2", "并发退役"), false); });
            var revoke = executor.submit(() -> { start.await(); return send(prepared.fixture(), "POST",
                    firmwarePath(prepared) + "/revocations", key(), transition("2", "并发撤销"), false); });
            start.countDown();
            var responses = List.of(deprecate.get(45, java.util.concurrent.TimeUnit.SECONDS),
                    revoke.get(45, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200, 409);
            error(responses.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow(), 409, 70026);
        }
        assertThat(ok(send(prepared.fixture(), "GET", firmwarePath(prepared) + "/lifecycle", null, null, false), 200)
                .path("revision").asText()).isEqualTo("3");
        assertObjectExists(prepared.upload());
    }

    /** 缺认证、非管理成员、跨项目及未发布草稿都不能获取新状态转移权限。 */
    @Test
    void rejectsUnauthorizedScopeAndDraftTransition() throws Exception {
        Prepared prepared = prepare();
        assertThat(send(null, "GET", firmwarePath(prepared) + "/lifecycle", null, null, false).statusCode()).isEqualTo(401);
        error(send(prepared.fixture(), "POST", firmwarePath(prepared) + "/revocations", key(), transition("0", "草稿不能撤销"), false), 409, 70026);
        String foreign = firmwarePath(prepared).replace(prepared.fixture().projectId().toString(), Uuid7.generate().toString());
        error(send(prepared.fixture(), "GET", foreign + "/lifecycle", null, null, false), 404, 50001);
        owner().update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",
                prepared.fixture().projectId(), prepared.fixture().accountId());
        error(send(prepared.fixture(), "GET", firmwarePath(prepared) + "/lifecycle", null, null, false), 403, 70027);
        error(send(prepared.fixture(), "POST", firmwarePath(prepared) + "/deprecations", key(), transition("0", "无权限"), false), 403, 70027);
        Fixture f = prepared.fixture();
        Fixture actualOwner = new Fixture(f.tenantId(), f.projectId(), f.ownerId(), f.ownerId(), f.typeId(), f.modelId());
        ok(send(actualOwner, "GET", firmwarePath(prepared) + "/lifecycle", null, null, false), 200);
        owner().update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", f.projectId());
        error(send(actualOwner, "GET", firmwarePath(prepared) + "/lifecycle", null, null, false), 403, 50017);
        // ARCHIVED不保存REST用量事实，公共WRITE门禁先以429关闭；不能冒充领域已执行。
        error(send(actualOwner, "POST", firmwarePath(prepared) + "/revocations", key(), transition("0", "只读项目"), false), 429, 10029);
        assertThatThrownBy(() -> scoped(actualOwner, () -> lifecycleService.revoke(f.projectId(), prepared.firmware(),
                key(), "0", "只读项目")))
                .isInstanceOfSatisfying(com.things.link.shared.error.BusinessException.class,
                        failure -> assertThat(failure.errorCode().code()).isEqualTo(50017));
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware WHERE id=?", String.class, prepared.firmware()))
                .isEqualTo("DRAFT");
        assertThat(owner().queryForObject("SELECT revision FROM ota_firmware WHERE id=?", Long.class, prepared.firmware())).isZero();
    }

    /** 下载签发成功后合法生命周期转移，返回前二次事务仍拒绝短URL。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"deprecations", "revocations"})
    void deniesPresignedTicketAfterRealLifecycleTransition(String action) throws Exception {
        Prepared prepared = published();
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Object url = invocation.callRealMethod();
            ok(send(prepared.fixture(), "POST", firmwarePath(prepared) + "/" + action, key(), transition("2", "签发窗口状态变化"), false), 200);
            return url;
        }).when(storage).presignGet(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        var response = send(prepared.fixture(), "POST", firmwarePath(prepared) + "/release/downloads", key(), null, false);
        error(response, 409, 70022);
        assertThat(response.body()).doesNotContain("downloadUrl", "X-Amz", prepared.upload().versionId());
        assertObjectExists(prepared.upload());
    }

    /** 真实业务发布入口完成签名和对象采用，不直接写READY夹具。 */
    private Prepared published() throws Exception {
        Prepared prepared = prepare();
        ok(send(prepared.fixture(), "POST", prepared.path(), key(), prepared.body(), false), 202);
        publicationProcessor.process(publications.claim().orElseThrow());
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware WHERE id=?", String.class, prepared.firmware())).isEqualTo("READY");
        return prepared;
    }

    /** 规范生命周期请求不携带客户端操作者或时间。 */
    private static byte[] transition(String revision, String reason) {
        return CANONICAL.writeObject(java.util.Map.of("expectedRevision", revision, "reason", reason));
    }

    /** 当前夹具固件根路径。 */
    private static String firmwarePath(Prepared prepared) { return base(prepared.fixture()) + "/" + prepared.firmware(); }

    /** 项目真实清理先转交ADOPTED责任，worker物理删固定版本后才能删除release与尝试。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"deprecations", "revocations"})
    void projectCleanupReclaimsTerminalFirmwareVersionBeforeDeletingReleaseFacts(String action) throws Exception {
        Prepared prepared = published();
        ok(send(prepared.fixture(), "POST", firmwarePath(prepared) + "/" + action, key(), transition("2", "清理前软终态"), false), 200);
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware WHERE id=?", String.class, prepared.firmware())).isEqualTo(action.equals("deprecations") ? "DEPRECATED" : "REVOKED");
        UUID lease = Uuid7.generate();
        owner().update("""
                UPDATE sys_project SET status='PURGING',lifecycle_generation=1,deleted_at=now()-interval '31 days',cleanup_stage='OTA',
                    cleanup_started_at=now(),cleanup_next_attempt_at=now(),cleanup_lease_token=?,cleanup_lease_until=now()+interval '120 seconds'
                WHERE id=?
                """, lease, prepared.fixture().projectId());
        var claim = new com.things.link.project.application.ProjectCleanupClaim(prepared.fixture().tenantId(), prepared.fixture().projectId(),
                1, "OTA", lease, java.time.Instant.now().plusSeconds(120), false);
        var result = cleanupBatch(prepared.fixture(), claim);
        assertThat(result.blockedReason()).isEqualTo("OTA_UPLOAD_CANCELLATION_REQUESTED");
        assertThat(result.deletedRows()).isZero();
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware_upload_session WHERE id=?", String.class, prepared.upload().id()))
                .isEqualTo("CLEANUP_PENDING");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_firmware_release WHERE firmware_id=?", Long.class, prepared.firmware())).isEqualTo(1);
        assertObjectExists(prepared.upload());
        var recovery = uploads.claimRecovery().orElseThrow();
        assertThat(recovery.id()).isEqualTo(prepared.upload().id());
        scoped(prepared.fixture(), () -> { uploadRecovery.recover(recovery); return true; });
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware_upload_session WHERE id=?", String.class, prepared.upload().id())).isEqualTo("CLEANED");
        try (MinioClient admin = admin()) {
            assertThat(admin.listObjects(ListObjectsArgs.builder().bucket(BUCKET).prefix(prepared.upload().objectKey())
                    .includeVersions(true).recursive(true).build()).iterator().hasNext()).isFalse();
        }
        boolean done = false;
        for (int i = 0; i < 16; i++) {
            var batch = cleanupBatch(prepared.fixture(), claim);
            assertThat(batch.deletedRows()).isBetween(0, 500);
            assertThat(batch.blockedReason()).isNull();
            if (batch.complete()) { done = true; break; }
        }
        assertThat(done).isTrue();
        for (String table : List.of("ota_firmware_release", "ota_firmware_publication", "ota_firmware_upload_session",
                "ota_trust_bundle", "ota_trust_domain", "ota_firmware_creation_request", "ota_firmware")) {
            assertThat(owner().queryForObject("SELECT count(*) FROM " + table + " WHERE project_id=?", Long.class,
                    prepared.fixture().projectId())).as(table).isZero();
        }
    }
    /** 本域真实受限批次仍由普通APP事务调用，不以owner执行清理函数。 */
    private com.things.link.project.application.ProjectCleanupBatchResult cleanupBatch(Fixture fixture,
            com.things.link.project.application.ProjectCleanupClaim claim) {
        return scoped(fixture, () -> new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status ->
                new com.things.link.ota.infrastructure.persistence.JdbcOtaProjectCleanupRepository(jdbc).clean(claim)));
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
    /** 明确HTTP状态并保留响应首因。 */
    private static JsonNode ok(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
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
