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

/** 真实PG与管理HTTP验证持久通知授权及迟到回执，不以受控回执替代实际Broker证据。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(OtaNotificationHttpIntegrationTests.SigningConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OtaNotificationHttpIntegrationTests extends com.things.link.testing.AbstractIntegrationTest {
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
    private static final String ACCESS = "notification-http-test", SECRET = "notification-http-secret";
    /** 独占失败端点，绝不向开发EMQX发送测试命令。 */
    private static final com.sun.net.httpserver.HttpServer FAILURE_HTTP = failureEndpoint();
    /** 真实物理请求计数。 */
    private static final java.util.concurrent.atomic.AtomicInteger HTTP_CALLS = new java.util.concurrent.atomic.AtomicInteger();
    private static com.sun.net.httpserver.HttpServer failureEndpoint() {
        try {
            var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes(); HTTP_CALLS.incrementAndGet();
                exchange.sendResponseHeaders(503, -1); exchange.close();
            });
            server.start(); return server;
        } catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    /** 本测试唯一桶，不触碰开发存储。 */
    private static final String BUCKET = "notification-http-" + UUID.randomUUID();
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
        OtaCompletionSourceAssertions.configure(registry);
        registry.add("things-link.storage.internal-endpoint", OtaNotificationHttpIntegrationTests::endpoint);
        registry.add("things-link.storage.external-endpoint", OtaNotificationHttpIntegrationTests::endpoint);
        registry.add("things-link.storage.access-key", () -> ACCESS);
        registry.add("things-link.storage.secret-key", () -> SECRET);
        registry.add("things-link.ota.storage.bucket", () -> BUCKET);
        registry.add("things-link.ota.type-baselines-json", OtaNotificationHttpIntegrationTests::baselineSource);
        registry.add("spring.kafka.listener.auto-startup", () -> false);
        registry.add("things-link.ota.upload.recovery-enabled", () -> false);
        registry.add("things-link.ota.publication.enabled", () -> false);
        registry.add("things-link.ota.campaign.runtime-enabled", () -> false);
        registry.add("things-link.ota.notification.enabled", () -> false);
        registry.add("things-link.ota.retry.runtime-enabled", () -> false);
        registry.add("things-link.ingestion.emqx-api.base-url", () -> "http://127.0.0.1:" + FAILURE_HTTP.getAddress().getPort());
        registry.add("things-link.ingestion.emqx-api.api-key", () -> "owned-test-key");
        registry.add("things-link.ingestion.emqx-api.api-secret", () -> "owned-test-secret");
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
    /** 真实业务重试服务供生产Worker有界处理。 */
    @Autowired private com.things.link.ota.application.OtaBusinessRetryService retries;
    /** 当前普通APP连接，不能由owner连接代替生产清理入口。 */
    @Autowired private JdbcTemplate jdbc;
    /** JUnit独占上传临时目录，测试结束自动清理。 */
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    /** 每例重置调用计数及故障接缝，避免共享测试Bean泄漏上例行为。 */
    /** 每例冻结重试预算，零预算用于首次失败即终结验收。 */
    private static long downloadRetries=2, dispatchedSeconds=120;
    @org.junit.jupiter.api.BeforeEach void resetSigner() { downloadRetries=2; dispatchedSeconds=120; releaseNotAfter = 253402300799L; signer.calls.set(0); signer.beforeReturn = () -> { }; signer.unknown = false; signer.corrupt = false; org.mockito.Mockito.doReturn(true).when(publisher).configured(); }

    /** 报告接纳必须使用真实数据面事务代理。 */
    @Autowired private com.things.link.ota.application.OtaDeviceReportIngestionService reportIngestion;
    /** 当前配置的受控基线登记服务。 */
    @Autowired private com.things.link.ota.application.OtaTypeBaselineService baselines;
    /** 后台入口从真实持久租约取得scope，不传递或伪造管理账号。 */
    @Autowired private com.things.link.ota.application.OtaCampaignAdmissionService admission;
    /** 只对实际审计写入后故障施加接缝，不替换资格服务。 */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.things.link.support.audit.AuditLogService audit;

    /** 真实数据库通知事务入口，scope仅来自持久随机能力。 */
    @Autowired private com.things.link.ota.application.OtaNotificationService notifications;
    /** 仅放行配置存在性，不替换真实资格和持久发送前围栏。 */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.things.link.ota.application.OtaNotificationPublisher publisher;

    /** 真实无账号准入后冻结准确路由和八字段正文，HTTP接受仅改变delivery。 */
    @Test
    void preparesAuthorizedNotificationAndRecordsBrokerAcceptanceOnly() throws Exception {
        Running run=dispatched();var claim=notifications.claimOne().orElseThrow();
        var prepared=notifications.prepare(claim.eventId(),claim.leaseToken()).orElseThrow();
        assertThat(com.things.link.shared.tenant.TenantContext.current()).isEmpty();
        assertThat(prepared.projectKey()).isEqualTo("ota_"+run.prepared().fixture().projectId().toString().replace("-",""));
        assertThat(prepared.deviceKey()).isEqualTo("device_"+run.device());
        assertThat(prepared.transport().topic()).isEqualTo("tc/v1/"+prepared.projectKey()+"/"+prepared.deviceKey()+"/down/ota/available");
        var body=JSON.readTree(prepared.transport().canonical());assertThat(body.size()).isEqualTo(8);
        assertThat(body.path("eventId").asText()).isEqualTo(claim.eventId().toString());
        assertThat(body.path("jobId").asText()).isEqualTo(claim.jobId().toString());assertThat(body.path("attemptNo").asInt()).isEqualTo(1);
        assertThat(notifications.complete(prepared.transport().id(),prepared.transport().reservationToken(),accepted())).isTrue();
        assertThat(delivery(claim.eventId())).containsEntry("status","BROKER_ACCEPTED").containsEntry("transport_count",1);
        long observations=owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE target_id=? AND action='ota.notification.observed'",
                Long.class,prepared.transport().id());
        assertThat(observations).isEqualTo(1L);
        // 同一外部观察重放不新增事实，也不能无限放大高危审计。
        assertThat(notifications.complete(prepared.transport().id(),prepared.transport().reservationToken(),accepted())).isFalse();
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE target_id=? AND action='ota.notification.observed'",
                Long.class,prepared.transport().id())).isEqualTo(observations);
        assertThat(delivery(claim.eventId())).containsEntry("status","BROKER_ACCEPTED").containsEntry("transport_count",1);
        assertThat(execution(run).path("dispatchedCount").asLong()).isEqualTo(1);
        assertThat(execution(run).path("status").asText()).isEqualTo("RUNNING");
        assertThat(notifications.claimOne()).isEmpty();
        assertThat(owner().queryForObject("SELECT published_at FROM ota_job_dispatch_outbox WHERE id=?",java.sql.Timestamp.class,claim.eventId())).isNull();
        org.mockito.Mockito.verify(publisher,org.mockito.Mockito.never()).publish(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }

    /** 未知和明确拒绝都沿原event/正文/期限有限重试，不增加设备作业attempt。 */
    @Test
    void preservesCanonicalBodyAndOriginalDeadlineAcrossTransportRetries() throws Exception {
        Running run=dispatched();byte[] original=null;java.time.Instant deadline=null;UUID event=null;
        for(int index=1;index<=3;index++) {
            var claim=notifications.claimOne().orElseThrow();var prepared=notifications.prepare(claim.eventId(),claim.leaseToken()).orElseThrow();
            if(index==1){original=prepared.transport().canonical();deadline=prepared.transport().deadline();event=claim.eventId();}
            assertThat(prepared.transport().eventId()).isEqualTo(event);assertThat(prepared.transport().canonical()).isEqualTo(original);
            assertThat(prepared.transport().deadline()).isEqualTo(deadline);assertThat(prepared.transport().transportNo()).isEqualTo(index);
            var outcome=index==3?accepted():index==1?unknown():rejected();
            assertThat(notifications.complete(prepared.transport().id(),prepared.transport().reservationToken(),outcome)).isTrue();
            if(index<3){assertBackoff(event,index==1?5:20);makeRetryDue(event);}
        }
        assertThat(delivery(event)).containsEntry("status","BROKER_ACCEPTED").containsEntry("transport_count",3);
        assertThat(owner().queryForObject("SELECT attempt_no FROM ota_device_job WHERE campaign_id=?",Integer.class,run.campaign())).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT deadline_at FROM ota_device_job WHERE campaign_id=?",java.sql.Timestamp.class,run.campaign()).toInstant()).isEqualTo(deadline);
    }

    /** 新重试重新取得当前代次，已提交能力始终保留旧空间和原正文。 */
    @Test void freezesConfigurationVersionAcrossCommittedAttemptAndRechecksRetry() throws Exception {
        Running run=dispatched(); var fixture=run.prepared().fixture();
        owner().update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol,config_version) VALUES(?,?,?,'MQTT',7)",
                run.device(),fixture.tenantId(),fixture.projectId());
        var claim=notifications.claimOne().orElseThrow();
        var first=notifications.prepare(claim.eventId(),claim.leaseToken()).orElseThrow();
        assertThat(first.route().configVersion()).isEqualTo(7);
        assertThat(first.route().deviceId()).isEqualTo(run.device());
        owner().update("UPDATE dev_access_binding SET config_version=8 WHERE device_id=?",run.device());
        assertThat(first.route().internalTopic(first.transport().topic())).startsWith("tc/private/device/"+run.device()+"/7/");
        assertThat(notifications.complete(first.transport().id(),first.transport().reservationToken(),rejected())).isTrue();
        makeRetryDue(claim.eventId()); var retry=notifications.claimOne().orElseThrow();
        var second=notifications.prepare(retry.eventId(),retry.leaseToken()).orElseThrow();
        assertThat(second.route().configVersion()).isEqualTo(8);
        assertThat(second.transport().canonical()).isEqualTo(first.transport().canonical());
        assertThat(second.transport().topic()).isEqualTo(first.transport().topic());
        assertThat(second.transport().deadline()).isEqualTo(first.transport().deadline());
        assertThat(second.transport().transportNo()).isEqualTo(2);
    }

    /** 禁用或切出MQTT后不消费任何新发送预算。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"DISABLED","HTTP","COAP","TCP"})
    void rejectsUnavailableMqttConfigurationBeforeReservation(String mode) throws Exception {
        Running run=dispatched(); var fixture=run.prepared().fixture();
        owner().update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol,enabled) VALUES(?,?,?,?,?)",
                run.device(),fixture.tenantId(),fixture.projectId(),"DISABLED".equals(mode)?"MQTT":mode,!"DISABLED".equals(mode));
        var claim=notifications.claimOne().orElseThrow();
        assertThat(notifications.prepare(claim.eventId(),claim.leaseToken())).isEmpty();
        assertThat(delivery(claim.eventId())).containsEntry("transport_count",0);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_notification_transport WHERE event_id=?",Long.class,claim.eventId())).isZero();
    }

    /** 真正数据库锁失败不能降级成无路由或消耗发送预算，释放后同一租约可恢复。 */
    @Test void databaseRouteLockFailureRollsBackAndRecovers() throws Exception {
        Running run=dispatched(); var claim=notifications.claimOne().orElseThrow();
        try(var connection=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("SELECT id FROM dev_device WHERE id=? FOR UPDATE")) {
                lock.setObject(1,run.device()); try(var rows=lock.executeQuery()) { assertThat(rows.next()).isTrue(); }
            }
            assertThatThrownBy(()->notifications.prepare(claim.eventId(),claim.leaseToken()))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(delivery(claim.eventId())).containsEntry("transport_count",0);
            connection.rollback();
        }
        assertThat(notifications.prepare(claim.eventId(),claim.leaseToken()).orElseThrow().route().configVersion()).isZero();
    }

    /** 自然过期后的预留入口独立耗尽，没有物理发送或伪造失败观察。 */
    @Test void expiredNotificationPreparationProducesCompletionWithoutSending() throws Exception {
        downloadRetries=0; dispatchedSeconds=15;
        var run=dispatched();
        var deadline=owner().queryForObject("SELECT deadline_at FROM ota_device_job WHERE campaign_id=?",java.sql.Timestamp.class,run.campaign()).toInstant();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).until(()->java.time.Instant.now().isAfter(deadline));
        var claim=notifications.claimOne().orElseThrow();
        assertThat(notifications.prepare(claim.eventId(),claim.leaseToken())).isEmpty();
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE campaign_id=?",String.class,run.campaign())).isEqualTo("TIMED_OUT");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_notification_transport WHERE project_id=?",Integer.class,run.prepared().fixture().projectId())).isZero();
        OtaCompletionSourceAssertions.verify(owner(),run.prepared().fixture().projectId());
    }

    /** 原管理HTTP归类旧暂停；归类审计失败必须连同恢复及作业转移整体回滚。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs={0,2})
    void authorizedHistoricalResumeAuditsAndRollsBackAtomically(long limit) throws Exception {
        downloadRetries=limit;
        Running run=dispatched();
        UUID job=owner().queryForObject("SELECT id FROM ota_device_job WHERE campaign_id=?",UUID.class,run.campaign());
        // 单独构造升级前已耗尽观察；实际旧版本入口与升级由2c-2c验证。
        var source=owner().getDataSource();
        new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(source)).executeWithoutResult(tx->{
            var j=new JdbcTemplate(source); j.execute("SET LOCAL session_replication_role=replica");
            j.update("UPDATE ota_notification_delivery SET status='EXHAUSTED',revision=revision+1,exhausted_at=clock_timestamp(),updated_at=clock_timestamp(),reason='NOTIFICATION_DELIVERY_EXHAUSTED' WHERE job_id=?",job);
        });
        var paused=ok(send(run.prepared().fixture(),"POST",runtime(run)+"/pauses",key(),reason(execution(run).path("stateVersion").asText(),"旧失败暂停"),false),200);
        var before=owner().queryForMap("SELECT * FROM ota_device_job WHERE id=?",job);
        org.mockito.Mockito.doAnswer(invocation->{
            invocation.callRealMethod();
            com.things.link.support.audit.AuditLogEntry entry=invocation.getArgument(0);
            if("ota.retry.authorizedResume".equals(entry.action())) throw new IllegalStateException("受权恢复审计失败");
            return null;
        }).when(audit).record(org.mockito.ArgumentMatchers.any());
        try {
            assertThat(send(run.prepared().fixture(),"POST",runtime(run)+"/resumptions",key(),reason(paused.path("stateVersion").asText(),"原事务恢复"),false).statusCode()).isEqualTo(500);
        } finally { org.mockito.Mockito.doCallRealMethod().when(audit).record(org.mockito.ArgumentMatchers.any()); }
        assertThat(owner().queryForMap("SELECT * FROM ota_device_job WHERE id=?",job)).isEqualTo(before);
        assertThat(execution(run).path("status").asText()).isEqualTo("PAUSED");
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE target_id=? AND action='ota.retry.authorizedResume'",Integer.class,job)).isZero();
        var resumed=ok(send(run.prepared().fixture(),"POST",runtime(run)+"/resumptions",key(),reason(paused.path("stateVersion").asText(),"原事务恢复"),false),200);
        assertThat(resumed.path("status").asText()).isEqualTo(limit==0?"PAUSED":"RUNNING");
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?",String.class,job)).isEqualTo(limit==0?"TIMED_OUT":"RETRY_WAIT");
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE target_id=? AND action='ota.retry.authorizedResume'",Integer.class,job)).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_dispatch_outbox WHERE job_id=?",Integer.class,job)).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT status FROM ota_notification_delivery WHERE job_id=?",String.class,job)).isEqualTo("EXHAUSTED");
    }

    /** 实际管理暂停/恢复不能删除历史失败或创建额外业务尝试。 */
    private void assertManualResumePreservesFailure(Running run, UUID job) throws Exception {
        var before=owner().queryForMap("SELECT * FROM ota_device_job WHERE id=?",job);
        var failures=owner().queryForList("SELECT * FROM ota_notification_delivery WHERE job_id=? AND status='EXHAUSTED' ORDER BY job_attempt_no",job);
        var paused=ok(send(run.prepared().fixture(),"POST",runtime(run)+"/pauses",key(),reason(execution(run).path("stateVersion").asText(),"验证已处理失败归属"),false),200);
        var resumed=ok(send(run.prepared().fixture(),"POST",runtime(run)+"/resumptions",key(),reason(paused.path("stateVersion").asText(),"显式恢复原活动"),false),200);
        assertThat(resumed.path("status").asText()).isEqualTo("RUNNING");
        assertThat(owner().queryForMap("SELECT * FROM ota_device_job WHERE id=?",job)).usingRecursiveComparison().isEqualTo(before);
        assertThat(owner().queryForList("SELECT * FROM ota_notification_delivery WHERE job_id=? AND status='EXHAUSTED' ORDER BY job_attempt_no",job))
                .usingRecursiveComparison().isEqualTo(failures);
    }

    /** 真实HTTP503形成失败，原事务进入业务退避，生产Worker重派同一作业直到冻结上限。 */
    @Test
    void realHttpFailuresRetrySameJobWithinFrozenBudget() throws Exception {
        Running run = dispatched();
        var initial = owner().queryForMap("SELECT j.id,c.manifest_sha256 FROM ota_device_job j JOIN ota_campaign c ON c.id=j.campaign_id WHERE j.campaign_id=?", run.campaign());
        UUID job = (UUID) initial.get("id");
        int physicalBefore = HTTP_CALLS.get();
        var worker = new com.things.link.ota.application.OtaBusinessRetryWorker(retries, true);
        worker.start();
        try {
            for (int attempt = 1; attempt <= 3; attempt++) {
                for (int transport = 1; transport <= 3; transport++) {
                    var claim = notifications.claimOne().orElseThrow();
                    assertThat(claim.jobId()).isEqualTo(job);
                    assertThat(claim.jobAttemptNo()).isEqualTo(attempt);
                    var prepared = notifications.prepare(claim.eventId(), claim.leaseToken()).orElseThrow();
                    var result = publisher.publish(prepared.route(), prepared.transport().canonical(), Duration.ofSeconds(3));
                    assertThat(result.status()).isEqualTo(503);
                    assertThat(notifications.complete(prepared.transport().id(), prepared.transport().reservationToken(), result)).isTrue();
                    if (transport < 3) makeRetryDue(claim.eventId());
                    else assertThat(delivery(claim.eventId())).containsEntry("status", "EXHAUSTED").containsEntry("transport_count", 3);
                }
                assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?", String.class, job))
                        .isEqualTo(attempt < 3 ? "RETRY_WAIT" : "TIMED_OUT");
                if (attempt < 3) {
                    assertThat(execution(run).path("status").asText()).isEqualTo("RUNNING");
                    assertManualResumePreservesFailure(run, job);
                    int next = attempt + 1;
                    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
                        worker.tick();
                        assertThat(owner().queryForObject("SELECT attempt_no FROM ota_device_job WHERE id=?", Integer.class, job)).isEqualTo(next);
                    });
                    assertManualResumePreservesFailure(run, job);
                }
            }
        } finally { worker.stop(); }
        assertThat(HTTP_CALLS.get() - physicalBefore).isEqualTo(9);
        assertThat(owner().queryForMap("SELECT j.id,c.manifest_sha256 FROM ota_device_job j JOIN ota_campaign c ON c.id=j.campaign_id WHERE j.campaign_id=?", run.campaign())).isEqualTo(initial);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_dispatch_outbox WHERE job_id=?", Integer.class, job)).isEqualTo(3);
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE target_id=? AND action='ota.retry.failure_observed'", Integer.class, job)).isEqualTo(3);
        var state = execution(run);
        assertThat(state.path("status").asText()).isEqualTo("PAUSED");
        assertThat(state.path("pauseKind").asText()).isEqualTo("AUTO");
        assertThat(notifications.claimOne()).isEmpty();
        assertThat(retries.claimDue()).isEmpty();
        assertThat(owner().queryForObject("SELECT status FROM ota_campaign_batch WHERE campaign_id=?",String.class,run.campaign())).isEqualTo("FAILED");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_campaign_completion WHERE campaign_id=?",Integer.class,run.campaign())).isZero();
        error(send(run.prepared().fixture(),"POST",runtime(run)+"/resumptions",key(),reason(state.path("stateVersion").asText(),"失败批次不能恢复"),false),409,70040);
        ok(send(run.prepared().fixture(),"POST",runtime(run)+"/cancellation",key(),reason(state.path("stateVersion").asText(),"结束已耗尽活动"),false),200);
        assertThat(execution(run).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?",String.class,job)).isEqualTo("TIMED_OUT");
        assertObjectExists(run.prepared().upload());
    }

    /** 第三次失败的业务审计回滚传输和作业，随后并发重复回执只能消费一次预算。 */
    @Test void businessFailureAuditRollbackAndDuplicateCompletion() throws Exception {
        Running run = dispatched();
        for (int index = 0; index < 2; index++) {
            var claim = notifications.claimOne().orElseThrow();
            var prepared = notifications.prepare(claim.eventId(), claim.leaseToken()).orElseThrow();
            assertThat(notifications.complete(prepared.transport().id(), prepared.transport().reservationToken(), rejected())).isTrue();
            makeRetryDue(claim.eventId());
        }
        var claim = notifications.claimOne().orElseThrow();
        var prepared = notifications.prepare(claim.eventId(), claim.leaseToken()).orElseThrow();
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.callRealMethod();
            com.things.link.support.audit.AuditLogEntry entry = invocation.getArgument(0);
            if ("ota.retry.failure_observed".equals(entry.action())) throw new IllegalStateException("测试业务归类审计失败");
            return null;
        }).when(audit).record(org.mockito.ArgumentMatchers.any());
        assertThatThrownBy(() -> notifications.complete(prepared.transport().id(), prepared.transport().reservationToken(), rejected()))
                .isInstanceOf(IllegalStateException.class).hasMessage("测试业务归类审计失败");
        assertThat(delivery(claim.eventId())).containsEntry("status", "IN_FLIGHT");
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?", String.class, claim.jobId())).isEqualTo("DISPATCHED");
        assertThat(owner().queryForObject("SELECT outcome FROM ota_notification_transport WHERE id=?", String.class, prepared.transport().id())).isNull();
        org.mockito.Mockito.doCallRealMethod().when(audit).record(org.mockito.ArgumentMatchers.any());
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> complete = () -> {
                start.await();
                return notifications.complete(prepared.transport().id(), prepared.transport().reservationToken(), rejected());
            };
            var first = executor.submit(complete);
            var second = executor.submit(complete);
            start.countDown();
            assertThat(List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE job_id=? AND to_status='RETRY_WAIT'", Integer.class, claim.jobId())).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE target_id=? AND action='ota.retry.failure_observed'", Integer.class, claim.jobId())).isEqualTo(1);
        assertThat(execution(run).path("status").asText()).isEqualTo("RUNNING");
    }

    /** 两个独立Spring JVM竞争真实失败产生的到期事实，领取者强杀后自然失租并由另一实例接管。 */
    @Test void independentProcessesRecoverCommittedRetryLeaseAfterCrash() throws Exception {
        try (var processes = new OtaRetryProcessFixture(POSTGRES.getJdbcUrl(), REDIS.getHost(), REDIS.getMappedPort(6379))) {
            var a = processes.start("a");
            var b = processes.start("b");
            assertThat(a.process().pid()).isNotEqualTo(b.process().pid());
            Running run = dispatched();
            UUID job = owner().queryForObject("SELECT id FROM ota_device_job WHERE campaign_id=?", UUID.class, run.campaign());
            for (int index = 1; index <= 3; index++) {
                var claim = notifications.claimOne().orElseThrow();
                var prepared = notifications.prepare(claim.eventId(), claim.leaseToken()).orElseThrow();
                var result = publisher.publish(prepared.route(), prepared.transport().canonical(), Duration.ofSeconds(3));
                assertThat(result.status()).isEqualTo(503);
                assertThat(notifications.complete(prepared.transport().id(), prepared.transport().reservationToken(), result)).isTrue();
                if (index < 3) makeRetryDue(claim.eventId());
            }
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(12)).until(() -> owner().queryForObject(
                    "SELECT next_attempt_at<=clock_timestamp() FROM ota_device_job WHERE id=?", Boolean.class, job));
            String first;
            String second;
            try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var ca = executor.submit(() -> processes.command(a, "claim", null));
                var cb = executor.submit(() -> processes.command(b, "claim", null));
                first = ca.get(15, java.util.concurrent.TimeUnit.SECONDS);
                second = cb.get(15, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertThat(List.of(first, second).stream().filter("null"::equals).count()).isEqualTo(1);
            boolean aWon = !"null".equals(first);
            var winner = aWon ? a : b;
            var survivor = aWon ? b : a;
            String oldClaim = aWon ? first : second;
            var stale = processes.directory.resolve("stale.json");
            java.nio.file.Files.writeString(stale, oldClaim);
            assertThat(JSON.readTree(oldClaim).path("jobId").asText()).isEqualTo(job.toString());
            processes.kill(winner);
            assertThat(processes.command(survivor, "claim", null)).isEqualTo("null");
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> owner().queryForObject(
                    "SELECT lease_until<=clock_timestamp() FROM ota_device_job WHERE id=?", Boolean.class, job));
            var restarted = processes.start("restarted");
            String freshClaim = processes.command(survivor, "claim", null);
            assertThat(freshClaim).isNotEqualTo("null");
            assertThat(JSON.readTree(freshClaim).path("token").asText()).isNotEqualTo(JSON.readTree(oldClaim).path("token").asText());
            var fresh = processes.directory.resolve("fresh.json");
            java.nio.file.Files.writeString(fresh, freshClaim);
            assertThat(processes.command(restarted, "classify", stale)).isEqualTo("false");
            assertThat(processes.command(survivor, "classify", fresh)).isEqualTo("true");
            assertThat(processes.command(restarted, "classify", fresh)).isEqualTo("false");
            assertThat(owner().queryForMap("SELECT status,attempt_no FROM ota_device_job WHERE id=?", job))
                    .containsEntry("status", "DISPATCHED").containsEntry("attempt_no", 2);
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_dispatch_outbox WHERE job_id=?", Integer.class, job)).isEqualTo(2);
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_execution_origin WHERE job_id=?", Integer.class, job)).isEqualTo(2);
            assertThat(owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE target_id=? AND action='ota.retry.dispatchRetry'", Integer.class, job)).isEqualTo(1);
        }
    }

    /** 预留后人工暂停，迟到真实2xx只能附加观察，不能恢复活动或认领新传输。 */
    @Test
    void preservesLateAcceptanceWithoutAdvancingPausedDelivery() throws Exception {
        Running run=dispatched();var claim=notifications.claimOne().orElseThrow();
        var prepared=notifications.prepare(claim.eventId(),claim.leaseToken()).orElseThrow();
        var state=execution(run);ok(send(run.prepared().fixture(),"POST",runtime(run)+"/pauses",key(),reason(state.path("stateVersion").asText(),"停止新通知"),false),200);
        assertThat(notifications.complete(prepared.transport().id(),prepared.transport().reservationToken(),accepted())).isTrue();
        assertThat(owner().queryForObject("SELECT outcome FROM ota_notification_transport WHERE id=?",String.class,prepared.transport().id())).isEqualTo("BROKER_ACCEPTED");
        assertThat(delivery(claim.eventId()).get("status")).isNotEqualTo("BROKER_ACCEPTED");
        assertThat(execution(run).path("pauseKind").asText()).isEqualTo("MANUAL");
        assertThat(notifications.prepare(claim.eventId(),claim.leaseToken())).isEmpty();
    }

    /** 缺发布配置安全暂停而非伪发送，不消耗任何HTTP预留次数。 */
    @Test
    void pausesSecurityWhenPublisherConfigurationIsMissing() throws Exception {
        Running run=dispatched();org.mockito.Mockito.doReturn(false).when(publisher).configured();
        var claim=notifications.claimOne().orElseThrow();assertThat(notifications.prepare(claim.eventId(),claim.leaseToken())).isEmpty();
        assertThat(delivery(claim.eventId())).containsEntry("transport_count",0);
        var state=execution(run);assertThat(state.path("pauseKind").asText()).isEqualTo("SECURITY");
        assertThat(state.path("pauseReason").asText()).isEqualTo("NOTIFICATION_TRANSPORT_UNAVAILABLE");
        assertThat(state.path("pauseJobId").asText()).isEqualTo(claim.jobId().toString());
    }

    /** 通知预留前凭据代际变化只退避，不把已准入作业改成SKIPPED或消费传输预算。 */
    @Test
    void defersChangedCredentialWithoutConsumingTransportBudget() throws Exception {
        Running run=dispatched();owner().update("UPDATE dev_device SET credential_version=2 WHERE id=?",run.device());
        var claim=notifications.claimOne().orElseThrow();assertThat(notifications.prepare(claim.eventId(),claim.leaseToken())).isEmpty();
        assertThat(delivery(claim.eventId())).containsEntry("status","RETRY_WAIT").containsEntry("transport_count",0);
        assertThat(execution(run).path("dispatchedCount").asLong()).isEqualTo(1);assertThat(execution(run).path("skippedCount").asLong()).isZero();
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_notification_transport WHERE event_id=?",Long.class,claim.eventId())).isZero();
    }

    /** 随机能力与账号上下文分离，错event或管理线程不能替代后台授权。 */
    @Test
    void rejectsForgedTokensAndInheritedManagementIdentity() throws Exception {
        Running run=dispatched();var claim=notifications.claimOne().orElseThrow();
        assertThat(notifications.prepare(claim.eventId(),Uuid7.generate())).isEmpty();
        assertThat(notifications.prepare(Uuid7.generate(),claim.leaseToken())).isEmpty();
        assertThatThrownBy(()->scoped(run.prepared().fixture(),()->notifications.prepare(claim.eventId(),claim.leaseToken())))
                .isInstanceOf(IllegalStateException.class).hasMessage("OTA通知不能继承管理账号");
        var prepared=notifications.prepare(claim.eventId(),claim.leaseToken()).orElseThrow();
        assertThat(notifications.complete(prepared.transport().id(),Uuid7.generate(),accepted())).isFalse();
        assertThat(notifications.complete(Uuid7.generate(),prepared.transport().reservationToken(),accepted())).isFalse();
        assertThat(owner().queryForObject("SELECT outcome FROM ota_notification_transport WHERE id=?",String.class,prepared.transport().id())).isNull();
    }

    /** 预留或回执的真实审计写后异常必须回滚，不吞首因、不重复消耗预算。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"RESERVE","OBSERVE"})
    void rollsBackNotificationTransactionWhenAuditInsertFails(String stage) throws Exception {
        Running run=dispatched();var claim=notifications.claimOne().orElseThrow();
        var prepared="OBSERVE".equals(stage)?notifications.prepare(claim.eventId(),claim.leaseToken()).orElseThrow():null;
        org.mockito.Mockito.doAnswer(invocation->{invocation.callRealMethod();throw new IllegalStateException("测试通知审计写后失败");})
                .when(audit).record(org.mockito.ArgumentMatchers.any());
        if(prepared==null){
            assertThatThrownBy(()->notifications.prepare(claim.eventId(),claim.leaseToken())).isInstanceOf(IllegalStateException.class).hasMessage("测试通知审计写后失败");
            assertThat(delivery(claim.eventId())).containsEntry("status","WAITING").containsEntry("transport_count",0);
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_notification_transport WHERE event_id=?",Long.class,claim.eventId())).isZero();
        }else{
            assertThatThrownBy(()->notifications.complete(prepared.transport().id(),prepared.transport().reservationToken(),accepted())).isInstanceOf(IllegalStateException.class).hasMessage("测试通知审计写后失败");
            assertThat(owner().queryForObject("SELECT outcome FROM ota_notification_transport WHERE id=?",String.class,prepared.transport().id())).isNull();
            assertThat(delivery(claim.eventId())).containsEntry("status","IN_FLIGHT").containsEntry("transport_count",1);
        }
        org.mockito.Mockito.doCallRealMethod().when(audit).record(org.mockito.ArgumentMatchers.any());
        var retried=prepared==null?notifications.prepare(claim.eventId(),claim.leaseToken()).orElseThrow():prepared;
        assertThat(notifications.complete(retried.transport().id(),retried.transport().reservationToken(),accepted())).isTrue();
        assertThat(delivery(claim.eventId())).containsEntry("status","BROKER_ACCEPTED").containsEntry("transport_count",1);
    }

    /** 完整业务前置生成真正DISPATCHED通知意图，不直接插入delivery。 */
    private Running dispatched() throws Exception {
        Running run=running(true);var claim=admission.claimOne().orElseThrow();assertThat(admission.admit(claim.jobId(),claim.token())).isTrue();return run;
    }
    /** 普通owner仅观察本例结果。 */
    private static java.util.Map<String,Object> delivery(UUID event){return owner().queryForMap("SELECT status,revision,transport_count,deadline_at FROM ota_notification_delivery WHERE event_id=?",event);}
    /** 确认数据库确实安排冻结退避，再由fixture推进其可领取时间以避免25秒无效等待。 */
    private static void assertBackoff(UUID event,long seconds){assertThat(owner().queryForObject("SELECT extract(epoch FROM next_attempt_at-updated_at)::bigint FROM ota_notification_delivery WHERE event_id=?",Long.class,event)).isEqualTo(seconds);}
    /** 只推进测试领取时刻，遵从CAS加一；原deadline、正文及次数一律不变。 */
    private static void makeRetryDue(UUID event){owner().update("UPDATE ota_notification_delivery SET next_attempt_at=clock_timestamp(),updated_at=clock_timestamp(),revision=revision+1 WHERE event_id=? AND status='RETRY_WAIT'",event);}
    /** 真实发布器相同的2xx观察形状，本类不宣称已经访问Broker。 */
    private static com.things.link.ota.application.OtaNotificationPublisher.Result accepted(){return new com.things.link.ota.application.OtaNotificationPublisher.Result(com.things.link.ota.application.OtaNotificationPublisher.Outcome.BROKER_ACCEPTED,200,"HTTP_ACCEPTED");}
    /** 已观察HTTP明确拒绝。 */
    private static com.things.link.ota.application.OtaNotificationPublisher.Result rejected(){return new com.things.link.ota.application.OtaNotificationPublisher.Result(com.things.link.ota.application.OtaNotificationPublisher.Outcome.REJECTED,503,"HTTP_REJECTED");}
    /** 已发起但未观察响应，不把未知降为未发送。 */
    private static com.things.link.ota.application.OtaNotificationPublisher.Result unknown(){return new com.things.link.ota.application.OtaNotificationPublisher.Result(com.things.link.ota.application.OtaNotificationPublisher.Outcome.UNKNOWN,null,"TRANSPORT_UNKNOWN");}


    /** 完整受控前置都经真实服务，报告可选用于准确验证零分母。 */
    private Running running(boolean reported) throws Exception {
        Prepared p=published();Fixture f=p.fixture();UUID device=initialized(p);
        if(reported) reportIngestion.accept(identity(f,device,1),report(f,1,0,bundleHash(f)),java.time.Instant.now());
        UUID campaign=UUID.fromString(ok(send(f,"POST",campaigns(f),key(),plan(p.firmware(),List.of(device)),false),201).path("id").asText());
        String path=campaigns(f)+"/"+campaign;
        ok(send(f,"POST",path+"/scheduling",key(),revision("0"),false),200);
        ok(send(f,"POST",path+"/starting",key(),revision("1"),false),200);
        return new Running(p,device,campaign);
    }
    /** 当前运行事实每次真实读取并禁止HTTP缓存。 */
    private JsonNode execution(Running run) throws Exception {
        var response=send(run.prepared().fixture(),"GET",runtime(run)+"/execution",null,null,false);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");return ok(response,200);
    }
    /** 当前项目活动集合。 */
    private static String campaigns(Fixture f){return "/api/v1/projects/"+f.projectId()+"/ota/campaigns";}
    /** 运行命令和投影共享的活动身份。 */
    private static String runtime(Running run){return campaigns(run.prepared().fixture())+"/"+run.campaign();}
    /** 启动不接受隐式修订。 */
    private static byte[] revision(String value){return CANONICAL.writeObject(java.util.Map.of("expectedRevision",value));}
    /** 人工操作理由不能替代授权或安全复核。 */
    private static byte[] reason(String revision,String reason){return CANONICAL.writeObject(java.util.Map.of("expectedRevision",revision,"reason",reason));}
    /** 本例明确运行资源。
     * @param prepared 已发布固定版本
     * @param device 已冻结设备
     * @param campaign 活动身份
     */
    private record Running(Prepared prepared,UUID device,UUID campaign) { }


    /** 显式完整策略与稳定名单，旧时间表示立即可执行但本片仍不派发。 */
    private static byte[] plan(UUID firmware,List<UUID> devices) {
        var policy=new java.util.LinkedHashMap<String,Object>();
        policy.put("maxConcurrentDownloads",2L);policy.put("maxDownloadBytesPerSecond",1048576L);
        policy.put("downloadRetryLimit",downloadRetries);policy.put("retryBackoffSeconds",5L);policy.put("healthWindowSeconds",60L);
        policy.put("pauseMinEvaluated",2L);policy.put("pauseFailureCount",1L);policy.put("pauseFailureRateBps",5000L);
        policy.put("batchMinSuccessRateBps",10000L);policy.put("requireManualBatchApproval",true);
        var stages=new java.util.LinkedHashMap<String,Object>();
        for(String name:List.of("DISPATCHED","DOWNLOADING","VERIFYING","INSTALLING","REBOOTING","HEALTH_CHECKING","CONFIRMING","ROLLBACK_PENDING","ROLLING_BACK")) stages.put(name,120L);
        stages.put("DISPATCHED",dispatchedSeconds);
        policy.put("stageTimeoutSeconds",stages);
        return CANONICAL.writeObject(java.util.Map.of("contractVersion","tc-ota-campaign-plan/v1","firmwareId",firmware.toString(),
                "deviceIds",devices.stream().map(UUID::toString).toList(),"batchSize",2L,"notBefore","2026-09-12T00:00:00Z","executionPolicy",policy));
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
    /** 测试全部结束后销毁自有容器。 */ @AfterAll static void stopStorage() { MINIO.stop(); FAILURE_HTTP.stop(0); }
    /** 新OTA引用必须先清理，再删除本例模型与项目；不修改触发器或禁用外键。 */
    @AfterEach
    void clearOwnedFacts() throws Exception {
        try {
            for (Fixture fixture : fixtures) OtaCompletionSourceAssertions.verify(owner(),fixture.projectId());
        } finally {
        try (MinioClient admin = admin()) {
            for (var item : admin.listObjects(ListObjectsArgs.builder().bucket(BUCKET).recursive(true).includeVersions(true).build())) {
                var version = item.get();
                admin.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(version.objectName()).versionId(version.versionId()).build());
            }
        }
        JdbcTemplate owner = owner();
        for (Fixture fixture : fixtures) {
            owner.update("DELETE FROM sys_outbox_event WHERE project_id=?",fixture.projectId());
            new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(owner.getDataSource())).executeWithoutResult(status -> {
                for(String table:List.of("ota_job_progress_outbox","ota_job_progress","ota_job_expiry","ota_job_execution_origin","ota_notification_transport","ota_notification_delivery","ota_job_dispatch_outbox","ota_job_transition","ota_batch_transition",
                        "ota_campaign_outbox","ota_campaign_runtime_cancellation","ota_campaign_transition","ota_device_job","ota_campaign_batch",
                        "ota_campaign_creation_request","ota_campaign")) owner.update("DELETE FROM "+table+" WHERE project_id=?",fixture.projectId());
            });
            owner.update("DELETE FROM ota_firmware_release WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_firmware_publication WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_trust_bundle WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_trust_domain WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_firmware_upload_session WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_firmware_creation_request WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_firmware WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_device_report WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM dev_access_binding WHERE project_id = ?", fixture.projectId());
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
