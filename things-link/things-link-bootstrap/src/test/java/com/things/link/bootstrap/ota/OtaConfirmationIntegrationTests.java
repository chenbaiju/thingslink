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

/** 真实PG、MinIO、HTTP和raw Kafka验证健康窗口、固定许可和提交成功闭环，不冒充实机刷写。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import({OtaConfirmationIntegrationTests.SigningConfiguration.class,
        com.things.link.bootstrap.integration.WebhookCompletionJourney.ReceiverConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OtaConfirmationIntegrationTests extends com.things.link.testing.AbstractKafkaIntegrationTest {
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
    private static final String ACCESS = "download-authorization-http-test", SECRET = "download-authorization-http-secret";
    /** 本测试唯一桶，不触碰开发存储。 */
    private static final String BUCKET = "ota-confirm-http-" + UUID.randomUUID();
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
        registry.add("things-link.storage.internal-endpoint", OtaConfirmationIntegrationTests::endpoint);
        registry.add("things-link.storage.external-endpoint", OtaConfirmationIntegrationTests::endpoint);
        registry.add("things-link.storage.access-key", () -> ACCESS);
        registry.add("things-link.storage.secret-key", () -> SECRET);
        registry.add("things-link.ota.storage.bucket", () -> BUCKET);
        registry.add("things-link.ota.type-baselines-json", OtaConfirmationIntegrationTests::baselineSource);
        registry.add("spring.kafka.listener.auto-startup", () -> false);
        registry.add("things-link.ota.progress-timeout.enabled", () -> false);
        registry.add("things-link.ota.download-authorization.enabled", () -> false);
        registry.add("things-link.ingestion.emqx-api.base-url", () -> "http://127.0.0.1:"+BROKER.getAddress().getPort());
        registry.add("things-link.ingestion.emqx-api.api-key", () -> "authorization-test");
        registry.add("things-link.ingestion.emqx-api.api-secret", () -> "authorization-test-secret");
        registry.add("things-link.ota.download-response.active-key-version", () -> "test-v1");
        registry.add("things-link.ota.download-response.keyring-json", () -> "{\"test-v1\":\""+java.util.Base64.getEncoder().encodeToString(new byte[32])+"\"}");
        registry.add("things-link.ota.upload.recovery-enabled", () -> false);
        registry.add("things-link.ota.publication.enabled", () -> false);
        registry.add("things-link.ota.campaign.runtime-enabled", () -> false);
        registry.add("things-link.ota.notification.enabled", () -> false);
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
    /** 已通过生产清理验证的项目：其业务记录已删，Outbox独立保留。 */
    private final java.util.Set<UUID> verifiedPurgedProjects = new java.util.HashSet<>();


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
    @org.junit.jupiter.api.BeforeEach void resetSigner() { releaseNotAfter = 253402300799L; signer.calls.set(0); signer.beforeReturn = () -> { }; signer.unknown = false; signer.corrupt = false; dispatchedSeconds=120; progressSeconds=120; confirmingSeconds=120; differentTarget=false; BROKER_STATUS.set(202); LAST_BODY.set(null); org.mockito.Mockito.doCallRealMethod().when(responseCipher).configured(); }

    /** 报告接纳必须使用真实数据面事务代理。 */
    @Autowired private com.things.link.ota.application.OtaDeviceReportIngestionService reportIngestion;
    /** 当前配置的受控基线登记服务。 */
    @Autowired private com.things.link.ota.application.OtaTypeBaselineService baselines;
    /** 后台入口从真实持久租约取得scope，不传递或伪造管理账号。 */
    @Autowired private com.things.link.ota.application.OtaCampaignAdmissionService admission;
    /** 只对实际审计写入后故障施加接缝，不替换资格服务。 */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.things.link.support.audit.AuditLogService audit;

    /** 原认证数据面接纳代理，保持真实事务与RLS。 */
    @Autowired private com.things.link.ota.application.OtaDownloadRequestIngestionService requests;
    /** 真正认证进度数据面事务服务。 */
    @Autowired private com.things.link.ota.application.OtaJobProgressIngestionService progressService;
    /** 独立持久阶段超时能力。 */
    @Autowired private com.things.link.ota.application.OtaJobProgressTimeoutService timeouts;
    /** 只启动本专项所需原始消息监听器。 */
    @Autowired private org.springframework.kafka.config.KafkaListenerEndpointRegistry listeners;
    /** 使用生产消息序列化的真实Kafka发送器。 */
    @Autowired private org.springframework.kafka.core.KafkaTemplate<String,Object> kafka;
    /** 实际授权短事务服务，签址和网络在事务外调用。 */
    @Autowired private com.things.link.ota.application.OtaDownloadAuthorizationService authorizations;
    /** 真正版本化私桶，签址不能用伪造URL替代。 */
    @Autowired private com.things.link.support.storage.VersionedPrivateObjectStorage storage;
    /** 真实本机HTTP适配器，接收端仅模拟Broker回执。 */
    @Autowired private com.things.link.ota.application.OtaDownloadResponsePublisher responsePublisher;
    /** 仅缺配置专项切换configured；加解密仍由真实AEAD实现。 */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.things.link.ota.application.OtaDownloadResponseCipher responseCipher;
    /** 最后观察到的测试HTTP正文，不输出到日志。 */
    private static final java.util.concurrent.atomic.AtomicReference<byte[]> LAST_BODY=new java.util.concurrent.atomic.AtomicReference<>();
    /** 测试受控Broker回执码。 */
    private static final java.util.concurrent.atomic.AtomicInteger BROKER_STATUS=new java.util.concurrent.atomic.AtomicInteger(202);
    /** 仅本类随机端口的真实HTTP接收端，不宣称实际MQTT设备接收。 */
    private static final com.sun.net.httpserver.HttpServer BROKER=broker();
    /** 在Spring属性读取前建立专用HTTP端点。 */
    private static com.sun.net.httpserver.HttpServer broker(){
        try{var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api/v5/publish",exchange->{LAST_BODY.set(exchange.getRequestBody().readAllBytes());exchange.sendResponseHeaders(BROKER_STATUS.get(),-1);exchange.close();});server.start();return server;
        }catch(java.io.IOException failure){throw new ExceptionInInitializerError(failure);}
    }
    /** 本例可缩短原始派发预算，绝不更新已冻结期限。 */
    private static long dispatchedSeconds=120;
    /** 只有超时专项缩短两个阶段原始策略，不更新已生成deadline。 */
    private static long progressSeconds=120;
    /** 许可原始期限仅由测试排程策略声明。 */ private static long confirmingSeconds=120;
    /** 真实发布另一个已持久模型，来源仍保持初始版本。 */ private static boolean differentTarget=false;
    /** 固定不同目标模型身份。 */ private static final UUID TARGET_MODEL=Uuid7.generate();
    /** 公开本域模型转换服务，保留真实转换历史与来源CAS。 */ @Autowired private com.things.link.device.application.ThingModelVersionBindingService modelBindings;
    /** 独立真实仓储用于绕Java的数据库负例。 */ @Autowired private com.things.link.ota.domain.OtaConfirmationRepository confirmationRepository;
    /** 已持久作业上下文，不伪造状态。 */ @Autowired private com.things.link.ota.domain.OtaJobProgressRepository confirmationProgress;
    /** 负例沿真实APP事务和设备RLS。 */ @Autowired private com.things.link.support.tenant.TransactionLocalRlsScope confirmationRls;
    /** 生产清理批次以APP真实租约推进，不能由owner执行领域删除。 */ @Autowired private com.things.link.project.application.ProjectCleanupBatchService projectCleanupBatches;
    /** 生产领取与跨阶段推进，不手改后续阶段或续租。 */ @Autowired private com.things.link.project.application.ProjectCleanupAdmissionService projectCleanupAdmission;
    /** 健康和提交生产事务入口。 */ @Autowired private com.things.link.ota.application.OtaConfirmationIngestionService confirmation;
    /** 真实许可交付预留和回执。 */ @Autowired private com.things.link.ota.application.OtaCommitPermitDeliveryService permitDelivery;
    /** 单次真实本地HTTP发送器。 */ @Autowired private com.things.link.ota.application.OtaCommitPermitPublisher permitPublisher;

    /** 新许可冻结配置7，提交后变为8仍只向原空间发送原规范字节。 */
    @Test void frozenRouteSurvivesConfigurationChangeBeforePhysicalPublish() throws Exception {
        var f=confirming();var run=f.run();var fixture=run.prepared().fixture();
        owner().update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol,config_version) VALUES(?,?,?,'MQTT',7)",
                run.device(),fixture.tenantId(),fixture.projectId());
        var claim=permitDelivery.claimOne().orElseThrow();var prepared=permitDelivery.prepare(claim.permit().id(),claim.leaseToken()).orElseThrow();
        owner().update("UPDATE dev_access_binding SET config_version=8 WHERE device_id=?",run.device());
        var body=prepared.transport().permit();
        var result=permitPublisher.publish(prepared.route(),body.canonical(),java.time.Instant.ofEpochSecond(body.deadlineAt().getEpochSecond()),
                prepared.leaseUntil(),Duration.ofMillis(300));
        assertThat(result.outcome().name()).isEqualTo("BROKER_ACCEPTED");
        var wire=JSON.readTree(LAST_BODY.get());assertThat(wire.path("topic").asText()).isEqualTo(
                "tc/private/device/"+run.device()+"/7/tc/v1/"+prepared.projectKey()+"/"+prepared.deviceKey()+"/down/ota/commit/permit");
        assertThat(java.util.Base64.getDecoder().decode(wire.path("payload").asText())).containsExactly(body.canonical());
        assertThat(permitDelivery.complete(prepared.transport().id(),prepared.transport().reservationToken(),result)).isTrue();
    }

    /** 禁用或切换非MQTT协议时零预留，不将不可用路由当作执行许可。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"DISABLED","HTTP","COAP","TCP"})
    void unavailableConfigurationRejectsNewTransport(String mode) throws Exception {
        var f=confirming();var run=f.run();var fixture=run.prepared().fixture();
        var claim=permitDelivery.claimOne().orElseThrow();
        owner().update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol,enabled) VALUES(?,?,?,?,?)",
                run.device(),fixture.tenantId(),fixture.projectId(),"DISABLED".equals(mode)?"MQTT":mode,!"DISABLED".equals(mode));
        LAST_BODY.set(null);assertThat(permitDelivery.prepare(claim.permit().id(),claim.leaseToken())).isEmpty();
        assertThat(owner().queryForObject("SELECT transport_count FROM ota_commit_delivery WHERE event_id=?",Integer.class,claim.permit().id())).isZero();
        assertThat(LAST_BODY.get()).isNull();
    }

    /** 数据库实际设备锁冲突回滚许可，释放后原租约可恢复且未消耗次数。 */
    @Test void routeLockFailureDoesNotConsumeTransport() throws Exception {
        var f=confirming();var run=f.run();var claim=permitDelivery.claimOne().orElseThrow();
        try(var connection=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("SELECT id FROM dev_device WHERE id=? FOR UPDATE")) {
                lock.setObject(1,run.device());try(var rows=lock.executeQuery()){assertThat(rows.next()).isTrue();}
            }
            assertThatThrownBy(()->permitDelivery.prepare(claim.permit().id(),claim.leaseToken()))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(owner().queryForObject("SELECT transport_count FROM ota_commit_delivery WHERE event_id=?",Integer.class,claim.permit().id())).isZero();
            connection.rollback();
        }
        assertThat(permitDelivery.prepare(claim.permit().id(),claim.leaseToken()).orElseThrow().route().configVersion()).isZero();
    }

    /** 真正许可及提交闭环到公开Kafka/TLS；受理失败不撤销已完成OTA，接收提交不确定不换身份。 */
    @Test void otaCompletionSurvivesAdmissionAndDeliveryFailure() throws Exception {
        var f=confirming(); reserveAndPublish(f); var fixture=f.run().prepared().fixture();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->
                completionApplication.getBean(com.things.link.project.application.TenantSubscriptionProvisioning.class)
                        .provisionFreeSubscription(fixture.tenantId()));
        try {
            com.things.link.bootstrap.integration.WebhookCompletionJourney.verify(completionApplication,owner(),
                    fixture.tenantId(),fixture.projectId(),fixture.accountId(),f.run().device(),"ota.job.completed",()->{
                byte[] payload=commit(f,Uuid7.generate());
                assertThat(acceptCommit(f,payload,java.time.Instant.now()).name()).isEqualTo("SUCCEEDED");
                assertThat(acceptCommit(f,payload,java.time.Instant.now()).name()).isEqualTo("REPLAY");
                OtaCompletionSourceAssertions.verify(owner(),fixture.projectId());
            });
            assertThat(job(f.run())).containsEntry("status","SUCCEEDED");
            OtaCompletionSourceAssertions.verify(owner(),fixture.projectId());
        } finally { owner().update("DELETE FROM sys_tenant_subscription WHERE tenant_id=?",fixture.tenantId()); }
    }
    /** 仅供本地完整交付旅程取得生产Bean，未绕过原事务与状态机。 */
    @Autowired private org.springframework.context.ApplicationContext completionApplication;

    /** 两个真实Broker时刻跨稳定窗口后，同模型证据原子完成成功且固定许可实际经HTTP发布。 */
    @Test void completesSameModelAfterRealPermitTransmission() throws Exception {
        var f=confirming();var delivery=reserveAndPublish(f);byte[] payload=commit(f,Uuid7.generate());
        assertThat(acceptCommit(f,payload,java.time.Instant.now()).name()).isEqualTo("SUCCEEDED");
        assertThat(job(f.run())).containsEntry("status","SUCCEEDED");
        assertThat(count(f,"dev_device_model_binding_history")).isZero();
        assertThat(owner().queryForMap("SELECT decision,committed_security_version FROM dev_ota_commit WHERE device_id=?",f.run().device()))
                .containsEntry("decision","SAME_MODEL").containsEntry("committed_security_version",1L);
        var before=job(f.run());assertThat(acceptCommit(f,payload,java.time.Instant.now().minusSeconds(600)).name()).isEqualTo("REPLAY");
        assertThat(job(f.run())).usingRecursiveComparison().isEqualTo(before);
        assertThat(count(f,"ota_commit_permit")).isEqualTo(1);assertThat(count(f,"ota_commit_receipt")).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE target_id=? AND action='ota.job.commit' AND details->>'actorKind'='SYSTEM'",Long.class,before.get("id"))).isEqualTo(1);
        assertObjectExists(f.run().prepared().upload());
    }

    /** 目标是另一已发布模型时，来源CAS、历史与成功在同一真实事务完成。 */
    @Test void atomicallyBindsDifferentTargetModel() throws Exception {
        differentTarget=true;var f=confirming();reserveAndPublish(f);
        assertThat(acceptCommit(f,commit(f,Uuid7.generate()),java.time.Instant.now()).name()).isEqualTo("SUCCEEDED");
        assertThat(owner().queryForObject("SELECT thing_model_version_id FROM dev_device WHERE id=?",UUID.class,f.run().device())).isEqualTo(TARGET_MODEL);
        assertThat(owner().queryForMap("SELECT from_model_version_id,to_model_version_id FROM dev_device_model_binding_history WHERE device_id=?",f.run().device()))
                .containsEntry("from_model_version_id",f.run().prepared().fixture().modelId()).containsEntry("to_model_version_id",TARGET_MODEL);
    }

    /** 第一观察、早到第二观察和原字节重放均不能伪造稳定窗口或刷新期限。 */
    @Test void earlyAndRepeatedHealthNeverRefreshesDeadline() throws Exception {
        var f=healthy();var before=job(f.run());var now=java.time.Instant.now();byte[] first=health(f,1,10000,0);
        assertThat(acceptHealth(f,first,now).name()).isEqualTo("OBSERVED");
        assertThat(acceptHealth(f,first,now.minusSeconds(600)).name()).isEqualTo("REPLAY");
        assertThat(acceptHealth(f,health(f,2,10100,100),now.plusMillis(100)).name()).isEqualTo("OBSERVED");
        assertThat(job(f.run())).usingRecursiveComparison().isEqualTo(before);assertThat(count(f,"ota_commit_permit")).isZero();
    }

    /** 序号缺口、启动变化和健康时钟不连续必须进入恢复，不能签发许可。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"gap","boot","uptime"})
    void rejectsDiscontinuousHealth(String boundary) throws Exception {
        var f=healthy();var now=java.time.Instant.now();acceptHealth(f,health(f,1,10000,0),now);
        var body=mutableObject(health(f,"gap".equals(boundary)?3:2,11000,"uptime".equals(boundary)?999:1000));
        if("boot".equals(boundary))body.put("bootId",Uuid7.generate().toString());
        assertThat(acceptHealth(f,CANONICAL.writeObject(body),now.plusSeconds(1)).name()).isEqualTo("RECOVERY_REQUIRED");
        assertThat(job(f.run())).containsEntry("status","RECOVERY_REQUIRED");assertThat(count(f,"ota_commit_permit")).isZero();
    }

    /** 暂停和取消期间只积累真实健康观察，不签发新许可。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"pauses","cancellation"})
    void pausedOrCancellingCampaignCannotCreatePermit(String action) throws Exception {
        var f=healthy();var run=f.run();ok(send(run.prepared().fixture(),"POST",runtime(run)+"/"+action,key(),reason("2","健康阶段停止新增许可"),false),200);
        var first=java.time.Instant.now();acceptHealth(f,health(f,1,10000,0),first);waitUntil(first.plusMillis(1100));
        assertThat(acceptHealth(f,health(f,2,11100,1100),java.time.Instant.now()).name()).isEqualTo("OBSERVED");
        assertThat(count(f,"ota_commit_permit")).isZero();assertThat(job(run)).containsEntry("status","HEALTH_CHECKING");
    }

    /** 已预留许可后的取消保留设备提交责任，真实成功使取消由SYSTEM收束。 */
    @Test void acceptedPermitCanFinishCancelledCampaign() throws Exception {
        var f=confirming();reserveAndPublish(f);var run=f.run();
        ok(send(run.prepared().fixture(),"POST",runtime(run)+"/cancellation",key(),reason("2","已发许可继续收束"),false),200);
        assertThat(execution(run).path("status").asText()).isEqualTo("CANCELLING");
        assertThat(acceptCommit(f,commit(f,Uuid7.generate()),java.time.Instant.now()).name()).isEqualTo("SUCCEEDED");
        assertThat(execution(run).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(count(f,"ota_confirmation_cancellation")).isEqualTo(1);
    }

    /** 许可准备后暂停使旧投递能力失效，不能制造新发送预留。 */
    @Test void pauseRevokesUnsentPermitLease() throws Exception {
        var f=confirming();var claim=permitDelivery.claimOne().orElseThrow();var run=f.run();
        ok(send(run.prepared().fixture(),"POST",runtime(run)+"/pauses",key(),reason("2","禁止新发许可"),false),200);
        assertThat(permitDelivery.prepare(claim.permit().id(),claim.leaseToken())).isEmpty();assertThat(count(f,"ota_commit_transport")).isZero();
    }

    /** 已发生的提交不能因为发布随后被撤销而丢失安全事实。 */
    @Test void retainsExistingPermitCommitAfterReleaseRevocation() throws Exception {
        var f=confirming();reserveAndPublish(f);var run=f.run();var fixture=run.prepared().fixture();
        ok(send(fixture,"POST",base(fixture)+"/"+run.prepared().firmware()+"/revocations",key(),reason("2","关闭后续派发"),false),200);
        assertThat(acceptCommit(f,commit(f,Uuid7.generate()),java.time.Instant.now()).name()).isEqualTo("SUCCEEDED");
        assertThat(job(run)).containsEntry("status","SUCCEEDED");
    }

    /** 无真实发送预留和旧凭据都不能把设备报文变成提交事实。 */
    @Test void rejectsUnsentPermitAndRotatedCredential() throws Exception {
        var f=confirming();var payload=commit(f,Uuid7.generate());
        assertThatThrownBy(()->acceptCommit(f,payload,java.time.Instant.now())).isInstanceOf(IllegalArgumentException.class);
        reserveAndPublish(f);owner().update("UPDATE dev_device SET credential_version=2 WHERE id=?",f.run().device());
        for(long generation:List.of(1L,2L))assertThatThrownBy(()->confirmation.acceptCommit(identity(f.run().prepared().fixture(),f.run().device(),generation),payload,java.time.Instant.now())).isInstanceOf(IllegalArgumentException.class);
        assertThat(count(f,"dev_ota_commit")).isZero();assertThat(job(f.run())).containsEntry("status","CONFIRMING");
    }

    /** 不同许可或确认元组不能伪造成功与设备安全下限。 */
    @Test void rejectsWrongPermitAndQuarantinesWrongCommittedTuple() throws Exception {
        var f=confirming();reserveAndPublish(f);var wrong=mutableObject(commit(f,Uuid7.generate()));wrong.put("permitId",Uuid7.generate().toString());
        assertThatThrownBy(()->acceptCommit(f,CANONICAL.writeObject(wrong),java.time.Instant.now())).isInstanceOf(IllegalArgumentException.class);
        var bad=mutableObject(commit(f,Uuid7.generate()));evidence(bad).put("committedSecurityVersion",0L);
        assertThat(acceptCommit(f,CANONICAL.writeObject(bad),java.time.Instant.now()).name()).isEqualTo("RECOVERY_REQUIRED");
        assertThat(count(f,"dev_ota_commit")).isZero();
    }

    /** 验证较早Broker时刻确实产生全量日志中的观察分支，时钟屏障不能放宽此生产围栏。 */
    @Test void receiptBeforePermitCreationCannotAdoptModel() throws Exception {
        differentTarget=true;var f=confirming();var sent=reserveAndPublish(f);
        var earlier=sent.transport().permit().createdAt().minusNanos(1000);
        assertThat(acceptCommit(f,commit(f,Uuid7.generate()),earlier).name()).isEqualTo("RECOVERY_REQUIRED");
        assertThat(owner().queryForMap("SELECT decision,committed_security_version FROM dev_ota_commit WHERE device_id=?",f.run().device()))
                .containsEntry("decision","OBSERVED_ONLY").containsEntry("committed_security_version",1L);
        assertThat(owner().queryForObject("SELECT reason FROM ota_job_transition WHERE job_id=? AND to_status='RECOVERY_REQUIRED' ORDER BY to_revision DESC LIMIT 1",String.class,job(f.run()).get("id")))
                .isEqualTo("COMMITTED_OUTSIDE_PERMIT_WINDOW");
        assertThat(owner().queryForObject("SELECT thing_model_version_id FROM dev_device WHERE id=?",UUID.class,f.run().device()))
                .isEqualTo(f.run().prepared().fixture().modelId());
    }

    /** 原许可期限到达或超时先采用后，真实COMMITTED仍保留下限但不改模型。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void lateCommitPreservesFloorWithoutBinding(boolean timeoutWins) throws Exception {
        confirmingSeconds=3;differentTarget=true;var f=confirming();reserveAndPublish(f);var permit=permit(f);
        waitUntil(((java.sql.Timestamp)permit.get("deadline_at")).toInstant().plusMillis(150));
        if(timeoutWins){var claim=timeouts.claimOne().orElseThrow();assertThat(timeouts.expire(claim.context().jobId(),claim.token())).isTrue();}
        var outcome=acceptCommit(f,commit(f,Uuid7.generate()),java.time.Instant.now());
        assertThat(outcome.name()).isEqualTo(timeoutWins?"OBSERVED":"RECOVERY_REQUIRED");
        assertThat(job(f.run())).containsEntry("status","RECOVERY_REQUIRED");
        assertThat(owner().queryForMap("SELECT decision,committed_security_version FROM dev_ota_commit WHERE device_id=?",f.run().device()))
                .containsEntry("decision","OBSERVED_ONLY").containsEntry("committed_security_version",1L);
        assertThat(owner().queryForObject("SELECT thing_model_version_id FROM dev_device WHERE id=?",UUID.class,f.run().device())).isEqualTo(f.run().prepared().fixture().modelId());
    }

    /** 健康窗口完成前来源已被公开端口转换，不再签发不可逆提交许可。 */
    @Test void changedSourceBeforeHealthWindowRejectsNewPermit() throws Exception {
        var f=healthy();var first=java.time.Instant.now();
        assertThat(acceptHealth(f,health(f,1,10000,0),first).name()).isEqualTo("OBSERVED");
        changeSource(f);waitUntil(first.plusMillis(1100));
        assertThat(acceptHealth(f,health(f,2,11100,1100),java.time.Instant.now()).name()).isEqualTo("RECOVERY_REQUIRED");
        assertThat(job(f.run())).containsEntry("status","RECOVERY_REQUIRED");
        assertThat(count(f,"ota_commit_permit")).isZero();assertThat(count(f,"ota_commit_transport")).isZero();
        assertThat(count(f,"dev_ota_commit")).isZero();assertThat(count(f,"dev_device_model_binding_history")).isEqualTo(1);
    }

    /** 许可已固定但还没真实预留发送，来源转换使最终发送检查拒绝并安全暂停。 */
    @Test void changedSourceBeforePermitSendRejectsReservation() throws Exception {
        var f=confirming();var claim=permitDelivery.claimOne().orElseThrow();changeSource(f);
        assertThat(permitDelivery.prepare(claim.permit().id(),claim.leaseToken())).isEmpty();
        assertThat(count(f,"ota_commit_permit")).isEqualTo(1);assertThat(count(f,"ota_commit_transport")).isZero();
        assertThat(count(f,"dev_ota_commit")).isZero();assertThat(count(f,"dev_device_model_binding_history")).isEqualTo(1);
        assertThat(execution(f.run()).path("status").asText()).isEqualTo("PAUSED");
        assertThat(execution(f.run()).path("pauseKind").asText()).isEqualTo("SECURITY");
    }

    /** 通过真实公开绑定端口完成另一转换，不能只在数据库改指针制造无历史漂移。 */
    private UUID changeSource(ProgressFixture f){
        var fixture=f.run().prepared().fixture();UUID next=Uuid7.generate();insertModel(fixture,next,"3.0.0","e".repeat(64));
        var changed=scoped(fixture,()->modelBindings.bind(fixture.projectId(),f.run().device(),next,Uuid7.generate(),
                com.things.link.device.domain.ThingModelVersionRepository.BindingTransition.TransitionType.MANUAL,java.time.Instant.now()));
        assertThat(changed.fromVersionId()).isEqualTo(fixture.modelId());assertThat(changed.toVersionId()).isEqualTo(next);return next;
    }

    /** 另一真实模型转换先发生，提交下限与冲突恢复同事务保存且不覆盖当前指针。 */
    @Test void sourceConflictStillRetainsIrreversibleFloor() throws Exception {
        var f=confirming();reserveAndPublish(f);var fixture=f.run().prepared().fixture();var third=Uuid7.generate();insertModel(fixture,third,"3.0.0","e".repeat(64));
        owner().update("UPDATE dev_device SET thing_model_version_id=? WHERE id=?",third,f.run().device());
        assertThat(acceptCommit(f,commit(f,Uuid7.generate()),java.time.Instant.now()).name()).isEqualTo("RECOVERY_REQUIRED");
        assertThat(owner().queryForMap("SELECT decision,committed_security_version FROM dev_ota_commit WHERE device_id=?",f.run().device()))
                .containsEntry("decision","SOURCE_CONFLICT").containsEntry("committed_security_version",1L);
        assertThat(owner().queryForObject("SELECT thing_model_version_id FROM dev_device WHERE id=?",UUID.class,f.run().device())).isEqualTo(third);
    }

    /** 审计写后故障回滚设备和OTA成功图，原始消息可在CONFIRMING上重试。 */
    @Test void auditFailureRollsBackCommitAndAllowsOriginalRetry() throws Exception {
        differentTarget=true;var f=confirming();reserveAndPublish(f);byte[] payload=commit(f,Uuid7.generate());
        org.mockito.Mockito.doAnswer(invocation->{invocation.callRealMethod();throw new IllegalStateException("提交审计注入失败");}).when(audit).record(org.mockito.ArgumentMatchers.argThat(entry->"ota.job.commit".equals(entry.action())));
        assertThatThrownBy(()->acceptCommit(f,payload,java.time.Instant.now())).isInstanceOf(IllegalStateException.class).hasMessage("提交审计注入失败");
        assertThat(job(f.run())).containsEntry("status","CONFIRMING");assertThat(count(f,"dev_ota_commit")).isZero();assertThat(count(f,"ota_commit_receipt")).isZero();
        org.mockito.Mockito.doCallRealMethod().when(audit).record(org.mockito.ArgumentMatchers.any());
        assertThat(acceptCommit(f,payload,java.time.Instant.now()).name()).isEqualTo("SUCCEEDED");
    }

    /** 真实提交下限阻止后续旧能力报告重新声明更低计数器。 */
    @Test void committedFloorRejectsSubsequentOldCapabilityReport() throws Exception {
        var f=confirming();reserveAndPublish(f);acceptCommit(f,commit(f,Uuid7.generate()),java.time.Instant.now());var run=f.run();
        assertThatThrownBy(()->reportIngestion.accept(identity(run.prepared().fixture(),run.device(),1),report(run.prepared().fixture(),2,0,bundleHash(run.prepared().fixture())),java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        var response=ok(send(run.prepared().fixture(),"GET","/api/v1/projects/"+run.prepared().fixture().projectId()+"/ota/devices/"+run.device()+"/eligibility?firmwareId="+run.prepared().firmware(),null,null,false),200);
        assertThat(response.path("eligible").asBoolean()).isFalse();assertThat(response.path("reason").asText()).isEqualTo("INCOMPATIBLE");
    }

    /** 真实raw Kafka同时覆盖健康与提交路由，最终成功来自生产监听器事务。 */
    @Test void persistsRawHealthAndCommitThroughProductionKafka() throws Exception {
        var f=healthy();String topic=com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.RAW_UPLINK_TOPIC;
        try(var admin=org.apache.kafka.clients.admin.Admin.create(java.util.Map.of("bootstrap.servers",KAFKA.getBootstrapServers()))){
            var existing=admin.listTopics().names().get(10,java.util.concurrent.TimeUnit.SECONDS);var missing=new ArrayList<org.apache.kafka.clients.admin.NewTopic>();
            for(String name:List.of(topic,com.things.link.ingestion.infrastructure.UplinkKafkaConfiguration.DEAD_LETTER_TOPIC))
                if(!existing.contains(name))missing.add(new org.apache.kafka.clients.admin.NewTopic(name,3,(short)1));
            if(!missing.isEmpty())admin.createTopics(missing).all().get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        var container=listeners.getListenerContainers().stream().filter(value->"things-link-ingestion-raw".equals(value.getGroupId())).findFirst().orElseThrow();
        assertThat(container.isRunning()).isFalse();container.start();
        try{
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).until(()->container.getAssignedPartitions()!=null&&!container.getAssignedPartitions().isEmpty());
            var first=java.time.Instant.now();raw(f,"ota/health",health(f,1,10000,0),first);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(()->count(f,"ota_health_receipt")==1);
            waitUntil(first.plusMillis(1100));raw(f,"ota/health",health(f,2,11100,1100),java.time.Instant.now());
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(()->"CONFIRMING".equals(job(f.run()).get("status")));
            reserveAndPublish(f);byte[] payload=commit(f,Uuid7.generate());raw(f,"ota/commit/receipt",payload,java.time.Instant.now());
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(()->"SUCCEEDED".equals(job(f.run()).get("status")));
            assertThat(owner().queryForObject("SELECT payload_hash FROM ota_commit_receipt WHERE job_id=?",String.class,job(f.run()).get("id"))).isEqualTo(sha(payload));
            assertThat(count(f,"dev_ota_commit")).isEqualTo(1);
        }finally{var stopped=new java.util.concurrent.CountDownLatch(1);container.stop(stopped::countDown);assertThat(stopped.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    }
    /** 原Broker认证元组与接收时刻通过实际序列化器传给生产raw监听器。 */
    private void raw(ProgressFixture f,String kind,byte[] bytes,java.time.Instant received) throws Exception {
        var fixture=f.run().prepared().fixture();var message=new com.things.link.shared.message.RawUplinkMessage(fixture.tenantId(),fixture.projectId(),f.run().device(),
                "tc/v1/ota_test/device_test/up/"+kind,bytes,1,false,"test-client",received,UUID.randomUUID().toString(),identity(fixture,f.run().device(),1));
        kafka.send(com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.RAW_UPLINK_TOPIC,f.run().device().toString(),message).get(10,java.util.concurrent.TimeUnit.SECONDS);
    }

    /** 同活动另一作业持有的真实许可租约不能被第一个作业成功清除。 */
    @Test void oneSuccessfulJobPreservesOtherPermitLeaseInSameCampaign() throws Exception {
        var p=published();var fixture=p.fixture();UUID firstDevice=initialized(p),secondDevice=device(fixture);
        for(UUID device:List.of(firstDevice,secondDevice))reportIngestion.accept(identity(fixture,device,1),report(fixture,1,0,bundleHash(fixture)),java.time.Instant.now());
        UUID campaign=UUID.fromString(ok(send(fixture,"POST",campaigns(fixture),key(),plan(p.firmware(),List.of(firstDevice,secondDevice)),false),201).path("id").asText());
        String path=campaigns(fixture)+"/"+campaign;
        ok(send(fixture,"POST",path+"/scheduling",key(),revision("0"),false),200);ok(send(fixture,"POST",path+"/starting",key(),revision("1"),false),200);
        for(int i=0;i<2;i++){var claim=admission.claimOne().orElseThrow();assertThat(admission.admit(claim.jobId(),claim.token())).isTrue();}
        var a=healthyFor(new Running(p,firstDevice,campaign));var b=healthyFor(new Running(p,secondDevice,campaign));
        var first=java.time.Instant.now();acceptHealth(a,health(a,1,10000,0),first);acceptHealth(b,health(b,1,10000,0),first);
        waitUntil(first.plusMillis(1100));assertThat(acceptHealth(a,health(a,2,11100,1100),java.time.Instant.now()).name()).isEqualTo("CONFIRMING");
        assertThat(acceptHealth(b,health(b,2,11100,1100),java.time.Instant.now()).name()).isEqualTo("CONFIRMING");
        var one=permitDelivery.claimOne().orElseThrow();var two=permitDelivery.claimOne().orElseThrow();
        var aClaim=one.permit().deviceId().equals(firstDevice)?one:two;var bClaim=one.permit().deviceId().equals(secondDevice)?one:two;
        assertThat(aClaim.permit().deviceId()).isEqualTo(firstDevice);assertThat(bClaim.permit().deviceId()).isEqualTo(secondDevice);
        assertThat(permitDelivery.prepare(aClaim.permit().id(),aClaim.leaseToken())).isPresent();
        assertThat(acceptCommit(a,commit(a,Uuid7.generate()),java.time.Instant.now()).name()).isEqualTo("SUCCEEDED");
        assertThat(job(b.run())).containsEntry("status","CONFIRMING");
        assertThat(permitDelivery.prepare(bClaim.permit().id(),bClaim.leaseToken())).isPresent();
    }

    /** 绕过Java服务提交完整伪健康窗口，真实PG必须以23514拒绝错误制品并回滚整个图。 */
    @Test void databaseRejectsWrongArtifactHealthWindowWithoutPartialPermit() throws Exception {
        var f=healthy();var first=java.time.Instant.now();acceptHealth(f,health(f,1,10000,0),first);waitUntil(first.plusMillis(1100));
        var bad=mutableObject(health(f,2,11100,1100));evidence(bad).put("artifactSha256","f".repeat(64));
        var decoded=new com.things.link.ota.application.OtaHealthCodec().decode(CANONICAL.writeObject(bad));var value=decoded.value();
        assertThatThrownBy(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{
            var fixture=f.run().prepared().fixture();confirmationRls.establish(fixture.tenantId(),fixture.projectId());
            var context=confirmationProgress.locate(value.jobId()).orElseThrow();
            var now=jdbc.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant();
            UUID healthId=Uuid7.generate(),permitId=Uuid7.generate();var deadline=now.plusSeconds(confirmingSeconds);
            var valid=new com.things.link.ota.application.OtaHealthCodec().decode(health(f,2,11100,1100)).value().evidence().target();
            byte[] canonical=new com.things.link.ota.application.OtaCommitPermitCodec().encode(new com.things.link.ota.application.OtaCommitPermitCodec.Permit(
                    "tc-ota-commit-permit/v1",permitId,value.jobId(),1,f.authorization(),value.manifestSha256(),f.newBoot(),valid.securityVersion(),valid.artifactSha256(),valid.targetSlot(),deadline.getEpochSecond()));
            var receipt=new com.things.link.ota.domain.OtaHealthReceipt(healthId,fixture.tenantId(),fixture.projectId(),f.run().campaign(),value.jobId(),f.run().device(),
                    1,1,2,f.authorization(),value.manifestSha256(),f.newBoot(),decoded.canonical(),decoded.sha256(),now,now);
            var permit=new com.things.link.ota.domain.OtaCommitPermit(permitId,fixture.tenantId(),fixture.projectId(),f.run().campaign(),value.jobId(),f.run().device(),
                    1,1,f.authorization(),value.manifestSha256(),f.newBoot(),healthId,canonical,sha(canonical),now,deadline);
            confirmationRepository.acceptHealth(context,receipt,"CONFIRMING","HEALTH_WINDOW_CONFIRMED",permit);
            return true;
        })).satisfies(error->{
            Throwable root=error;while(root.getCause()!=null)root=root.getCause();
            assertThat(root).isInstanceOf(java.sql.SQLException.class);assertThat(((java.sql.SQLException)root).getSQLState()).isEqualTo("23514");
        });
        assertThat(count(f,"ota_health_receipt")).isEqualTo(1);assertThat(count(f,"ota_commit_permit")).isZero();assertThat(count(f,"ota_commit_delivery")).isZero();
        assertThat(job(f.run())).containsEntry("status","HEALTH_CHECKING");
    }

    /** 同一合法活动的另一个设备也通过真实申请、签址、发送预留及四阶段进入健康。 */
    private ProgressFixture healthyFor(Running run){
        assertThat(accept(run,request(run,Uuid7.generate(),1,null)).name()).isEqualTo("ACCEPTED");var signed=signing();
        assertThat(authorizations.seal(signed.claim().authorizationId(),signed.claim().leaseToken(),presign(signed))).isTrue();
        var sealed=claimSealed(signed);var sending=authorizations.prepareSend(sealed.authorizationId(),sealed.leaseToken()).orElseThrow();
        assertThat(authorizations.complete(sending.transport().id(),sending.transport().reservationToken(),accepted())).isTrue();
        var f=new ProgressFixture(run,signed.claim().authorizationId(),Uuid7.generate(),Uuid7.generate());
        long seq=1;for(String stage:List.of("VERIFYING","INSTALLING","REBOOTING","HEALTH_CHECKING"))apply(f,progress(f,seq++,stage));return f;
    }

    /** 成功后的新确认图和设备安全下限由生产租约批次清理，固定对象必须先真实物理删除。 */
    @Test void productionCleanupDrainsConfirmationAndDeviceCommitAfterPhysicalObjectDeletion() throws Exception {
        differentTarget=true;var f=confirming();reserveAndPublish(f);
        assertThat(acceptCommit(f,commit(f,Uuid7.generate()),java.time.Instant.now()).name()).isEqualTo("SUCCEEDED");
        var fixture=f.run().prepared().fixture();var upload=f.run().prepared().upload();UUID lease=Uuid7.generate();
        OtaCompletionSourceAssertions.verify(owner(),fixture.projectId());
        var completionSources=OtaCompletionSourceAssertions.sources(owner(),fixture.projectId());
        assertThat(count(f,"dev_ota_security_floor")).isEqualTo(1);assertThat(count(f,"dev_ota_commit")).isEqualTo(1);
        // owner仅准备已获批准且宽限期届满的项目回收前置；被测删除全部由APP生产入口执行。
        owner().update("""
                UPDATE sys_project SET status='PURGING',lifecycle_generation=1,deleted_at=now()-interval '31 days',cleanup_stage='OTA',
                cleanup_started_at=now(),cleanup_next_attempt_at=now(),cleanup_lease_token=?,cleanup_lease_until=clock_timestamp()+interval '120 seconds'
                WHERE id=?
                """,lease,fixture.projectId());
        var claim=new com.things.link.project.application.ProjectCleanupClaim(fixture.tenantId(),fixture.projectId(),1,"OTA",lease,java.time.Instant.now().plusSeconds(120),false);
        var invalid=new com.things.link.project.application.ProjectCleanupClaim(fixture.tenantId(),fixture.projectId(),1,"OTA",Uuid7.generate(),claim.leaseUntil(),false);
        assertThat(projectCleanupBatches.execute(invalid)).isEmpty();assertThat(count(f,"ota_commit_receipt")).isEqualTo(1);
        var initialClaim=claim;
        var first=scoped(fixture,()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->
                new com.things.link.ota.infrastructure.persistence.JdbcOtaProjectCleanupRepository(jdbc).clean(initialClaim)));
        assertThat(first.deletedRows()).isZero();assertThat(first.blockedReason()).isEqualTo("OTA_UPLOAD_CANCELLATION_REQUESTED");
        assertThat(count(f,"ota_commit_receipt")).isEqualTo(1);assertThat(count(f,"dev_ota_commit")).isEqualTo(1);assertObjectExists(upload);
        var recovery=uploads.claimRecovery().orElseThrow();assertThat(recovery.id()).isEqualTo(upload.id());
        scoped(fixture,()->{uploadRecovery.recover(recovery);return true;});
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware_upload_session WHERE id=?",String.class,upload.id())).isEqualTo("CLEANED");
        try(var admin=admin()){
            assertThat(admin.listObjects(ListObjectsArgs.builder().bucket(BUCKET).prefix(upload.objectKey()).includeVersions(true).recursive(true).build()).iterator().hasNext()).isFalse();
        }
        boolean deviceDone=false;boolean sawTelemetry=false;boolean sawDevice=false;
        for(int batch=0;batch<128;batch++){
            assertThat(claim.projectId()).isEqualTo(fixture.projectId());
            assertThat(owner().queryForObject("SELECT cleanup_lease_token=? AND cleanup_lease_until>clock_timestamp() FROM sys_project WHERE id=?",Boolean.class,claim.leaseToken(),fixture.projectId())).isTrue();
            String stage=claim.stage();sawTelemetry|="TELEMETRY".equals(stage);sawDevice|="DEVICE".equals(stage);
            var result=projectCleanupBatches.execute(claim).orElseThrow();
            assertThat(result.deletedRows()).isBetween(0,500);assertThat(result.blockedReason()).isNull();
            if("DEVICE".equals(stage)&&result.complete()){deviceDone=true;break;}
            claim=projectCleanupAdmission.claimNext().orElseThrow();
        }
        assertThat(deviceDone).isTrue();assertThat(sawTelemetry).isTrue();assertThat(sawDevice).isTrue();
        if (!Boolean.getBoolean("thingslink.ota.upgrade-test")) assertThat(count(f,"ota_job_completion")).isZero();
        assertThat(owner().queryForObject("SELECT cleanup_stage FROM sys_project WHERE id=?",String.class,fixture.projectId())).isEqualTo("IAM");
        for(String table:List.of("ota_health_receipt","ota_commit_permit","ota_commit_delivery","ota_commit_transport","ota_commit_receipt",
                "ota_confirmation_outbox","ota_confirmation_cancellation","ota_job_progress","ota_job_execution_origin","ota_device_job","ota_campaign",
                "ota_firmware_release","ota_firmware_upload_session","dev_ota_security_floor","dev_ota_commit","dev_device_model_binding_history","dev_device"))
            assertThat(count(f,table)).as(table).isZero();
        assertThat(OtaCompletionSourceAssertions.sources(owner(),fixture.projectId())).isEqualTo(completionSources);
        verifiedPurgedProjects.add(fixture.projectId());
    }

    /** 四个已认证阶段先到候选健康状态，不改持久deadline或来源快照。 */
    private ProgressFixture healthy() throws Exception {
        var f=downloading();long seq=1;for(String stage:List.of("VERIFYING","INSTALLING","REBOOTING","HEALTH_CHECKING"))apply(f,progress(f,seq++,stage));return f;
    }
    /** 真实时间跨过计划中一秒窗口，设备相对时钟同时增长。 */
    private ProgressFixture confirming() throws Exception {
        var f=healthy();var first=java.time.Instant.now();assertThat(acceptHealth(f,health(f,1,10000,0),first).name()).isEqualTo("OBSERVED");
        waitUntil(first.plusMillis(1100));assertThat(acceptHealth(f,health(f,2,11100,1100),java.time.Instant.now()).name()).isEqualTo("CONFIRMING");return f;
    }
    /** 同一许可经过真实持久发送预留及独立本地HTTP接受，不表示设备已收到。 */
    private com.things.link.ota.application.OtaCommitPermitDeliveryService.Prepared reserveAndPublish(ProgressFixture f){
        var claim=permitDelivery.claimOne().orElseThrow();var prepared=permitDelivery.prepare(claim.permit().id(),claim.leaseToken()).orElseThrow();
        var permit=prepared.transport().permit();var result=permitPublisher.publish(prepared.route(),permit.canonical(),
                java.time.Instant.ofEpochSecond(permit.deadlineAt().getEpochSecond()),prepared.leaseUntil(),Duration.ofMillis(300));
        assertThat(result.outcome()).isEqualTo(com.things.link.ota.application.OtaCommitPermitPublisher.Outcome.BROKER_ACCEPTED);
        assertThat(permitDelivery.complete(prepared.transport().id(),prepared.transport().reservationToken(),result)).isTrue();
        var envelope=JSON.readTree(LAST_BODY.get());
        assertThat(java.util.Base64.getDecoder().decode(envelope.path("payload").asText())).containsExactly(permit.canonical());
        assertThat(envelope.path("qos").asInt()).isEqualTo(1);assertThat(envelope.path("retain").asBoolean()).isFalse();
        assertThat(envelope.path("topic").asText()).endsWith("/down/ota/commit/permit");
        // 真实Broker时刻由JVM模拟，先满足数据库创建时刻的因果先后，不靠HTTP耗时掩盖两时钟偏差。
        waitUntil(permit.createdAt());
        return prepared;
    }
    /** 平铺健康证据只增加两个相对时钟字段。 */
    private byte[] health(ProgressFixture f,long seq,long uptime,long healthy){
        var body=mutableObject(progress(f,4,"HEALTH_CHECKING"));body.put("contractVersion","tc-ota-health/v1");body.remove("stage");body.remove("progressSeq");body.put("healthSeq",seq);
        evidence(body).put("uptimeMillis",uptime);evidence(body).put("healthyForMillis",healthy);return CANONICAL.writeObject(body);
    }
    /** 提交报文明确绑定持久许可和已安装目标，模拟认证设备软件协议。 */
    private byte[] commit(ProgressFixture f,UUID id){
        var body=mutableObject(progress(f,4,"HEALTH_CHECKING"));body.put("contractVersion","tc-ota-commit-receipt/v1");body.remove("stage");body.remove("progressSeq");
        body.put("receiptId",id.toString());body.put("permitId",permit(f).get("id").toString());evidence(body).put("committedSecurityVersion",1L);return CANONICAL.writeObject(body);
    }
    /** 合同解析值保持不可变；测试修改前递归复制对象和数组，不改变生产解析器语义。 */
    @SuppressWarnings("unchecked")
    private static java.util.Map<String,Object> mutableObject(byte[] bytes){
        return (java.util.Map<String,Object>)mutableValue(CANONICAL.parseObject(bytes));
    }
    /** 所有容器复制到测试自有可变实例，标量直接保留。 */
    private static Object mutableValue(Object value){
        if(value instanceof java.util.Map<?,?> map){
            var copied=new java.util.LinkedHashMap<String,Object>();
            map.forEach((key,item)->copied.put((String)key,mutableValue(item)));return copied;
        }
        if(value instanceof List<?> list){
            var copied=new ArrayList<Object>();for(Object item:list)copied.add(mutableValue(item));return copied;
        }
        return value;
    }
    /** 严格解析后的本地可修改证据对象。 */
    @SuppressWarnings("unchecked") private static java.util.Map<String,Object> evidence(java.util.Map<String,Object> body){return (java.util.Map<String,Object>)body.get("evidence");}
    /** 真实设备认证入口不借管理身份。 */
    private com.things.link.ota.application.OtaConfirmationIngestionService.Outcome acceptHealth(ProgressFixture f,byte[] body,java.time.Instant at){return confirmation.acceptHealth(identity(f.run().prepared().fixture(),f.run().device(),1),body,at);}
    /** 真实设备确认入口不借管理身份。 */
    private com.things.link.ota.application.OtaConfirmationIngestionService.Outcome acceptCommit(ProgressFixture f,byte[] body,java.time.Instant at){return confirmation.acceptCommit(identity(f.run().prepared().fixture(),f.run().device(),1),body,at);}
    /** 当前许可只读作断言，不制造替代许可。 */
    private static java.util.Map<String,Object> permit(ProgressFixture f){return owner().queryForMap("SELECT * FROM ota_commit_permit WHERE job_id=?",job(f.run()).get("id"));}
    /** 静态测试表名只统计本例范围。 */
    private static long count(ProgressFixture f,String table){return owner().queryForObject("SELECT count(*) FROM "+table+" WHERE project_id=?",Long.class,f.run().prepared().fixture().projectId());}
    /** 真实时钟等待，不修改持久租约或阶段期限。 */
    private static void waitUntil(java.time.Instant time){org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).until(()->java.time.Instant.now().isAfter(time));}
    /** 同类型真实不可变发布模型只作为受控夹具。 */
    private static void insertModel(Fixture f,UUID id,String version,String digest){owner().update("""
            INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,version_major,version_minor,version_patch,
            change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
            VALUES(?,?,?,?,?,?,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1','{"properties":{},"events":{},"commands":{}}'::jsonb,?,'PG_JSONB_TEXT_V1_SHA256')
            """,id,f.tenantId(),f.projectId(),f.typeId(),version,Integer.parseInt(version.substring(0,1)),digest);}

    /** 数据面入口只继承Broker认证身份，不创建管理账号。 */
    private void apply(ProgressFixture fixture,byte[] payload){progressService.accept(identity(fixture.run().prepared().fixture(),fixture.run().device(),1),payload,java.time.Instant.now());}
    /** 完整来源只读用于比较，不作为当前资格替代物。 */
    private static java.util.Map<String,Object> origin(Running run){return owner().queryForMap("SELECT * FROM ota_job_execution_origin WHERE campaign_id=? AND device_id=?",run.campaign(),run.device());}

    /** 实际申请及唯一签址封存后取得进度可引用的授权，不伪造DOWNLOADING。 */
    private ProgressFixture downloading() throws Exception {
        Running run=acceptedRequest();var signed=signing();
        assertThat(authorizations.seal(signed.claim().authorizationId(),signed.claim().leaseToken(),presign(signed))).isTrue();
        return new ProgressFixture(run,signed.claim().authorizationId(),Uuid7.generate(),Uuid7.generate());
    }
    /** 进度身份只引用真实冻结作业与授权，启动身份由本模拟设备会话保持。
     * @param run 真实运行图
     * @param authorization 持久封存授权
     * @param originalBoot 安装前启动身份
     * @param newBoot 新槽启动身份
     */
    private record ProgressFixture(Running run,UUID authorization,UUID originalBoot,UUID newBoot) { }
    /** 按已签名manifest及本例原始能力报告构造完整观察，不代表实机刷写证明。 */
    private byte[] progress(ProgressFixture fixture,long sequence,String stage){
        var run=fixture.run();var manifest=(java.util.Map<?,?>)CANONICAL.parseObject(run.prepared().body()).get("manifest");
        var evidence=new java.util.LinkedHashMap<String,Object>();
        for(String field:List.of("artifactSha256","artifactSize","securityVersion","thingModelVersionId","thingModelSchemaDigestAlgorithm","thingModelSchemaDigest","trustDomain"))evidence.put(field,manifest.get(field));
        evidence.put("committedSecurityVersion",0L);evidence.put("propertyProfile","TC_PROPERTY_COMPOSITE_V1");
        evidence.put("rootFingerprint",sha(ROOT.getPublic().getEncoded()));evidence.put("trustBundleVersion",1L);
        evidence.put("trustBundleSha256",bundleHash(run.prepared().fixture()));
        boolean healthy="HEALTH_CHECKING".equals(stage);evidence.put("sourceSlot","A");evidence.put("targetSlot","B");evidence.put("activeSlot",healthy?"B":"A");
        evidence.put("verification","VERIFYING".equals(stage)?"NOT_STARTED":"PASSED");
        evidence.put("bootVerified",healthy);evidence.put("selfTestPassed",healthy);evidence.put("watchdogHealthy",healthy);
        var dispatch=owner().queryForMap("SELECT job_id,manifest_sha256 FROM ota_job_dispatch_outbox WHERE campaign_id=? AND device_id=?",run.campaign(),run.device());
        return CANONICAL.writeObject(java.util.Map.of("contractVersion","tc-ota-job-progress/v1","jobId",dispatch.get("job_id").toString(),
                "attemptNo",1L,"progressSeq",sequence,"authorizationId",fixture.authorization().toString(),"manifestSha256",dispatch.get("manifest_sha256"),
                "stage",stage,"bootId",(healthy?fixture.newBoot():fixture.originalBoot()).toString(),"evidence",evidence));
    }

    /** 全业务前置再接纳不可变设备下载申请，授权头由数据库触发器创建。 */
    private Running acceptedRequest() throws Exception {Running run=dispatched();assertThat(accept(run,request(run,Uuid7.generate(),1,null)).name()).isEqualTo("ACCEPTED");return run;}
    /** 真实可信租约和完整资格预留。 */
    private com.things.link.ota.application.OtaDownloadAuthorizationService.Signing signing(){var claim=authorizations.claimOne().orElseThrow();return authorizations.prepareSigning(claim.authorizationId(),claim.leaseToken()).orElseThrow();}
    /** 封存撤销原签址能力；发送必须领取独立SEALED租约且旧能力明确无效。 */
    private com.things.link.ota.domain.OtaDownloadAuthorizationRepository.Claim claimSealed(
            com.things.link.ota.application.OtaDownloadAuthorizationService.Signing signed){
        assertThat(authorizations.prepareSend(signed.claim().authorizationId(),signed.claim().leaseToken())).isEmpty();
        var claim=authorizations.claimOne().orElseThrow();assertThat(claim.status()).isEqualTo("SEALED");
        assertThat(claim.authorizationId()).isEqualTo(signed.claim().authorizationId());
        assertThat(claim.leaseToken()).isNotEqualTo(signed.claim().leaseToken());return claim;
    }
    /** 固定版本真实签址严格在数据库事务之外，不更新签址期限。 */
    private URI presign(com.things.link.ota.application.OtaDownloadAuthorizationService.Signing signed){
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        return storage.presignGet(signed.version(),Duration.ofSeconds(signed.expiresAt().getEpochSecond()-java.time.Instant.now().getEpochSecond()),new com.things.link.support.storage.VersionedStorageControl(Duration.ofSeconds(5),()->false));
    }
    /** 只等待真实首次5秒退避，不修改生产期限、修订或预算。 */
    private com.things.link.ota.domain.OtaDownloadAuthorizationRepository.Claim awaitClaim(){
        var found=new java.util.concurrent.atomic.AtomicReference<com.things.link.ota.domain.OtaDownloadAuthorizationRepository.Claim>();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(()->{var value=authorizations.claimOne();value.ifPresent(found::set);return value.isPresent();});return found.get();
    }
    /** owner仅观察本活动持久授权事实。 */
    private static java.util.Map<String,Object> authorization(Running run){return owner().queryForMap("SELECT * FROM ota_download_authorization WHERE campaign_id=? AND device_id=?",run.campaign(),run.device());}
    /** 接收端HTTP接受不是设备接收证明。 */
    private static com.things.link.ota.application.OtaDownloadResponsePublisher.Result accepted(){return new com.things.link.ota.application.OtaDownloadResponsePublisher.Result(com.things.link.ota.application.OtaDownloadResponsePublisher.Outcome.BROKER_ACCEPTED,202,"HTTP_ACCEPTED");}
    /** 固定外部失败观察，不携带供应商响应正文。 */
    private static com.things.link.ota.application.OtaDownloadResponsePublisher.Result rejected(){return new com.things.link.ota.application.OtaDownloadResponsePublisher.Result(com.things.link.ota.application.OtaDownloadResponsePublisher.Outcome.REJECTED,503,"HTTP_REJECTED");}

    /** 持久业务结果来自真实数据面事务代理。 */
    private com.things.link.ota.application.OtaDownloadRequestIngestionService.Outcome accept(Running run,byte[] body){return requests.accept(identity(run.prepared().fixture(),run.device(),1),body,java.time.Instant.now());}
    /** 永久不合格精确断言，不吞基础设施异常。 */
    private void assertInvalid(Running run,byte[] body){assertThatThrownBy(()->accept(run,body)).isInstanceOf(IllegalArgumentException.class);}
    /** 只读完整作业用于断言阶段与期限不被接纳更新。 */
    private static java.util.Map<String,Object> job(Running run){return owner().queryForMap("SELECT * FROM ota_device_job WHERE campaign_id=? AND device_id=?",run.campaign(),run.device());}
    /** 不可变申请全部字段，bytea值按数组内容比较。 */
    private static java.util.Map<String,Object> receipt(Running run){return owner().queryForMap("SELECT * FROM ota_download_request WHERE campaign_id=? AND device_id=?",run.campaign(),run.device());}
    /** 当前活动接纳数量。 */
    private static long requestCount(Running run){return owner().queryForObject("SELECT count(*) FROM ota_download_request WHERE campaign_id=?",Long.class,run.campaign());}
    /** 正文五字段只用原通知非秘密身份，无认证自报字段。 */
    private static byte[] request(Running run,UUID id,int attempt,String hash){
        var outbox=owner().queryForMap("SELECT job_id,manifest_sha256 FROM ota_job_dispatch_outbox WHERE campaign_id=? AND device_id=?",run.campaign(),run.device());
        return CANONICAL.writeObject(java.util.Map.of("contractVersion","tc-ota-download-request/v1","requestId",id.toString(),"jobId",outbox.get("job_id").toString(),"attemptNo",(long)attempt,"manifestSha256",hash==null?outbox.get("manifest_sha256"):hash));
    }

    /** 完整业务前置生成真正DISPATCHED通知意图，不直接插入delivery。 */
    private Running dispatched() throws Exception {
        Running run=running(true);var claim=admission.claimOne().orElseThrow();assertThat(admission.admit(claim.jobId(),claim.token())).isTrue();return run;
    }
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
        policy.put("downloadRetryLimit",2L);policy.put("retryBackoffSeconds",5L);policy.put("healthWindowSeconds",1L);
        policy.put("pauseMinEvaluated",2L);policy.put("pauseFailureCount",1L);policy.put("pauseFailureRateBps",5000L);
        policy.put("batchMinSuccessRateBps",10000L);policy.put("requireManualBatchApproval",true);
        var stages=new java.util.LinkedHashMap<String,Object>();
        for(String name:List.of("DISPATCHED","DOWNLOADING","VERIFYING","INSTALLING","REBOOTING","HEALTH_CHECKING","CONFIRMING","ROLLBACK_PENDING","ROLLING_BACK")) stages.put(name,"DISPATCHED".equals(name)?dispatchedSeconds:List.of("VERIFYING","INSTALLING").contains(name)?progressSeconds:"CONFIRMING".equals(name)?confirmingSeconds:120L);
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
        if(differentTarget) insertModel(fixture,TARGET_MODEL,"2.0.0","d".repeat(64));
        JsonNode firmware = ok(send(fixture, "POST", base(fixture), key(), body(fixture, "publication-v1").replace(fixture.modelId().toString(),(differentTarget?TARGET_MODEL:fixture.modelId()).toString()).getBytes(StandardCharsets.UTF_8), false), 201);
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
        manifest.put("thingModelVersionId", (differentTarget?TARGET_MODEL:fixture.modelId()).toString()); manifest.put("thingModelSchemaDigestAlgorithm", "PG_JSONB_TEXT_V1_SHA256");
        manifest.put("thingModelSchemaDigest", (differentTarget?"d":"a").repeat(64)); manifest.put("allowedSourceThingModelVersionIds", List.of(fixture.modelId().toString()));
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
    /** 测试全部结束后销毁自有容器。 */ @AfterAll static void stopStorage() { MINIO.stop(); BROKER.stop(0); }
    /** 新OTA引用必须先清理，再删除本例模型与项目；不修改触发器或禁用外键。 */
    @AfterEach
    void clearOwnedFacts() throws Exception {
        try {
            for (Fixture fixture : fixtures) {
                if (!verifiedPurgedProjects.contains(fixture.projectId()))
                    OtaCompletionSourceAssertions.verify(owner(),fixture.projectId());
            }
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
                for(String table:List.of("ota_confirmation_cancellation","ota_confirmation_outbox","ota_commit_receipt","ota_commit_transport","ota_commit_delivery","ota_commit_permit","ota_health_receipt","ota_job_progress_outbox","ota_job_progress","ota_job_expiry","ota_job_execution_origin","ota_download_transport","ota_download_authorization_outbox","ota_download_authorization","ota_download_bandwidth","ota_download_request_outbox","ota_download_request","ota_notification_transport","ota_notification_delivery","ota_job_dispatch_outbox","ota_job_transition","ota_batch_transition",
                        "ota_campaign_outbox","ota_campaign_transition","ota_device_job","ota_campaign_batch",
                        "ota_campaign_creation_request","ota_campaign_runtime_cancellation","ota_campaign")) owner.update("DELETE FROM "+table+" WHERE project_id=?",fixture.projectId());
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
