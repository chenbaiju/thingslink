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

/** 真实PG、HTTP及固定MinIO发布验证显式活动排程、预占和未派发取消。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(OtaCampaignHttpIntegrationTests.SigningConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OtaCampaignHttpIntegrationTests extends AbstractIntegrationTest {
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
    private static final String ACCESS = "campaign-http-test", SECRET = "campaign-http-secret";
    /** 本测试唯一桶，不触碰开发存储。 */
    private static final String BUCKET = "campaign-http-" + UUID.randomUUID();
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
        registry.add("things-link.storage.internal-endpoint", OtaCampaignHttpIntegrationTests::endpoint);
        registry.add("things-link.storage.external-endpoint", OtaCampaignHttpIntegrationTests::endpoint);
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

    /** 仅目标活动审计写入后注入失败，仍执行真实INSERT以证明事务回滚。 */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.things.link.support.audit.AuditLogService audit;

    /** 明确无模型、凭据和报告也可冻结，稳定批次不等于已经派发。 */
    @Test
    void schedulesCompleteExplicitTargetsAndCancelsWithoutDispatching() throws Exception {
        Prepared p=published(); Fixture f=p.fixture(); var devices=devices(f,3);
        byte[] plan=plan(p.firmware(),devices); String creationKey=key();
        var creation=send(f,"POST",campaigns(f),creationKey,plan,false);
        var draft=ok(creation,201); UUID id=UUID.fromString(draft.path("id").asText());
        assertThat(creation.headers().firstValue("Location").orElseThrow()).endsWith(campaign(f,id));
        assertThat(creation.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.path("stateVersion").asText()).isEqualTo("0");
        assertThat(draft.path("scheduledAt").isNull()).isTrue(); assertThat(draft.path("cancelledAt").isNull()).isTrue();
        assertThat(ok(send(f,"POST",campaigns(f),creationKey,plan,false),201).path("id").asText()).isEqualTo(id.toString());
        var altered=new java.util.LinkedHashMap<>(CANONICAL.parseObject(plan));altered.put("batchSize",3L);
        error(send(f,"POST",campaigns(f),creationKey,CANONICAL.writeObject(altered),false),409,70036);
        String schedulingKey=key();
        var scheduled=ok(send(f,"POST",campaign(f,id)+"/scheduling",schedulingKey,revision("0"),false),200);
        error(send(f,"POST",campaign(f,id)+"/scheduling",schedulingKey,revision("0"),false),409,10014);
        error(send(f,"POST",campaign(f,id)+"/scheduling",schedulingKey,revision("1"),false),409,10009);
        assertThat(scheduled.path("status").asText()).isEqualTo("SCHEDULED");
        assertThat(scheduled.path("stateVersion").asText()).isEqualTo("1");
        assertThat(scheduled.path("targetCount").asInt()).isEqualTo(3); assertThat(scheduled.path("batchCount").asInt()).isEqualTo(2);
        var jobs=owner().queryForList("SELECT device_id,batch_number,status,thing_model_version_id,credential_version FROM ota_device_job WHERE campaign_id=? ORDER BY device_id",id);
        assertThat(jobs).hasSize(3);
        var sorted=devices.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList();
        for(int i=0;i<jobs.size();i++) {
            assertThat(jobs.get(i)).containsEntry("device_id",sorted.get(i)).containsEntry("batch_number",i/2+1)
                    .containsEntry("status","PENDING").containsEntry("thing_model_version_id",null).containsEntry("credential_version",1L);
        }
        assertThat(ok(send(f,"POST",campaign(f,id)+"/scheduling",key(),revision("0"),false),200)).isEqualTo(scheduled);
        var cancelled=ok(send(f,"POST",campaign(f,id)+"/cancellation",key(),cancel("1","停止未派发活动"),false),200);
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("stateVersion").asText()).isEqualTo("3");
        var auditRows=owner().queryForList("SELECT actor_account_id,details->>'reason' AS reason FROM sys_audit_log WHERE target_id=? AND action='ota.campaign.cancelled'",id);
        assertThat(auditRows).hasSize(1);assertThat(auditRows.getFirst()).containsEntry("actor_account_id",f.accountId())
                .containsEntry("reason","停止未派发活动");
        assertThat(owner().queryForList("SELECT status FROM ota_device_job WHERE campaign_id=?",String.class,id)).containsOnly("CANCELLED");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_campaign_transition WHERE campaign_id=?",Long.class,id)).isEqualTo(4L);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_campaign_outbox WHERE campaign_id=?",Long.class,id)).isEqualTo(4L);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_campaign_outbox WHERE campaign_id=? AND published_at IS NOT NULL",Long.class,id)).isZero();
        assertThat(cancelled.path("plan")).isEqualTo(draft.path("plan"));
        assertThat(cancelled.path("planSha256")).isEqualTo(draft.path("planSha256"));
        assertThat(ok(send(f,"POST",campaign(f,id)+"/cancellation",key(),cancel("1","停止未派发活动"),false),200)).isEqualTo(cancelled);
        error(send(f,"POST",campaign(f,id)+"/cancellation",key(),cancel("3","不同原因"),false),409,70036);
        assertObjectExists(p.upload());
    }

    /** 另一活动占位冲突必须完整回滚，取消前一活动后原草稿才可排程。 */
    @Test
    void releasesExclusiveDeviceReservationOnlyAfterCancellation() throws Exception {
        Prepared p=published(); Fixture f=p.fixture(); var ids=devices(f,2);
        UUID first=createCampaign(p,ids),second=createCampaign(p,ids);
        ok(send(f,"POST",campaign(f,first)+"/scheduling",key(),revision("0"),false),200);
        error(send(f,"POST",campaign(f,second)+"/scheduling",key(),revision("0"),false),409,70038);
        assertDraftWithoutChildren(f,second);
        ok(send(f,"POST",campaign(f,first)+"/cancellation",key(),cancel("1","释放设备预占"),false),200);
        ok(send(f,"POST",campaign(f,second)+"/scheduling",key(),revision("0"),false),200);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_device_job WHERE campaign_id=? AND status='PENDING'",Long.class,second)).isEqualTo(2L);
    }

    /** 创建不静默过滤目标，首次排程有任何目标缺失都不得留下部分作业或事件。 */
    @Test
    void rejectsMissingTargetWithoutPartialScheduling() throws Exception {
        Prepared p=published(); Fixture f=p.fixture(); var ids=new ArrayList<>(devices(f,1)); ids.add(Uuid7.generate());
        UUID id=createCampaign(p,ids);
        error(send(f,"POST",campaign(f,id)+"/scheduling",key(),revision("0"),false),409,70037);
        assertDraftWithoutChildren(f,id);
        var cancelled=ok(send(f,"POST",campaign(f,id)+"/cancellation",key(),cancel("0","撤销无效计划"),false),200);
        assertThat(cancelled.path("stateVersion").asText()).isEqualTo("1");
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
    }

    /** 原因、主体与状态在同事务审计；审计实际INSERT后失败不能留下预占或Outbox。 */
    @Test
    void rollsBackScheduleAndOutboxWhenItsAuditWriteFails() throws Exception {
        Prepared p=published(); Fixture f=p.fixture(); UUID id=createCampaign(p,devices(f,2));
        long before=owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE target_id=?",Long.class,id);
        org.mockito.Mockito.doAnswer(invocation->{invocation.callRealMethod();throw new IllegalStateException("测试活动审计写后失败");})
                .when(audit).record(org.mockito.ArgumentMatchers.argThat(entry->entry!=null&&id.equals(entry.targetId())));
        error(send(f,"POST",campaign(f,id)+"/scheduling",key(),revision("0"),false),500,90000);
        assertDraftWithoutChildren(f,id);
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE target_id=?",Long.class,id)).isEqualTo(before);
        org.mockito.Mockito.doCallRealMethod().when(audit).record(org.mockito.ArgumentMatchers.any());
        ok(send(f,"POST",campaign(f,id)+"/scheduling",key(),revision("0"),false),200);
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE target_id=?",Long.class,id)).isEqualTo(before+1);
        assertThat(owner().queryForList("SELECT actor_account_id FROM sys_audit_log WHERE target_id=?",UUID.class,id)).containsOnly(f.accountId());
    }

    /** 写入使用当前角色和严格请求，普通查看者、缺键与未知字段不能改变计划。 */
    @Test
    void enforcesCurrentManagementPermissionAndClosedMutationContracts() throws Exception {
        Prepared p=published(); Fixture f=p.fixture(); var ids=devices(f,1); byte[] body=plan(p.firmware(),ids);
        error(send(f,"POST",campaigns(f),null,body,false),400,10001);
        var extra=new java.util.LinkedHashMap<>(CANONICAL.parseObject(body)); extra.put("status","SCHEDULED");
        error(send(f,"POST",campaigns(f),key(),CANONICAL.writeObject(extra),false),400,10002);
        var distant=new java.util.LinkedHashMap<>(CANONICAL.parseObject(body));
        distant.put("notBefore",java.time.Instant.now().plus(31,java.time.temporal.ChronoUnit.DAYS)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
        error(send(f,"POST",campaigns(f),key(),CANONICAL.writeObject(distant),false),400,10001);
        String creationKey=key();
        UUID id=UUID.fromString(ok(send(f,"POST",campaigns(f),creationKey,body,false),201).path("id").asText());
        owner().update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",f.projectId(),f.accountId());
        error(send(f,"GET",campaign(f,id),null,null,false),403,70035);
        error(send(f,"POST",campaign(f,id)+"/scheduling",key(),revision("0"),false),403,70035);
        owner().update("UPDATE sys_project_member SET role='ADMIN' WHERE project_id=? AND account_id=?",f.projectId(),f.accountId());
        error(send(f,"POST",campaign(f,id)+"/scheduling",key(),revision("9"),false),409,70036);
        assertDraftWithoutChildren(f,id);
        owner().update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?",f.projectId(),f.accountId());
        error(send(f,"GET",campaign(f,id),null,null,false),401,20020);
        error(send(f,"POST",campaigns(f),creationKey,body,false),401,20020);
    }

    /** 同一设备两个HTTP排程竞争只有一个成功，失败活动仍完整保留草稿。 */
    @Test
    void serializesConcurrentHttpReservationsWithoutPartialLoser() throws Exception {
        Prepared p=published(); Fixture f=p.fixture(); var ids=devices(f,2);
        UUID first=createCampaign(p,ids),second=createCampaign(p,ids);
        var gate=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var left=executor.submit(()->{gate.await();return send(f,"POST",campaign(f,first)+"/scheduling",key(),revision("0"),false);});
            var right=executor.submit(()->{gate.await();return send(f,"POST",campaign(f,second)+"/scheduling",key(),revision("0"),false);});
            gate.countDown(); var a=left.get(15,java.util.concurrent.TimeUnit.SECONDS);var b=right.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(List.of(a.statusCode(),b.statusCode())).containsExactlyInAnyOrder(200,409);
            error(a.statusCode()==409?a:b,409,70038);
            assertDraftWithoutChildren(f,a.statusCode()==409?first:second);
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_device_job WHERE project_id=? AND status='PENDING'",Long.class,f.projectId())).isEqualTo(2L);
        }
    }

    /** 创建时验真不代替排程时当前资格，撤销后拒绝且不保留任何作业。 */
    @Test
    void rejectsFirmwareRevokedAfterDraftBeforeScheduling() throws Exception {
        Prepared p=published(); Fixture f=p.fixture(); UUID id=createCampaign(p,devices(f,2));
        ok(send(f,"POST",base(f)+"/"+p.firmware()+"/revocations",key(),
                CANONICAL.writeObject(java.util.Map.of("expectedRevision","2","reason","禁止新活动预占")),false),200);
        error(send(f,"POST",campaign(f,id)+"/scheduling",key(),revision("0"),false),409,70022);
        assertDraftWithoutChildren(f,id); assertObjectExists(p.upload());
    }

    /** 只创建明确名单，不添加有效凭据、报告或绑定来降低排程测试难度。 */
    private static List<UUID> devices(Fixture f,int count) {
        var result=new ArrayList<UUID>();
        for(int index=0;index<count;index++) {
            UUID id=Uuid7.generate(); result.add(id);
            owner().update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name) VALUES (?,?,?,?,?,'活动目标测试')",
                    id,f.tenantId(),f.projectId(),f.typeId(),"device_"+id);
        }
        java.util.Collections.reverse(result); return List.copyOf(result);
    }
    /** 显式完整策略与稳定名单，旧时间表示立即可执行但本片仍不派发。 */
    private static byte[] plan(UUID firmware,List<UUID> devices) {
        var policy=new java.util.LinkedHashMap<String,Object>();
        policy.put("maxConcurrentDownloads",2L);policy.put("maxDownloadBytesPerSecond",1048576L);
        policy.put("downloadRetryLimit",2L);policy.put("retryBackoffSeconds",5L);policy.put("healthWindowSeconds",60L);
        policy.put("pauseMinEvaluated",2L);policy.put("pauseFailureCount",1L);policy.put("pauseFailureRateBps",5000L);
        policy.put("batchMinSuccessRateBps",10000L);policy.put("requireManualBatchApproval",true);
        var stages=new java.util.LinkedHashMap<String,Object>();
        for(String name:List.of("DISPATCHED","DOWNLOADING","VERIFYING","INSTALLING","REBOOTING","HEALTH_CHECKING","CONFIRMING","ROLLBACK_PENDING","ROLLING_BACK")) stages.put(name,120L);
        policy.put("stageTimeoutSeconds",stages);
        return CANONICAL.writeObject(java.util.Map.of("contractVersion","tc-ota-campaign-plan/v1","firmwareId",firmware.toString(),
                "deviceIds",devices.stream().map(UUID::toString).toList(),"batchSize",2L,"notBefore","2026-09-12T00:00:00Z","executionPolicy",policy));
    }
    /** HTTP创建返回持久身份，拒绝以owner直接写活动来绕过资格。 */
    private UUID createCampaign(Prepared p,List<UUID> ids) throws Exception {
        return UUID.fromString(ok(send(p.fixture(),"POST",campaigns(p.fixture()),key(),plan(p.firmware(),ids),false),201).path("id").asText());
    }
    /** 草稿保留原始创建转移和事件，无排程半成品。 */
    private void assertDraftWithoutChildren(Fixture f,UUID id) throws Exception {
        var current=ok(send(f,"GET",campaign(f,id),null,null,false),200);
        assertThat(current.path("status").asText()).isEqualTo("DRAFT");assertThat(current.path("stateVersion").asText()).isEqualTo("0");
        for(String table:List.of("ota_device_job","ota_campaign_batch"))
            assertThat(owner().queryForObject("SELECT count(*) FROM "+table+" WHERE campaign_id=?",Long.class,id)).isZero();
        for(String table:List.of("ota_campaign_transition","ota_campaign_outbox"))
            assertThat(owner().queryForObject("SELECT count(*) FROM "+table+" WHERE campaign_id=?",Long.class,id)).isEqualTo(1L);
    }
    /** 管理集合路径。 */
    private static String campaigns(Fixture f) { return "/api/v1/projects/"+f.projectId()+"/ota/campaigns"; }
    /** 明确活动路径。 */
    private static String campaign(Fixture f,UUID id) { return campaigns(f)+"/"+id; }
    /** 排程仅接受期望状态修订。 */
    private static byte[] revision(String revision) { return CANONICAL.writeObject(java.util.Map.of("expectedRevision",revision)); }
    /** 取消原因不能由默认字段代替。 */
    private static byte[] cancel(String revision,String reason) { return CANONICAL.writeObject(java.util.Map.of("expectedRevision",revision,"reason",reason)); }


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
                for (String table : List.of("ota_campaign_outbox", "ota_campaign_transition", "ota_device_job",
                        "ota_campaign_batch", "ota_campaign_creation_request", "ota_campaign")) {
                    owner.update("DELETE FROM " + table + " WHERE project_id=?", fixture.projectId());
                }
            });
            owner.update("DELETE FROM ota_firmware_release WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_firmware_publication WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_trust_bundle WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_trust_domain WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_firmware_upload_session WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_firmware_creation_request WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_firmware WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM dev_device WHERE project_id = ?", fixture.projectId());
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
