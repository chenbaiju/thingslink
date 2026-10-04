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

/** 真实PG、MinIO、HTTP和raw Kafka验证原来源与认证进度，不冒充设备实际刷写证据。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(OtaJobProgressIntegrationTests.SigningConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OtaJobProgressIntegrationTests extends com.things.link.testing.AbstractKafkaIntegrationTest {
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
    private static final String BUCKET = "ota-progress-http-" + UUID.randomUUID();
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
        registry.add("things-link.storage.internal-endpoint", OtaJobProgressIntegrationTests::endpoint);
        registry.add("things-link.storage.external-endpoint", OtaJobProgressIntegrationTests::endpoint);
        registry.add("things-link.storage.access-key", () -> ACCESS);
        registry.add("things-link.storage.secret-key", () -> SECRET);
        registry.add("things-link.ota.storage.bucket", () -> BUCKET);
        registry.add("things-link.ota.type-baselines-json", OtaJobProgressIntegrationTests::baselineSource);
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
    @org.junit.jupiter.api.BeforeEach void resetSigner() { releaseNotAfter = 253402300799L; signer.calls.set(0); signer.beforeReturn = () -> { }; signer.unknown = false; signer.corrupt = false; dispatchedSeconds=120; progressSeconds=120; BROKER_STATUS.set(202); LAST_BODY.set(null); org.mockito.Mockito.doCallRealMethod().when(responseCipher).configured(); }

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

    /** 正常完整序列使用原来源和平台期限，不把健康检查观察当作成功。 */
    @Test void advancesAuthenticatedStagesAndKeepsOriginalOrigin() throws Exception {
        var fixture=downloading();var origin=origin(fixture.run());
        for(var stage:List.of("VERIFYING","INSTALLING","REBOOTING","HEALTH_CHECKING")){
            long sequence=List.of("VERIFYING","INSTALLING","REBOOTING","HEALTH_CHECKING").indexOf(stage)+1;
            apply(fixture,progress(fixture,sequence,stage));assertThat(job(fixture.run())).containsEntry("status",stage);
            assertThat(job(fixture.run()).get("deadline_at")).isNotNull();
        }
        assertThat(origin(fixture.run())).usingRecursiveComparison().isEqualTo(origin);
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE target_id=? AND action='ota.job.progress' AND actor_account_id IS NULL AND details->>'actorKind'='SYSTEM'",Long.class,job(fixture.run()).get("id"))).isEqualTo(4);
        assertThat(execution(fixture.run()).path("status").asText()).isEqualTo("RUNNING");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE campaign_id=? AND to_status='SUCCEEDED'",Long.class,fixture.run().campaign())).isZero();
        assertObjectExists(fixture.run().prepared().upload());
    }

    /** 后续能力报告替换当前头时，派发原canonical和修订依然不可变。 */
    @Test void laterReportDoesNotOverwriteExecutionOrigin() throws Exception {
        Running run=dispatched();var before=origin(run);Fixture f=run.prepared().fixture();
        var updated=new java.util.LinkedHashMap<>(CANONICAL.parseObject(report(f,2,0,bundleHash(f))));updated.put("availableRamBytes",512L);
        reportIngestion.accept(identity(f,run.device(),1),CANONICAL.writeObject(updated),java.time.Instant.now());
        assertThat(origin(run)).usingRecursiveComparison().isEqualTo(before);
        assertThat(accept(run,request(run,Uuid7.generate(),1,null)).name()).isEqualTo("ACCEPTED");
    }

    /** 原活动槽漂移即使普通能力仍合格也不能作为同一次执行的新来源。 */
    @Test void originSlotDriftRejectsNewDownloadRequest() throws Exception {
        Running run=dispatched();var before=origin(run);Fixture f=run.prepared().fixture();
        var updated=new java.util.LinkedHashMap<>(CANONICAL.parseObject(report(f,2,0,bundleHash(f))));updated.put("activeSlot","B");
        reportIngestion.accept(identity(f,run.device(),1),CANONICAL.writeObject(updated),java.time.Instant.now());
        assertThat(accept(run,request(run,Uuid7.generate(),1,null)).name()).isEqualTo("SECURITY_PAUSED");
        assertThat(execution(run).path("pauseKind").asText()).isEqualTo("SECURITY");
        assertThat(requestCount(run)).isZero();assertThat(origin(run)).usingRecursiveComparison().isEqualTo(before);
        String pausedRevision=execution(run).path("stateVersion").asText();
        error(send(f,"POST",runtime(run)+"/resumptions",key(),reason(pausedRevision,"不能绕过原执行来源漂移"),false),409,70040);
        assertThat(execution(run).path("pauseKind").asText()).isEqualTo("SECURITY");
    }

    /** 同序同正文只读重放，陈旧Broker重投不刷新原阶段期限或追加转换。 */
    @Test void exactSequenceReplayDoesNotRefreshDeadline() throws Exception {
        var fixture=downloading();byte[] payload=progress(fixture,1,"VERIFYING");apply(fixture,payload);var before=job(fixture.run());
        progressService.accept(identity(fixture.run().prepared().fixture(),fixture.run().device(),1),payload,java.time.Instant.now().minusSeconds(600));
        assertThat(job(fixture.run())).usingRecursiveComparison().isEqualTo(before);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE job_id=? AND to_status='VERIFYING'",Long.class,before.get("id"))).isEqualTo(1);
        var altered=new java.util.LinkedHashMap<>(CANONICAL.parseObject(payload));altered.put("bootId",Uuid7.generate().toString());
        assertThatThrownBy(()->apply(fixture,CANONICAL.writeObject(altered))).isInstanceOf(IllegalArgumentException.class);
        assertThat(job(fixture.run())).usingRecursiveComparison().isEqualTo(before);
    }

    /** 经认证的序号缺口或不一致证据保守进入恢复，不能猜补安全阶段。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"SEQUENCE_GAP","EVIDENCE_MISMATCH"})
    void quarantinesAuthenticatedGapOrContradictoryProof(String kind) throws Exception {
        var fixture=downloading();byte[] payload=progress(fixture,"SEQUENCE_GAP".equals(kind)?2:1,"VERIFYING");
        if("EVIDENCE_MISMATCH".equals(kind)){
            var outer=new java.util.LinkedHashMap<>(CANONICAL.parseObject(payload));var original=(java.util.Map<?,?>)outer.get("evidence");
            var evidence=new java.util.LinkedHashMap<String,Object>();original.forEach((key,value)->evidence.put((String)key,value));evidence.put("artifactSha256","b".repeat(64));outer.put("evidence",evidence);payload=CANONICAL.writeObject(outer);
        }
        apply(fixture,payload);assertThat(job(fixture.run())).containsEntry("status","RECOVERY_REQUIRED");
        assertThat(execution(fixture.run()).path("pauseKind").asText()).isEqualTo("SECURITY");
        assertThat(admission.claimOne()).isEmpty();assertThat(authorizations.claimOne()).isEmpty();
    }

    /** 缺口进入恢复后迟到未见旧序仍保留观察，不能推进或降低最大序号。 */
    @Test void recordsUnseenLateSequenceWithoutReopeningRecovery() throws Exception {
        var fixture=downloading();apply(fixture,progress(fixture,1,"VERIFYING"));apply(fixture,progress(fixture,3,"INSTALLING"));
        var before=job(fixture.run());assertThat(before).containsEntry("status","RECOVERY_REQUIRED");
        byte[] late=progress(fixture,2,"INSTALLING");var identity=identity(fixture.run().prepared().fixture(),fixture.run().device(),1);
        assertThat(progressService.accept(identity,late,java.time.Instant.now()).name()).isEqualTo("OBSERVED");
        assertThat(job(fixture.run())).usingRecursiveComparison().isEqualTo(before);
        assertThat(owner().queryForObject("SELECT max(progress_seq) FROM ota_job_progress WHERE job_id=?",Long.class,before.get("id"))).isEqualTo(3);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_progress WHERE job_id=?",Long.class,before.get("id"))).isEqualTo(3);
        var observed=owner().queryForMap("SELECT * FROM ota_job_progress WHERE job_id=? AND progress_seq=2",before.get("id"));
        assertThat(observed).containsEntry("adopted_revision",null).containsEntry("adopted_status",null);
        assertThat(progressService.accept(identity,late,java.time.Instant.now().minusSeconds(600)).name()).isEqualTo("REPLAY");
        assertThat(owner().queryForMap("SELECT * FROM ota_job_progress WHERE job_id=? AND progress_seq=2",before.get("id"))).usingRecursiveComparison().isEqualTo(observed);
        assertThat(job(fixture.run())).usingRecursiveComparison().isEqualTo(before);
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE target_id=? AND action='ota.job.progress' AND details->>'progressSeq'='2'",Long.class,before.get("id"))).isEqualTo(1);
    }

    /** 已暂停或取消活动继续接收必要安全观察，不能要求设备在危险区立即停机。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"PAUSED","CANCELLING"})
    void preservesSafetyProgressAfterPauseOrCancellation(String expected) throws Exception {
        var fixture=downloading();Running run=fixture.run();String action="PAUSED".equals(expected)?"pauses":"cancellation";
        ok(send(run.prepared().fixture(),"POST",runtime(run)+"/"+action,key(),reason("2","暂停后仍接纳安全进度"),false),200);
        apply(fixture,progress(fixture,1,"VERIFYING"));apply(fixture,progress(fixture,2,"INSTALLING"));
        assertThat(job(run)).containsEntry("status","INSTALLING");assertThat(execution(run).path("status").asText()).isEqualTo(expected);
        assertThat(authorizations.claimOne()).isEmpty();assertObjectExists(run.prepared().upload());
    }

    /** 撤销发布关闭新授权但不能丢弃设备已经发生的必要安全观察。 */
    @Test void acceptsSafetyObservationAfterReleaseRevocation() throws Exception {
        var fixture=downloading();var run=fixture.run();Fixture f=run.prepared().fixture();
        ok(send(f,"POST","/api/v1/projects/"+f.projectId()+"/ota/firmwares/"+run.prepared().firmware()+"/revocations",key(),reason("2","撤销后仍须收集安全证据"),false),200);
        apply(fixture,progress(fixture,1,"VERIFYING"));assertThat(job(run)).containsEntry("status","VERIFYING");
        assertThat(execution(run).path("status").asText()).isEqualTo("PAUSED");assertThat(authorizations.claimOne()).isEmpty();
    }

    /** 旧凭据和新的凭据都不能自动接管原attempt的进度身份。 */
    @Test void rejectsRotatedIdentityWithoutRewritingOldAttempt() throws Exception {
        var fixture=downloading();byte[] payload=progress(fixture,1,"VERIFYING");var run=fixture.run();
        owner().update("UPDATE dev_device SET credential_version=2 WHERE id=?",run.device());
        for(long generation:List.of(1L,2L))assertThatThrownBy(()->progressService.accept(identity(run.prepared().fixture(),run.device(),generation),payload,java.time.Instant.now())).isInstanceOf(IllegalArgumentException.class);
        assertThat(job(run)).containsEntry("status","DOWNLOADING");
    }

    /** 真实SYSTEM审计写后故障回滚已接纳序号和转换，原消息仍可重试。 */
    @Test void rollsBackProgressWhenAuditFails() throws Exception {
        var fixture=downloading();byte[] payload=progress(fixture,1,"VERIFYING");var before=job(fixture.run());
        org.mockito.Mockito.doAnswer(invocation->{invocation.callRealMethod();throw new IllegalStateException("测试进度审计写后失败");}).when(audit).record(org.mockito.ArgumentMatchers.argThat(entry->"ota.job.progress".equals(entry.action())));
        assertThatThrownBy(()->apply(fixture,payload)).isInstanceOf(IllegalStateException.class).hasMessage("测试进度审计写后失败");
        assertThat(job(fixture.run())).usingRecursiveComparison().isEqualTo(before);
        org.mockito.Mockito.doCallRealMethod().when(audit).record(org.mockito.ArgumentMatchers.any());
        apply(fixture,payload);assertThat(job(fixture.run())).containsEntry("status","VERIFYING");
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE target_id=? AND action='ota.job.progress' AND details->>'decision'='AUTHENTICATED_PROGRESS'",Long.class,before.get("id"))).isEqualTo(1);
    }

    /** Broker及时观察先提交时，已经领取的旧阶段超时能力永久失效。 */
    @Test void timelyProgressWinsAgainstClaimedOldDeadline() throws Exception {
        progressSeconds=2;var fixture=downloading();apply(fixture,progress(fixture,1,"VERIFYING"));
        var timely=java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var claim=awaitExpiry();assertThat(claim.context().jobId()).isEqualTo(job(fixture.run()).get("id"));
        progressService.accept(identity(fixture.run().prepared().fixture(),fixture.run().device(),1),progress(fixture,2,"INSTALLING"),timely);
        assertThat(job(fixture.run())).containsEntry("status","INSTALLING");
        assertThat(timeouts.expire(claim.context().jobId(),claim.token())).isFalse();
        assertThat(job(fixture.run())).containsEntry("status","INSTALLING");
    }

    /** 刷写状态超时先提交后普通进度不能重开，不重发attempt或猜造终态。 */
    @Test void installingTimeoutKeepsRecoveryResponsibilityWithoutRedispatch() throws Exception {
        progressSeconds=2;var fixture=downloading();apply(fixture,progress(fixture,1,"VERIFYING"));apply(fixture,progress(fixture,2,"INSTALLING"));
        var before=job(fixture.run());var timely=java.time.Instant.now();var claim=awaitExpiry();
        assertThat(timeouts.expire(claim.context().jobId(),claim.token())).isTrue();
        assertThat(timeouts.expire(claim.context().jobId(),claim.token())).isFalse();
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE target_id=? AND action='ota.job.timeout' AND actor_account_id IS NULL AND details->>'reason'='STAGE_DEADLINE_EXCEEDED'",Long.class,claim.context().jobId())).isEqualTo(1);
        assertThat(job(fixture.run())).containsEntry("status","RECOVERY_REQUIRED").containsEntry("deadline_at",before.get("deadline_at")).containsEntry("attempt_no",before.get("attempt_no"));
        progressService.accept(identity(fixture.run().prepared().fixture(),fixture.run().device(),1),progress(fixture,3,"REBOOTING"),timely);
        assertThat(job(fixture.run())).containsEntry("status","RECOVERY_REQUIRED");assertThat(admission.claimOne()).isEmpty();assertThat(authorizations.claimOne()).isEmpty();
        assertThat(execution(fixture.run()).path("pauseKind").asText()).isEqualTo("SECURITY");assertObjectExists(fixture.run().prepared().upload());
    }

    /** Broker实际接收已过阶段期限时不能用新的平台处理时间重新开启阶段。 */
    @Test void lateBrokerObservationRequiresRecovery() throws Exception {
        progressSeconds=2;var fixture=downloading();apply(fixture,progress(fixture,1,"VERIFYING"));awaitExpiry();
        apply(fixture,progress(fixture,2,"INSTALLING"));assertThat(job(fixture.run())).containsEntry("status","RECOVERY_REQUIRED");
    }

    /** 等待真实数据库到期领取，不改写任何不可变阶段时间或CAS版本。 */
    private com.things.link.ota.domain.OtaJobProgressRepository.ExpiryClaim awaitExpiry(){
        var found=new java.util.concurrent.atomic.AtomicReference<com.things.link.ota.domain.OtaJobProgressRepository.ExpiryClaim>();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).until(()->{var value=timeouts.claimOne();value.ifPresent(found::set);return value.isPresent();});return found.get();
    }

    /** 实际Kafka序列化和生产raw监听器最终写PG，不以手动调用consumer替代消息链。 */
    @Test
    void persistsAuthenticatedRawProgressThroughProductionListener() throws Exception {
        var progressFixture=downloading();Fixture fixture=progressFixture.run().prepared().fixture();UUID device=progressFixture.run().device();
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
            byte[] payload = progress(progressFixture,1,"VERIFYING");
            var received = java.time.Instant.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var raw = new com.things.link.shared.message.RawUplinkMessage(fixture.tenantId(), fixture.projectId(), device,
                    "tc/v1/ota_test/device_test/up/ota/progress", payload, 1, false, "test-client", received,
                    UUID.randomUUID().toString(), identity(fixture, device, 1));
            kafka.send(topic, device.toString(), raw).get(10, java.util.concurrent.TimeUnit.SECONDS);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_progress WHERE job_id=?", Long.class, job(progressFixture.run()).get("id"))).isEqualTo(1L));
            assertThat(owner().queryForMap("SELECT credential_version,progress_seq,payload_hash,broker_received_at FROM ota_job_progress WHERE job_id=?",job(progressFixture.run()).get("id")))
                    .containsEntry("credential_version",1L).containsEntry("progress_seq",1L).containsEntry("payload_hash",sha(payload))
                    .containsEntry("broker_received_at",java.sql.Timestamp.from(received));
            assertThat(job(progressFixture.run())).containsEntry("status","VERIFYING");
        } finally {
            var stopped = new java.util.concurrent.CountDownLatch(1); container.stop(stopped::countDown);
            assertThat(stopped.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 数据面入口只继承Broker认证身份，不创建管理账号。 */
    private void apply(ProgressFixture fixture,byte[] payload){progressService.accept(identity(fixture.run().prepared().fixture(),fixture.run().device(),1),payload,java.time.Instant.now());}
    /** 完整来源只读用于比较，不作为当前资格替代物。 */
    private static java.util.Map<String,Object> origin(Running run){return owner().queryForMap("SELECT * FROM ota_job_execution_origin WHERE campaign_id=?",run.campaign());}

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
        var dispatch=owner().queryForMap("SELECT job_id,manifest_sha256 FROM ota_job_dispatch_outbox WHERE campaign_id=?",run.campaign());
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
    private static java.util.Map<String,Object> authorization(Running run){return owner().queryForMap("SELECT * FROM ota_download_authorization WHERE campaign_id=?",run.campaign());}
    /** 接收端HTTP接受不是设备接收证明。 */
    private static com.things.link.ota.application.OtaDownloadResponsePublisher.Result accepted(){return new com.things.link.ota.application.OtaDownloadResponsePublisher.Result(com.things.link.ota.application.OtaDownloadResponsePublisher.Outcome.BROKER_ACCEPTED,202,"HTTP_ACCEPTED");}
    /** 固定外部失败观察，不携带供应商响应正文。 */
    private static com.things.link.ota.application.OtaDownloadResponsePublisher.Result rejected(){return new com.things.link.ota.application.OtaDownloadResponsePublisher.Result(com.things.link.ota.application.OtaDownloadResponsePublisher.Outcome.REJECTED,503,"HTTP_REJECTED");}

    /** 持久业务结果来自真实数据面事务代理。 */
    private com.things.link.ota.application.OtaDownloadRequestIngestionService.Outcome accept(Running run,byte[] body){return requests.accept(identity(run.prepared().fixture(),run.device(),1),body,java.time.Instant.now());}
    /** 永久不合格精确断言，不吞基础设施异常。 */
    private void assertInvalid(Running run,byte[] body){assertThatThrownBy(()->accept(run,body)).isInstanceOf(IllegalArgumentException.class);}
    /** 只读完整作业用于断言阶段与期限不被接纳更新。 */
    private static java.util.Map<String,Object> job(Running run){return owner().queryForMap("SELECT * FROM ota_device_job WHERE campaign_id=?",run.campaign());}
    /** 不可变申请全部字段，bytea值按数组内容比较。 */
    private static java.util.Map<String,Object> receipt(Running run){return owner().queryForMap("SELECT * FROM ota_download_request WHERE campaign_id=?",run.campaign());}
    /** 当前活动接纳数量。 */
    private static long requestCount(Running run){return owner().queryForObject("SELECT count(*) FROM ota_download_request WHERE campaign_id=?",Long.class,run.campaign());}
    /** 正文五字段只用原通知非秘密身份，无认证自报字段。 */
    private static byte[] request(Running run,UUID id,int attempt,String hash){
        var outbox=owner().queryForMap("SELECT job_id,manifest_sha256 FROM ota_job_dispatch_outbox WHERE campaign_id=?",run.campaign());
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
        policy.put("downloadRetryLimit",2L);policy.put("retryBackoffSeconds",5L);policy.put("healthWindowSeconds",60L);
        policy.put("pauseMinEvaluated",2L);policy.put("pauseFailureCount",1L);policy.put("pauseFailureRateBps",5000L);
        policy.put("batchMinSuccessRateBps",10000L);policy.put("requireManualBatchApproval",true);
        var stages=new java.util.LinkedHashMap<String,Object>();
        for(String name:List.of("DISPATCHED","DOWNLOADING","VERIFYING","INSTALLING","REBOOTING","HEALTH_CHECKING","CONFIRMING","ROLLBACK_PENDING","ROLLING_BACK")) stages.put(name,"DISPATCHED".equals(name)?dispatchedSeconds:List.of("VERIFYING","INSTALLING").contains(name)?progressSeconds:120L);
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
    /** 测试全部结束后销毁自有容器。 */ @AfterAll static void stopStorage() { MINIO.stop(); BROKER.stop(0); }
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
            new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(owner.getDataSource())).executeWithoutResult(status -> {
                for(String table:List.of("ota_job_progress_outbox","ota_job_progress","ota_job_expiry","ota_job_execution_origin","ota_download_transport","ota_download_authorization_outbox","ota_download_authorization","ota_download_bandwidth","ota_download_request_outbox","ota_download_request","ota_notification_transport","ota_notification_delivery","ota_job_dispatch_outbox","ota_job_transition","ota_batch_transition",
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
