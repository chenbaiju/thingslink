package com.things.link.bootstrap.ota;

import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.id.Uuid7;
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

/** 真实PG、Kafka、HTTP与MinIO验证认证报告持久化及当前四方资格，不冒充设备派发。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(OtaDeviceEligibilityHttpIntegrationTests.SigningConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OtaDeviceEligibilityHttpIntegrationTests extends com.things.link.testing.AbstractKafkaIntegrationTest {
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
    private static final String ACCESS = "device-eligibility-http-test", SECRET = "device-eligibility-http-secret";
    /** 本测试唯一桶，不触碰开发存储。 */
    private static final String BUCKET = "device-eligibility-http-" + UUID.randomUUID();
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
        registry.add("things-link.storage.internal-endpoint", OtaDeviceEligibilityHttpIntegrationTests::endpoint);
        registry.add("things-link.storage.external-endpoint", OtaDeviceEligibilityHttpIntegrationTests::endpoint);
        registry.add("things-link.storage.access-key", () -> ACCESS);
        registry.add("things-link.storage.secret-key", () -> SECRET);
        registry.add("things-link.ota.storage.bucket", () -> BUCKET);
        registry.add("things-link.ota.type-baselines-json", OtaDeviceEligibilityHttpIntegrationTests::baselineSource);
        registry.add("spring.kafka.listener.auto-startup", () -> false);
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

    /** 报告接纳必须使用真实数据面事务代理。 */
    @Autowired private com.things.link.ota.application.OtaDeviceReportIngestionService reportIngestion;
    /** 当前配置的受控基线登记服务。 */
    @Autowired private com.things.link.ota.application.OtaTypeBaselineService baselines;
    /** 生产Kafka发送器使用实际信封序列化。 */
    @Autowired private org.springframework.kafka.core.KafkaTemplate<String,Object> kafka;
    /** 只启动raw生产监听器，不启动本专项无关的消费链。 */
    @Autowired private org.springframework.kafka.config.KafkaListenerEndpointRegistry listeners;

    /** 首次事实保留Broker时间；同序规范重放不续鲜，低序或异摘要零写。 */
    @Test
    void preservesFreshnessAcrossCanonicalReplayAndRejectsConflictingSequence() throws Exception {
        Fixture fixture = seed(ProjectRole.ADMIN); UUID device = device(fixture);
        var identity = identity(fixture, device, 1); byte[] body = report(fixture, 2, 0, "a".repeat(64));
        var received = java.time.Instant.now().minusSeconds(20).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        assertThat(reportIngestion.accept(identity, body, received).name()).isEqualTo("ACCEPTED");
        var before = observed(device);
        assertThat(reportIngestion.accept(identity, body, java.time.Instant.now()).name()).isEqualTo("REPLAY");
        assertThat(observed(device)).isEqualTo(before);
        assertThatThrownBy(() -> reportIngestion.accept(identity, report(fixture, 1, 0, "a".repeat(64)), java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reportIngestion.accept(identity, report(fixture, 2, 1, "a".repeat(64)), java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(observed(device)).isEqualTo(before);
        assertThat(before.get("broker_received_at")).isEqualTo(java.sql.Timestamp.from(received));
    }

    /** 换凭据可从序号一重启，但已观察安全下限不能降低，也不能接受旧连接代际。 */
    @Test
    void preservesCommittedCounterFloorAcrossCredentialRotation() throws Exception {
        Fixture fixture = seed(ProjectRole.ADMIN); UUID device = device(fixture);
        reportIngestion.accept(identity(fixture, device, 1), report(fixture, 9, 2, "a".repeat(64)), java.time.Instant.now());
        owner().update("UPDATE dev_device SET credential_version=2 WHERE id=?", device);
        var before = observed(device);
        assertThatThrownBy(() -> reportIngestion.accept(identity(fixture, device, 2), report(fixture, 1, 1, "a".repeat(64)), java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reportIngestion.accept(identity(fixture, device, 1), report(fixture, 10, 2, "a".repeat(64)), java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(observed(device)).isEqualTo(before);
        reportIngestion.accept(identity(fixture, device, 2), report(fixture, 1, 2, "a".repeat(64)), java.time.Instant.now());
        assertThat(observed(device)).containsEntry("credential_version", 2L).containsEntry("report_sequence", 1L)
                .containsEntry("committed_security_version", 2L).containsEntry("revision", 2L);
    }

    /** 不完整来源、错scope/模型与过期积压都不能形成当前事实。 */
    @Test
    void rejectsMissingIdentityWrongScopeModelAndUnfreshBrokerTimeWithoutWrites() throws Exception {
        Fixture fixture = seed(ProjectRole.ADMIN); UUID device = device(fixture);
        var identity = identity(fixture, device, 1); byte[] body = report(fixture, 1, 0, "a".repeat(64));
        assertThatThrownBy(() -> reportIngestion.accept(null, body, java.time.Instant.now())).isInstanceOf(IllegalArgumentException.class);
        var wrong = new com.things.link.shared.message.AuthenticatedDeviceIdentity(fixture.tenantId(), Uuid7.generate(), device, 1);
        assertThatThrownBy(() -> reportIngestion.accept(wrong, body, java.time.Instant.now())).isInstanceOf(IllegalArgumentException.class);
        for (var time : List.of(java.time.Instant.now().minusSeconds(301), java.time.Instant.now().plusSeconds(35))) {
            assertThatThrownBy(() -> reportIngestion.accept(identity, body, time)).isInstanceOf(IllegalArgumentException.class);
        }
        var changed = new java.util.LinkedHashMap<>(CANONICAL.parseObject(body));
        changed.put("thingModelSchemaDigest", "b".repeat(64));
        assertThatThrownBy(() -> reportIngestion.accept(identity, CANONICAL.writeObject(changed), java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        var injectedMap = new java.util.LinkedHashMap<>(CANONICAL.parseObject(body));
        injectedMap.put("credentialVersion", 1L); byte[] injected = CANONICAL.writeObject(injectedMap);
        assertThatThrownBy(() -> reportIngestion.accept(identity, injected, java.time.Instant.now())).isInstanceOf(IllegalArgumentException.class);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_device_report WHERE device_id=?", Long.class, device)).isZero();
    }

    /** 实际Kafka序列化和生产raw监听器最终写PG，不以手动调用consumer替代消息链。 */
    @Test
    void persistsAuthenticatedRawKafkaReportThroughProductionListener() throws Exception {
        Fixture fixture = seed(ProjectRole.ADMIN); UUID device = device(fixture);
        String topic = com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.RAW_UPLINK_TOPIC;
        try (var admin = org.apache.kafka.clients.admin.Admin.create(java.util.Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            var existing = admin.listTopics().names().get(10, java.util.concurrent.TimeUnit.SECONDS);
            var missing = new ArrayList<org.apache.kafka.clients.admin.NewTopic>();
            for (String name : List.of(topic, com.things.link.ingestion.infrastructure.UplinkKafkaConfiguration.DEAD_LETTER_TOPIC)) {
                if (!existing.contains(name)) missing.add(new org.apache.kafka.clients.admin.NewTopic(name, 3, (short)1));
            }
            if (!missing.isEmpty()) admin.createTopics(missing).all().get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        var container = listeners.getListenerContainers().stream()
                .filter(value -> "things-link-ingestion-raw".equals(value.getGroupId())).findFirst().orElseThrow();
        assertThat(container.isRunning()).isFalse();
        container.start();
        try {
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).until(() ->
                    container.getAssignedPartitions() != null && !container.getAssignedPartitions().isEmpty());
            byte[] payload = report(fixture, 1, 0, "a".repeat(64));
            var received = java.time.Instant.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var raw = new com.things.link.shared.message.RawUplinkMessage(fixture.tenantId(), fixture.projectId(), device,
                    "tc/v1/ota_test/device_test/up/ota/report", payload, 1, false, "test-client", received,
                    UUID.randomUUID().toString(), identity(fixture, device, 1));
            kafka.send(topic, device.toString(), raw).get(10, java.util.concurrent.TimeUnit.SECONDS);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(owner().queryForObject("SELECT count(*) FROM ota_device_report WHERE device_id=?", Long.class, device)).isEqualTo(1L));
            assertThat(observed(device)).containsEntry("credential_version", 1L).containsEntry("revision", 1L)
                    .containsEntry("report_hash", sha(payload)).containsEntry("broker_received_at", java.sql.Timestamp.from(received));
        } finally {
            var stopped = new java.util.concurrent.CountDownLatch(1); container.stop(stopped::countDown);
            assertThat(stopped.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    /** HTTP必须独立验权限；无报告返回明确null，不能伪装允许或生成下载URL。 */
    @Test
    void enforcesManagementScopeAndReturnsExplicitMissingReport() throws Exception {
        Fixture fixture = seed(ProjectRole.ADMIN); UUID device = device(fixture); UUID firmware = Uuid7.generate();
        var response = send(fixture, "GET", eligibility(fixture, device, firmware), null, null, false);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        var json = ok(response, 200); assertThat(json.path("eligible").asBoolean()).isFalse();
        assertThat(json.path("reason").asText()).isEqualTo("REPORT_MISSING");
        assertThat(json.has("reportRevision")).isTrue(); assertThat(json.path("reportRevision").isNull()).isTrue();
        assertThat(json.size()).isEqualTo(4);
        owner().update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.accountId());
        error(send(fixture,"GET",eligibility(fixture, device, firmware),null,null,false),403,70033);
        owner().update("UPDATE sys_project_member SET role='ADMIN' WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.accountId());
        var anonymous = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + eligibility(fixture,device,firmware))).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        owner().update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.accountId());
        // 当前项目成员移除后，TenantScopeFilter先使项目令牌失效，不进入领域资源查询。
        error(send(fixture,"GET",eligibility(fixture, device, firmware),null,null,false),401,20020);
    }

    /** 真实固定版本发布与受控基线交集成功，随后撤销必须即时不合格且对象仍被采用。 */
    @Test
    void qualifiesPublishedFirmwareAndRejectsCurrentRevocation() throws Exception {
        Prepared prepared = published(); Fixture fixture = prepared.fixture(); UUID device = initialized(prepared);
        reportIngestion.accept(identity(fixture,device,1), report(fixture,1,0,bundleHash(fixture)), java.time.Instant.now());
        assertReason(fixture,device,prepared.firmware(),"ELIGIBLE");
        ok(send(fixture,"POST",base(fixture)+"/"+prepared.firmware()+"/revocations",key(),
                CANONICAL.writeObject(java.util.Map.of("expectedRevision","2","reason","资格必须观察撤销")),false),200);
        assertReason(fixture,device,prepared.firmware(),"FIRMWARE_UNAVAILABLE");
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware_upload_session WHERE id=?",String.class,prepared.upload().id())).isEqualTo("ADOPTED");
        assertObjectExists(prepared.upload());
    }

    /** 当前包更新必须重新确认报告摘要，旧确认不会被平台代填；更新报告后恢复交集。 */
    @Test
    void requiresExactCurrentTrustAcknowledgementAfterBundleRotation() throws Exception {
        Prepared prepared = published(); Fixture fixture = prepared.fixture(); UUID device = initialized(prepared);
        reportIngestion.accept(identity(fixture,device,1),report(fixture,1,0,bundleHash(fixture)),java.time.Instant.now());
        ok(send(fixture,"POST","/api/v1/projects/"+fixture.projectId()+"/ota/trust-domains/"+DOMAIN+"/bundles",
                key(),trustEnvelope("1",2,true),false),200);
        assertReason(fixture,device,prepared.firmware(),"TRUST_NOT_ACKNOWLEDGED");
        var updated = new java.util.LinkedHashMap<>(CANONICAL.parseObject(report(fixture,2,0,bundleHash(fixture))));
        updated.put("trustBundleVersion",2L);
        reportIngestion.accept(identity(fixture,device,1),CANONICAL.writeObject(updated),java.time.Instant.now());
        assertReason(fixture,device,prepared.firmware(),"ELIGIBLE");
        owner().update("UPDATE dev_device SET credential_version=2 WHERE id=?",device);
        assertReason(fixture,device,prepared.firmware(),"IDENTITY_CHANGED");
    }

    /** 锁等待跨越新鲜度或凭据截止后必须复验，不能以事务开始时的资格放行。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"REPORT", "CREDENTIAL"})
    void rechecksFreshnessAfterWaitingForFirmwareLock(String expiry) throws Exception {
        Prepared prepared = published(); Fixture fixture = prepared.fixture(); UUID device = initialized(prepared);
        var received = java.time.Instant.now().minusSeconds("REPORT".equals(expiry) ? 297 : 0);
        var cutoff = "REPORT".equals(expiry) ? received.plusSeconds(300) : received.plusSeconds(3);
        if ("CREDENTIAL".equals(expiry)) owner().update("UPDATE dev_credential SET expires_at=? WHERE device_id=?",java.sql.Timestamp.from(cutoff),device);
        reportIngestion.accept(identity(fixture,device,1),report(fixture,1,0,bundleHash(fixture)),received);
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())) {
            connection.setAutoCommit(false);
            try (var statement=connection.prepareStatement("SELECT id FROM ota_firmware WHERE id=? FOR UPDATE")) {
                statement.setObject(1,prepared.firmware()); statement.executeQuery().close();
            }
            var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return send(fixture,"GET",eligibility(fixture,device,prepared.firmware()),null,null,false); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            });
            try {
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> owner().queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%ota_firmware%'",Long.class)>0);
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(4)).until(() -> java.time.Instant.now().isAfter(cutoff));
            } finally { connection.rollback(); }
            assertThat(ok(future.get(8,java.util.concurrent.TimeUnit.SECONDS),200).path("reason").asText()).isEqualTo("REPORT".equals(expiry) ? "REPORT_STALE" : "IDENTITY_CHANGED");
        }
    }

    /** 声明来源只由运维配置提供，测试字段对应真实发布目标，不构造外部硬件证明。 */
    private static String baselineSource() {
        var value = new java.util.LinkedHashMap<String,Object>();
        value.put("contractVersion","tc-ota-type-baseline/v1"); value.put("tenantId",CONFIGURED.tenantId().toString());
        value.put("projectId",CONFIGURED.projectId().toString()); value.put("deviceTypeId",CONFIGURED.typeId().toString());
        value.put("productKey","product_"+CONFIGURED.typeId()); value.put("baselineVersion",1L);
        value.put("trustDomain",DOMAIN); value.put("rootFingerprint",sha(ROOT.getPublic().getEncoded()));
        value.put("hardware",java.util.Map.of("model","board-v1","boardRevisionMin",0L,"boardRevisionMax",1L));
        value.put("bootloader",java.util.Map.of("minimumVersion","1.0.0","maximumVersion","2.0.0"));
        value.put("signatureProfiles",List.of("TC_OTA_ED25519_V1")); value.put("maximumArtifactBytes",67108864L);
        value.put("availableRamBytes",1024L); value.put("availableFlashBytes",67108864L);
        value.put("supportsAbSlots",true); value.put("supportsRangeDownload",true); value.put("supportsResumeDownload",true);
        value.put("protectedSecurityCounterBits",53L); value.put("compressionAlgorithms",List.of("NONE"));
        value.put("deltaModes",List.of("NONE")); value.put("propertyProfile","TC_PROPERTY_COMPOSITE_V1");
        value.put("evidenceReference","test-fixture-only");
        return new String(CANONICAL.writeObject(java.util.Map.of("baselines",List.of(value))),StandardCharsets.UTF_8);
    }

    /** 完整合法报告正文只含运行声明，认证身份单独传入服务。 */
    private static byte[] report(Fixture fixture,long sequence,long committed,String bundleHash) {
        var value = new java.util.LinkedHashMap<String,Object>();
        value.put("contractVersion","tc-ota-device-report/v1"); value.put("reportSequence",sequence);
        value.put("hardware",java.util.Map.of("model","board-v1","boardRevision",0L));
        value.put("bootloaderVersion","1.0.0"); value.put("currentFirmwareVersion","factory-v1");
        value.put("currentFirmwareSha256","b".repeat(64)); value.put("currentSecurityVersion",committed);
        value.put("committedSecurityVersion",committed); value.put("thingModelVersionId",fixture.modelId().toString());
        value.put("thingModelSchemaDigestAlgorithm","PG_JSONB_TEXT_V1_SHA256"); value.put("thingModelSchemaDigest","a".repeat(64));
        value.put("propertyProfile","TC_PROPERTY_COMPOSITE_V1"); value.put("trustDomain",DOMAIN);
        value.put("rootFingerprint",sha(ROOT.getPublic().getEncoded())); value.put("trustBundleVersion",1L);
        value.put("trustBundleSha256",bundleHash); value.put("signatureProfiles",List.of("TC_OTA_ED25519_V1"));
        value.put("maximumArtifactBytes",67108864L); value.put("availableRamBytes",1024L); value.put("availableFlashBytes",67108864L);
        value.put("supportsAbSlots",true); value.put("supportsRangeDownload",true); value.put("supportsResumeDownload",true);
        value.put("protectedSecurityCounterBits",53L); value.put("compressionAlgorithms",List.of("NONE"));
        value.put("deltaModes",List.of("NONE")); value.put("activeSlot","A"); value.put("bootState","HEALTHY");
        return CANONICAL.writeObject(value);
    }

    /** 真实当前设备与基线，报告仍单独由认证入口接纳。 */
    private UUID initialized(Prepared prepared) {
        Fixture fixture = prepared.fixture(); UUID device = device(fixture);
        scoped(fixture,()->baselines.register(fixture.projectId(),fixture.typeId(),key(),"0")); return device;
    }
    /** 准备当前DIRECT设备绑定，绝不直接写报告。 */
    private static UUID device(Fixture fixture) {
        UUID device = Uuid7.generate();
        owner().update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,thing_model_version_id) VALUES (?,?,?,?,?,'报告测试设备',?)",
                device,fixture.tenantId(),fixture.projectId(),fixture.typeId(),"device_"+device,fixture.modelId());
        owner().update("INSERT INTO dev_credential(id,tenant_id,project_id,device_id,auth_type,credential_hash,display_name) VALUES (?,?,?,?,'ACCESS_TOKEN',?,'专项有效凭据')",
                Uuid7.generate(),fixture.tenantId(),fixture.projectId(),device,sha(device.toString().getBytes(StandardCharsets.UTF_8)));
        return device;
    }
    /** 可信代际来自参数身份，不进入正文。 */
    private static com.things.link.shared.message.AuthenticatedDeviceIdentity identity(Fixture fixture,UUID device,long generation) {
        return new com.things.link.shared.message.AuthenticatedDeviceIdentity(fixture.tenantId(),fixture.projectId(),device,generation);
    }
    /** owner仅观察持久事实，不代替生产接纳或资格权限。 */
    private static java.util.Map<String,Object> observed(UUID device) {
        return owner().queryForMap("SELECT credential_version,report_sequence,revision,committed_security_version,report_hash,broker_received_at,accepted_at FROM ota_device_report WHERE device_id=?",device);
    }
    /** 当前包摘要来自生产服务的受管理范围读取。 */
    private String bundleHash(Fixture fixture) { return scoped(fixture,()->trust.find(fixture.projectId(),DOMAIN).bundleSha256()); }
    /** 唯一管理资格路径不暴露下载签名。 */
    private static String eligibility(Fixture fixture,UUID device,UUID firmware) {
        return "/api/v1/projects/"+fixture.projectId()+"/ota/devices/"+device+"/eligibility?firmwareId="+firmware;
    }
    /** 精确原因与布尔同步，不接受多个宽泛失败结果。 */
    private void assertReason(Fixture fixture,UUID device,UUID firmware,String reason) throws Exception {
        var response=send(fixture,"GET",eligibility(fixture,device,firmware),null,null,false);
        var json=ok(response,200); assertThat(json.path("reason").asText()).isEqualTo(reason);
        assertThat(json.path("eligible").asBoolean()).isEqualTo("ELIGIBLE".equals(reason));
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }


    /** 通过真实上传和签名完成发布。 */
    private Prepared published() throws Exception {
        Prepared prepared = prepare();
        ok(send(prepared.fixture(), "POST", prepared.path(), key(), prepared.body(), false), 202);
        publicationProcessor.process(publications.claim().orElseThrow());
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware WHERE id=?", String.class, prepared.firmware())).isEqualTo("READY");
        return prepared;
    }

    /** 建立真实发布与上传前置。 */
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
            owner.update("DELETE FROM ota_device_report WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM dev_credential WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM dev_device WHERE project_id = ?", fixture.projectId());
            new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(owner.getDataSource())).executeWithoutResult(status -> {
                owner.update("DELETE FROM ota_type_baseline_version WHERE project_id = ?", fixture.projectId());
                owner.update("DELETE FROM ota_type_baseline WHERE project_id = ?", fixture.projectId());
            });
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
