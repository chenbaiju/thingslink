package com.things.link.bootstrap.ota;

import com.things.link.ota.domain.OtaDownloadRequestRepository;
import com.things.link.ota.domain.OtaDownloadAuthorizationRepository;
import com.things.link.ota.infrastructure.persistence.JdbcOtaDownloadAuthorizationRepository;
import com.things.link.ota.infrastructure.persistence.JdbcOtaDownloadRequestRepository;
import com.things.link.ota.domain.OtaNotificationRepository;
import com.things.link.ota.infrastructure.persistence.JdbcOtaNotificationRepository;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.infrastructure.persistence.JdbcOtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaCampaign;
import com.things.link.ota.domain.OtaCampaignRepository;
import com.things.link.ota.infrastructure.persistence.JdbcOtaCampaignRepository;
import com.things.link.ota.domain.OtaUploadSession;
import com.things.link.ota.domain.OtaPublication;
import com.things.link.ota.domain.OtaRelease;
import com.things.link.ota.infrastructure.persistence.JdbcOtaPublicationRepository;
import com.things.link.ota.infrastructure.persistence.JdbcOtaUploadRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 运行取消真实持久边界，只关闭新能力，不伪造设备安全终态。 */
class OtaCampaignRuntimeCancellationPersistenceTests extends AbstractIntegrationTest {
    /** 每例隔离项目。 */
    private final List<Fixture> fixtures = new ArrayList<>();

    /** owner按子先父清理全部夹具，完整删除事务不留下临时断图。 */
    @AfterEach void cleanup() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        for (Fixture f : fixtures) {
            new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status -> {
                var j = new JdbcTemplate(source);
                for (String table : List.of("ota_job_progress_outbox", "ota_job_progress", "ota_job_expiry", "ota_job_execution_origin", "ota_campaign_runtime_cancellation", "ota_download_transport", "ota_download_authorization_outbox", "ota_download_authorization", "ota_download_bandwidth", "ota_download_request_outbox", "ota_download_request", "ota_notification_transport", "ota_notification_delivery", "ota_job_dispatch_outbox", "ota_job_transition", "ota_batch_transition", "ota_campaign_outbox", "ota_campaign_transition", "ota_device_job",
                        "ota_campaign_batch", "ota_campaign_creation_request", "ota_campaign", "ota_firmware_release",
                        "ota_firmware_publication", "ota_firmware_upload_session", "ota_firmware_creation_request",
                        "ota_firmware", "ota_device_report", "dev_device", "dev_thing_model_version", "dev_type")) {
                    j.update("DELETE FROM " + table + " WHERE project_id=?", f.project());
                }
                j.update("DELETE FROM sys_project WHERE id=?", f.project());
                j.update("DELETE FROM sys_tenant WHERE id=?", f.tenant());
                j.update("DELETE FROM sys_account WHERE id=?", f.account());
                return true;
            });
        }
    }

    /** 原派发、下载和额度字段由真实先前状态机建立。 */
    private int notificationDeadlineSeconds=120;
    /** 测试使用明确并发上限。 */
    private int concurrentDownloads=1;
    /** 测试不声称代理物理字节限速。 */
    private long bytesPerSecond=1_073_741_824L;
    /** 清单与上传数据库长度一致。 */
    private long artifactSize=1;
    /** 仅成功清理专项使用真实隔离版本桶，其余纯PG夹具不认领物理证据。 */
    private com.things.link.support.storage.MinioVersionedPrivateObjectStorage actualStorage;
    /** 本例独立物理桶，不引用开发配置。 */
    private String actualBucket;
    /** 仅包含本例一字节固件的临时文件。 */
    private java.nio.file.Path actualArtifact;
    /** 与真实文件一致的数据库摘要，其余纯PG夹具沿用固定摘要。 */
    private String artifactSha256="a".repeat(64);

    /** 多个未来批次直接取消，整个图仍只有一个当前活跃位置。 */
    @Test void cancelsAllPendingAcrossBatchesWithImmutableRequest() {
        Fixture f=ready(); var c=scheduled(f,5,2);
        runtime(f,r->r.start(f.project(),c.id(),1,f.account()));
        assertThat(OtaCampaignRuntimeCancellationPersistenceTests.<Boolean>runtime(f,r->r.cancelRuntime(f.project(),c.id(),2,f.account(),"停止未准入活动"))).isTrue();
        var actual=runtime(f,r->r.read(f.project(),c.id()).orElseThrow());
        assertThat(actual.campaign().status()).isEqualTo("CANCELLED");
        assertThat(actual.campaign().stateVersion()).isEqualTo(4);
        assertThat(actual.runtimeCancellation().cancelledPendingCount()).isEqualTo(5);
        assertThat(actual.runtimeCancellation().unresolvedCount()).isZero();
        assertThat(actual.runtimeCancellation().completedAt()).isEqualTo(actual.runtimeCancellation().requestedAt());
        assertThat(owner().queryForList("SELECT status FROM ota_campaign_batch WHERE campaign_id=? ORDER BY batch_number",String.class,c.id()))
                .containsExactly("CANCELLED","CANCELLED","CANCELLED");
        assertThat(owner().queryForList("SELECT state_version FROM ota_campaign_batch WHERE campaign_id=? ORDER BY batch_number",Long.class,c.id()))
                .containsExactly(3L,1L,1L);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_campaign_outbox WHERE campaign_id=?",Integer.class,c.id())).isEqualTo(5);
        assertThat(OtaCampaignRuntimeCancellationPersistenceTests.<Boolean>runtime(f,r->r.cancelRuntime(f.project(),c.id(),2,f.account(),"停止未准入活动"))).isFalse();
        assertThatThrownBy(()->app(f,j->j.update("UPDATE ota_campaign_runtime_cancellation SET reason='改写原请求' WHERE campaign_id=?",c.id())))
                .hasStackTraceContaining("permission denied");
        Fixture foreign=seed();
        assertThat(OtaCampaignRuntimeCancellationPersistenceTests.<Integer>app(foreign,j->j.queryForObject("SELECT count(*) FROM ota_campaign_runtime_cancellation WHERE campaign_id=?",Integer.class,c.id()))).isZero();
    }

    /** 混合下载、派发和未准入目标取消时，仅后者释放占用，迟到观察不能采用。 */
    @Test void retainsDispatchedAndDownloadingResponsibilitiesAndRejectsLateCapabilities() {
        Fixture f=ready();var c=scheduled(f,5,3);
        runtime(f,r->r.start(f.project(),c.id(),1,f.account()));
        var first=claimRuntime();runtime(f,r->r.admit(first,1,1,OtaExecutionReportFixture.HASH,Instant.now()));
        var second=claimRuntime();runtime(f,r->r.admit(second,1,1,OtaExecutionReportFixture.HASH,Instant.now()));
        var context=requests(f,r->r.locate(first.jobId()).orElseThrow());
        var receipt=request(f,context);requests(f,r->r.create(receipt,context.jobRevision()));
        var waiting=claimAuthorization();
        var signing=authorize(f,r->r.reserveSigning(waiting,waiting.jobRevision()).orElseThrow());
        authorize(f,r->r.seal(signing,signing.jobRevision(),"key-v1",new byte[12],new byte[32],"a".repeat(64),topic()));
        var sealed=claimAuthorization();var transport=authorize(f,r->r.reserveSend(sealed).orElseThrow());
        var flight=authorize(f,r->r.authoritativeClaim(sealed.authorizationId(),sealed.leaseToken()).orElseThrow());
        var notification=plain(j->new JdbcOtaNotificationRepository(j).claimOne().orElseThrow());
        var original=owner().queryForList("SELECT id,status,attempt_no,deadline_at,dispatched_at,downloading_at FROM ota_device_job WHERE campaign_id=? AND status IN ('DISPATCHED','DOWNLOADING') ORDER BY id",c.id());
        runtime(f,r->r.cancelRuntime(f.project(),c.id(),2,f.account(),"停止后续派发"));
        var actual=runtime(f,r->r.read(f.project(),c.id()).orElseThrow());
        assertThat(actual.campaign().status()).isEqualTo("CANCELLING");
        assertThat(actual.runtimeCancellation().cancelledPendingCount()).isEqualTo(3);
        assertThat(actual.runtimeCancellation().unresolvedCount()).isEqualTo(2);
        assertThat(actual.runtimeCancellation().completedAt()).isNull();
        assertThat(owner().queryForList("SELECT id,status,attempt_no,deadline_at,dispatched_at,downloading_at FROM ota_device_job WHERE campaign_id=? AND status IN ('DISPATCHED','DOWNLOADING') ORDER BY id",c.id())).isEqualTo(original);
        assertThat(owner().queryForList("SELECT status FROM ota_campaign_batch WHERE campaign_id=? ORDER BY batch_number",String.class,c.id())).containsExactly("CANCELLING","CANCELLED");
        assertThat(OtaCampaignRuntimeCancellationPersistenceTests.<java.util.Optional<OtaDownloadAuthorizationRepository.Claim>>authorize(f,r->r.authoritativeClaim(sealed.authorizationId(),sealed.leaseToken()))).isEmpty();
        assertThat(OtaCampaignRuntimeCancellationPersistenceTests.<java.util.Optional<OtaNotificationRepository.Claim>>app(f,j->new JdbcOtaNotificationRepository(j).authoritativeClaim(notification.eventId(),notification.leaseToken()))).isEmpty();
        assertThat(OtaCampaignRuntimeCancellationPersistenceTests.<Boolean>authorize(f,r->r.recordObservation(transport,"BROKER_ACCEPTED",200,null))).isTrue();
        assertThat(OtaCampaignRuntimeCancellationPersistenceTests.<Boolean>authorize(f,r->r.settleCurrent(flight,transport.id()))).isFalse();
        assertThat(owner().queryForObject("SELECT status FROM ota_download_authorization WHERE id=?",String.class,sealed.authorizationId())).isEqualTo("IN_FLIGHT");
        assertThat(OtaCampaignRuntimeCancellationPersistenceTests.<Boolean>runtime(f,r->r.resume(f.project(),c.id(),3,f.account(),"不能恢复取消"))).isFalse();
        UUID token=Uuid7.generate();
        owner().update("UPDATE sys_project SET status='PURGING',lifecycle_generation=1,deleted_at=now()-interval '31 days',cleanup_stage='OTA',"
                +"cleanup_started_at=now(),cleanup_next_attempt_at=now(),cleanup_lease_token=?,cleanup_lease_until=now()+interval '120 seconds' WHERE id=?",token,f.project());
        assertThat(cleanBatch(f,token)).isEqualTo("OTA_CAMPAIGN_EXECUTION_PENDING");
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware_upload_session WHERE firmware_id=?",String.class,f.firmware())).isEqualTo("ADOPTED");
    }

    /** 已失败批次不重新打开，只有未来未准入目标新增取消历史。 */
    @Test void cancelsPausedFailedBatchWithoutReopeningItsTerminalHistory() {
        Fixture f=ready();var c=scheduled(f,3,2);runtime(f,r->r.start(f.project(),c.id(),1,f.account()));
        for(int index=0;index<2;index++){var pending=claimRuntime();runtime(f,r->r.skip(pending,"不符合条件"));}
        var before=owner().queryForMap("SELECT status,state_version FROM ota_campaign_batch WHERE campaign_id=? AND batch_number=1",c.id());
        var paused=runtime(f,r->r.read(f.project(),c.id()).orElseThrow());
        assertThat(paused.campaign().status()).isEqualTo("PAUSED");
        runtime(f,r->r.cancelRuntime(f.project(),c.id(),paused.campaign().stateVersion(),f.account(),"取消剩余批次"));
        var actual=runtime(f,r->r.read(f.project(),c.id()).orElseThrow());
        assertThat(actual.campaign().status()).isEqualTo("CANCELLED");
        assertThat(actual.runtimeCancellation().cancelledPendingCount()).isEqualTo(1);
        assertThat(actual.runtimeCancellation().unresolvedCount()).isZero();
        assertThat(actual.pauseKind()).isNull();
        assertThat(owner().queryForMap("SELECT status,state_version FROM ota_campaign_batch WHERE campaign_id=? AND batch_number=1",c.id())).isEqualTo(before);
    }

    /** 错误修订和空主体不写；仅插请求或仅改头的半取消图无法提交。 */
    @Test void rejectsStaleNullActorAndPartialCancellationGraph() {
        Fixture f=ready();var c=scheduled(f,2,1);runtime(f,r->r.start(f.project(),c.id(),1,f.account()));
        assertThat(OtaCampaignRuntimeCancellationPersistenceTests.<Boolean>runtime(f,r->r.cancelRuntime(f.project(),c.id(),1,f.account(),"旧修订"))).isFalse();
        assertThat(OtaCampaignRuntimeCancellationPersistenceTests.<Boolean>runtime(f,r->r.cancelRuntime(f.project(),c.id(),2,null,"无主体"))).isFalse();
        assertThatThrownBy(()->app(f,j->j.update("INSERT INTO ota_campaign_runtime_cancellation(tenant_id,project_id,campaign_id,requested_revision,requested_from_status,requested_at,requested_by,reason,cancelled_pending_count) VALUES(?,?,?,2,'RUNNING',clock_timestamp(),?,'半图',2)",f.tenant(),f.project(),c.id(),f.account())))
                .hasStackTraceContaining("request transition mismatch");
        assertThatThrownBy(()->app(f,j->j.update("UPDATE ota_campaign SET status='CANCELLING',state_version=3,cancellation_reason='缺事件',updated_at=clock_timestamp() WHERE id=?",c.id())))
                .hasStackTraceContaining("graph incomplete");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_campaign_runtime_cancellation WHERE campaign_id=?",Integer.class,c.id())).isZero();
    }
    /** 真实版本先删除并复核空盘点，再证明新取消事实先于父活动分批清理。 */
    @Test void clearsNonemptyCancellationThroughBoundedCleanupAfterRealObjectRemoval() throws Exception {
        var minio=new org.testcontainers.containers.GenericContainer<>(org.testcontainers.utility.DockerImageName.parse(
                "minio/minio:RELEASE.2025-04-22T22-12-26Z"))
                .withEnv("MINIO_ROOT_USER","ota-cleanup-test").withEnv("MINIO_ROOT_PASSWORD","ota-cleanup-test-secret")
                .withCommand("server","/data").withExposedPorts(9000)
                .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/minio/health/ready").forPort(9000));
        io.minio.MinioClient admin=null;
        try {
            minio.start();
            String endpoint="http://"+minio.getHost()+":"+minio.getMappedPort(9000);
            admin=io.minio.MinioClient.builder().endpoint(endpoint).credentials("ota-cleanup-test","ota-cleanup-test-secret").build();
            actualBucket="ota-cancel-"+UUID.randomUUID();
            admin.makeBucket(io.minio.MakeBucketArgs.builder().bucket(actualBucket).build());
            admin.setBucketVersioning(io.minio.SetBucketVersioningArgs.builder().bucket(actualBucket)
                    .config(new io.minio.messages.VersioningConfiguration(io.minio.messages.VersioningConfiguration.Status.ENABLED,null,null,null)).build());
            actualStorage=new com.things.link.support.storage.MinioVersionedPrivateObjectStorage(endpoint,endpoint,"ota-cleanup-test","ota-cleanup-test-secret");
            actualArtifact=java.nio.file.Files.createTempFile("ota-cancel-cleanup-",".bin");
            java.nio.file.Files.write(actualArtifact,new byte[]{1});artifactSha256=hash(new byte[]{1});
            Fixture f=ready();var c=scheduled(f,2,1);runtime(f,r->r.start(f.project(),c.id(),1,f.account()));
            runtime(f,r->r.cancelRuntime(f.project(),c.id(),2,f.account(),"验证有界清理"));
            UUID token=Uuid7.generate();
            owner().update("UPDATE sys_project SET status='PURGING',lifecycle_generation=1,deleted_at=now()-interval '31 days',cleanup_stage='OTA',"
                    +"cleanup_started_at=now(),cleanup_next_attempt_at=now(),cleanup_lease_token=?,cleanup_lease_until=now()+interval '120 seconds' WHERE id=?",token,f.project());
            assertThat(cleanBatch(f,token)).isEqualTo("OTA_UPLOAD_CANCELLATION_REQUESTED");
            var recovery=plain(j->new JdbcOtaUploadRepository(j).claimRecovery().orElseThrow());
            assertThat(recovery.projectId()).isEqualTo(f.project());
            assertThat(recovery.status()).isEqualTo("CLEANUP_PENDING");
            var versions=actualStorage.listVersions(recovery.bucket(),recovery.objectKey(),null,32,physicalControl());
            assertThat(versions.hasMore()).isFalse();assertThat(versions.items()).hasSize(1);
            assertThat(versions.items().getFirst().version().versionId()).isEqualTo(recovery.versionId());
            for(var item:versions.items())actualStorage.delete(item.version(),physicalControl());
            var remaining=actualStorage.listVersions(recovery.bucket(),recovery.objectKey(),null,1,physicalControl());
            var multipart=actualStorage.listMultipartUploads(recovery.bucket(),recovery.objectKey(),null,1,physicalControl());
            assertThat(remaining.items()).isEmpty();assertThat(remaining.hasMore()).isFalse();
            assertThat(multipart.items()).isEmpty();assertThat(multipart.hasMore()).isFalse();
            assertThat(OtaCampaignRuntimeCancellationPersistenceTests.<Boolean>upload(f,r->r.finishCleanup(recovery,recovery.leaseToken()))).isTrue();
            var first=cleanupResult(f,token);
            assertThat(first.get("deleted_rows")).isEqualTo(1);
            assertThat(first.get("complete")).isEqualTo(false);
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_campaign_runtime_cancellation WHERE campaign_id=?",Integer.class,c.id())).isZero();
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_campaign WHERE id=?",Integer.class,c.id())).isEqualTo(1);
            boolean complete=false;
            for(int batch=0;batch<64 && !complete;batch++) {
                var result=cleanupResult(f,token);
                assertThat(result.get("blocked_reason")).isNull();
                assertThat(((Number)result.get("deleted_rows")).intValue()).isBetween(0,500);
                complete=Boolean.TRUE.equals(result.get("complete"));
            }
            assertThat(complete).isTrue();
            for(String table:List.of("ota_campaign_runtime_cancellation","ota_campaign","ota_device_job","ota_firmware_release","ota_firmware_upload_session","ota_firmware"))
                assertThat(owner().queryForObject("SELECT count(*) FROM "+table+" WHERE project_id=?",Integer.class,f.project())).isZero();
            assertThat(owner().queryForObject("SELECT project_cleanup_authorized(?,?,1,'OTA',?)",Boolean.class,f.tenant(),f.project(),token)).isTrue();
        } finally {
            try { if(actualStorage!=null){actualStorage.close();actualStorage=null;} }
            finally {
                try { if(admin!=null)admin.close(); }
                finally {
                    try { minio.close(); }
                    finally { if(actualArtifact!=null)java.nio.file.Files.deleteIfExists(actualArtifact); }
                }
            }
        }
    }
    /** 普通应用角色调用真实0750，每轮返回实际删除计数而非推断清空。 */
    private java.util.Map<String,Object> cleanupResult(Fixture f,UUID token) {
        return plain(j->j.queryForMap("SELECT * FROM ota_project_cleanup_batch(?,?,1,?)",f.tenant(),f.project(),token));
    }
    /** 每次物理操作有独立五秒取消预算。 */
    private static com.things.link.support.storage.VersionedStorageControl physicalControl() {
        return new com.things.link.support.storage.VersionedStorageControl(java.time.Duration.ofSeconds(5),()->false);
    }

    /** 真实申请接纳自动产生待签址授权，不直接插造授权头。 */
    private Fixture accepted() {
        Fixture f = dispatched();
        var c = context(f);
        var receipt = request(f,c);
        requests(f, r -> r.create(receipt, c.jobRevision()));
        return f;
    }
    /** 后台原能力读取不预设租户。 */
    private OtaDownloadAuthorizationRepository.Claim claimAuthorization() {
        return plain(j -> new JdbcOtaDownloadAuthorizationRepository(j).claimOne().orElseThrow());
    }
    /** 最终动作加入真实RLS与项目控制锁。 */
    private static <T> T authorize(Fixture f, Function<JdbcOtaDownloadAuthorizationRepository,T> work) {
        return app(f,j -> { new JdbcOtaCampaignRuntimeRepository(j).controlLock(f.tenant(),f.project());
            return work.apply(new JdbcOtaDownloadAuthorizationRepository(j)); });
    }
    /** 数据库专项固定无秘密路由，HTTP专项验证真实设备路由。 */
    private static String topic() { return "tc/v1/project/device/down/ota/download/response"; }

    /** 原流程真实建立DISPATCHED与不可变通知意图。 */
    private Fixture dispatched() {
        Fixture f = ready();
        var campaign = scheduled(f, 1);
        runtime(f, r -> r.start(f.project(), campaign.id(), 1, f.account()));
        var claim = claimRuntime();
        runtime(f, r -> r.admit(claim, 1, 1, OtaExecutionReportFixture.HASH, Instant.now()));
        return f;
    }
    /** 必须先有真实RLS，再定位当前作业。 */
    private OtaDownloadRequestRepository.JobContext context(Fixture f) {
        UUID job = owner().queryForObject("SELECT id FROM ota_device_job WHERE project_id=?", UUID.class, f.project());
        return requests(f, r -> r.locate(job).orElseThrow());
    }
    /** 真实数据库时间构造规范申请，不注入客户端时间字段。 */
    private OtaDownloadRequestRepository.Request request(Fixture f, OtaDownloadRequestRepository.JobContext c) {
        UUID request = Uuid7.generate();
        byte[] canonical = ("{\"attemptNo\":1,\"contractVersion\":\"tc-ota-download-request/v1\",\"jobId\":\"" + c.jobId()
                + "\",\"manifestSha256\":\"" + c.manifestSha256() + "\",\"requestId\":\"" + request + "\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Instant at = runtime(f, JdbcOtaCampaignRuntimeRepository::currentTime);
        return new OtaDownloadRequestRepository.Request(Uuid7.generate(), c.tenantId(), c.projectId(), c.deviceId(),
                c.credentialVersion(), request, c.jobId(), c.campaignId(), c.firmwareId(), c.attemptNo(), c.manifestSha256(),
                canonical, hash(canonical), c.originalDeadline(), at, at, 1, "a".repeat(64));
    }
    /** 普通角色按现有项目控制锁加入事务。 */
    private static <T> T requests(Fixture f, Function<JdbcOtaDownloadRequestRepository, T> work) {
        return app(f, j -> {
            new JdbcOtaCampaignRuntimeRepository(j).controlLock(f.tenant(), f.project());
            return work.apply(new JdbcOtaDownloadRequestRepository(j));
        });
    }
    /** 直接SQL负例故意省略outbox，不能借仓储替代数据库完整性证据。 */
    private static int insertRaw(JdbcTemplate j, OtaDownloadRequestRepository.Request r, long revision) {
        return j.update("INSERT INTO ota_download_request(id,tenant_id,project_id,device_id,credential_version,request_id,job_id,"
                + "campaign_id,firmware_id,attempt_no,manifest_sha256,canonical,canonical_sha256,original_deadline,broker_received_at,"
                + "accepted_at,report_revision,report_hash,job_revision) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                r.id(), r.tenantId(), r.projectId(), r.deviceId(), r.credentialVersion(), r.requestId(), r.jobId(), r.campaignId(),
                r.firmwareId(), r.attemptNo(), r.manifestSha256(), r.canonical(), r.canonicalSha256(), java.sql.Timestamp.from(r.originalDeadline()),
                java.sql.Timestamp.from(r.brokerReceivedAt()), java.sql.Timestamp.from(r.acceptedAt()), r.reportRevision(), r.reportHash(), revision);
    }

    /** 运行夹具全部目标位于第一批。 */
    private OtaCampaign scheduled(Fixture f, int count) { return scheduled(f,count,count); }
    /** 独立冻结多批次，不改变名单排序。 */
    private OtaCampaign scheduled(Fixture f, int count, int batchSize) {
        var ids = devices(f, count);
        var c = campaign(f, ids, batchSize);
        campaignRun(f, r -> r.schedule(0, c, targets(f, ids), batchSize, f.account(), c.createdAt()));
        return c;
    }
    /** 不建立管理ThreadLocal，直接用受限领取返回的真实范围。 */
    private OtaCampaignRuntimeRepository.Claim claimRuntime() {
        return plain(j -> new JdbcOtaCampaignRuntimeRepository(j).claimOne().orElseThrow());
    }
    /** 真实普通角色及控制锁持有整个最终事务。 */
    private static <T> T runtime(Fixture f, Function<JdbcOtaCampaignRuntimeRepository, T> work) {
        return app(f, j -> {
            OtaExecutionReportFixture.seed(j, f.tenant(), f.project());
            var r = new JdbcOtaCampaignRuntimeRepository(j);
            r.controlLock(f.tenant(), f.project());
            return work.apply(r);
        });
    }

    /** 构造真实完整已采用发布父图，密码学与对象行为由其他专项覆盖。 */
    private Fixture ready() {
        Fixture f = seed();
        OtaPublication p = prepared(f);
        run(f, r -> r.recordSigned(p, p.leaseToken(), new byte[32], new byte[64], "campaign-fixture"));
        OtaPublication signed = publication(f, p);
        run(f, r -> r.commitRelease(signed, signed.leaseToken(), release(signed)));
        return f;
    }
    /** 稳定规范文本排序目标。 */
    private List<UUID> devices(Fixture f, int count) {
        List<UUID> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID id = Uuid7.generate();
            owner().update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name)"
                    + " VALUES(?,?,?,?,?,'活动目标')", id, f.tenant(), f.project(), f.type(), "target_" + id);
            result.add(id);
        }
        return result.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList();
    }
    /** 仅冻结设备权威字段，未绑定模型允许为空。 */
    private List<OtaCampaignRepository.Target> targets(Fixture f, List<UUID> ids) {
        return ids.stream().map(id -> new OtaCampaignRepository.Target(id, f.type(), null, 1)).toList();
    }
    /** 最小数据库计划，只用于结构持久约束，完整闭集语法由合同专项验证。 */
    private OtaCampaign campaign(Fixture f, List<UUID> devices, int batchSize) {
        var release = run(f, r -> r.findRelease(f.project(), f.firmware()).orElseThrow());
        String ids = devices.stream().map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(","));
        byte[] plan = ("{\"batchSize\":" + batchSize + ",\"deviceIds\":[" + ids
                + "],\"executionPolicy\":{\"maxConcurrentDownloads\":" + concurrentDownloads + ",\"maxDownloadBytesPerSecond\":" + bytesPerSecond + ",\"stageTimeoutSeconds\":{\"DOWNLOADING\":120,\"DISPATCHED\":" + notificationDeadlineSeconds + "}},\"firmwareId\":\""
                + f.firmware() + "\",\"notBefore\":\"2020-01-01T00:00:00Z\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OtaCampaign c = new OtaCampaign(Uuid7.generate(), f.tenant(), f.project(), f.firmware(), release.id(),
                f.account(), plan, hash(plan), release.canonicalManifest(), hash(release.canonicalManifest()), "DRAFT",
                0, 0, 0, now, now, null, null, null);
        campaignRun(f, r -> { r.create(c, hash(c.id().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), c.planSha256()); return true; });
        return c;
    }
    /** 按实际字节计算摘要。 */
    private static String hash(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    /** 真实普通角色活动事务。 */
    private static <T> T campaignRun(Fixture f, Function<JdbcOtaCampaignRepository, T> work) {
        return app(f, j -> work.apply(new JdbcOtaCampaignRepository(j)));
    }

    /** 正常上传状态机建立固定版本DB证据，不伪造生产验签成功。 */
    private OtaPublication prepared(Fixture f) {
        OtaUploadSession u=create(f); UUID token=Uuid7.generate();
        upload(f,r->r.claimReceive(u,token)); upload(f,r->r.markWriting(u,token));
        String physicalVersion="version-one";
        if(actualStorage!=null) {
            var reference=actualStorage.upload(new com.things.link.support.storage.VersionedPrivateObjectStorage.WriteRequest(
                    new com.things.link.support.storage.VersionedPrivateObjectStorage.WriteIdentity(u.bucket(),u.objectKey(),u.requestId()),actualArtifact),physicalControl());
            actualStorage.verify(reference,u.expectedLength(),u.expectedSha256(),physicalControl());
            physicalVersion=reference.versionId();
        }
        String recordedVersion=physicalVersion;
        upload(f,r->r.recordVersion(u,token,recordedVersion)); upload(f,r->r.finishVerified(u,token));
        OtaUploadSession verified=current(f,u); Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        OtaPublication p=new OtaPublication(Uuid7.generate(),f.tenant(),f.project(),f.firmware(),u.id(),f.account(),Uuid7.generate(),0,0,verified.revision(),("{\"artifactSize\":"+artifactSize+",\"deviceTypeId\":\""+f.type()+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8),new byte[]{2},"PREPARED",0,null,null,null,null,null,null,now,now);
        run(f,r->{r.create(p);return true;}); OtaPublication claimed=claim(); assertThat(claimed.id()).isEqualTo(p.id()); return claimed;
    }
    /** 单条可信scope领取。 */
    private OtaPublication claim() { return plain(j->new JdbcOtaPublicationRepository(j).claimPreparedOrSigned().orElseThrow()); }
    /** owner仅模拟真实时间流逝，不更改状态或身份。 */
    private void expire(OtaPublication p) { owner().update("UPDATE ota_firmware_publication SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",p.id()); }
    /** 精确当前回执。 */
    private OtaPublication publication(Fixture f,OtaPublication p) { return run(f,r->r.find(f.project(),f.firmware(),p.id(),false).orElseThrow()); }
    /** 同一规范证据构造不可变release。 */
    private OtaRelease release(OtaPublication p) { return new OtaRelease(p.id(),p.tenantId(),p.projectId(),p.firmwareId(),p.uploadSessionId(),p.id(),p.canonicalManifest(),p.trustSnapshot(),p.spki(),p.signature(),p.receipt(),Instant.now()); }
    /** 普通app事务持久发布。 */
    private static <T> T run(Fixture f,Function<JdbcOtaPublicationRepository,T> work) { return app(f,j->work.apply(new JdbcOtaPublicationRepository(j))); }

    /** owner只提供合法固件和模型，不绕过上传会话业务写入。 */
    private Fixture seed() {
        Fixture f = new Fixture(Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate());
        fixtures.add(f);
        JdbcTemplate jdbc=owner();
        jdbc.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'unused','OTA上传测试')",f.account(),f.account()+"@example.invalid");
        jdbc.update("INSERT INTO sys_tenant(id,name) VALUES (?,'OTA上传租户')",f.tenant());
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES (?,?,'OTA上传项目',?)",f.project(),f.tenant(),"upload_"+f.project().toString().replace("-",""));
        jdbc.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol) VALUES (?,?,?,'upload-type','上传类型','DIRECT','STANDARD')",f.type(),f.tenant(),f.project());
        jdbc.update("""
                INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,version_major,
                    version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                VALUES (?,?,?,?,'1.0.0',1,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1','{}'::jsonb,repeat('a',64),'PG_JSONB_TEXT_V1_SHA256')
                """,f.model(),f.tenant(),f.project(),f.type());
        jdbc.update("""
                INSERT INTO ota_firmware(id,tenant_id,project_id,created_by,device_type_id,thing_model_version_id,
                    product_key,firmware_version,schema_digest_algorithm,schema_digest,schema_profile,status,revision,created_at)
                VALUES (?,?,?,?,?,?,'upload_product','1','PG_JSONB_TEXT_V1_SHA256',repeat('a',64),
                    'TC_PROPERTY_COMPOSITE_V1','DRAFT',0,now())
                """,f.firmware(),f.tenant(),f.project(),f.account(),f.type(),f.model());
        return f;
    }

    /** 固定远期恢复时间避免本例未领取会话干扰其他测试，需恢复时显式提前。 */
    private OtaUploadSession create(Fixture f) {
        return create(f,Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /** 显式创建时钟用于验证JVM与DB时钟短暂偏差，不修改已冻结身份。 */
    private OtaUploadSession create(Fixture f,Instant now) {
        UUID id=Uuid7.generate();
        OtaUploadSession session=new OtaUploadSession(id,f.tenant(),f.project(),f.firmware(),f.account(),Uuid7.generate(),
                0,artifactSize,0,artifactSha256,actualBucket==null?"ota-test-bucket":actualBucket,"attempt/"+id,"WAITING",null,null,
                id.toString().replace("-","").repeat(2),"b".repeat(64),now,now.plusSeconds(3600),null,null,null,null,null,
                now.plusSeconds(3600),null);
        upload(f,repo->{repo.create(session);return true;});
        return session;
    }

    /** 当前revision来自实际持久状态，避免用构造时快照掩盖CAS语义。 */
    private OtaUploadSession current(Fixture f,OtaUploadSession s) {
        return upload(f,repo->repo.find(f.project(),f.firmware(),s.id(),false).orElseThrow());
    }

    /** 普通app角色调用实际受限函数，未声称这里执行真实对象网络。 */
    private String cleanBatch(Fixture f,UUID token) {
        return plain(jdbc->jdbc.queryForObject("SELECT coalesce(blocked_reason,'OK') FROM ota_project_cleanup_batch(?,?,1,?)",
                String.class,f.tenant(),f.project(),token));
    }

    /** 在真实普通连接的原事务中建立项目RLS。 */
    private static <T> T app(Fixture f,Function<JdbcTemplate,T> work) {
        return plain(jdbc->{jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,f.tenant().toString());
            jdbc.queryForObject("SELECT set_config('app.project_id',?,true)",String.class,f.project().toString());
            return work.apply(jdbc);});
    }

    /** 仓储所有普通动作进入实际事务；不通过owner验证RLS。 */
    private static <T> T upload(Fixture f,Function<JdbcOtaUploadRepository,T> work) {
        return app(f,jdbc->work.apply(new JdbcOtaUploadRepository(jdbc)));
    }

    /** 无默认scope普通连接用于证明受限后台claim与查询不可见边界。 */
    private static <T> T plain(Function<JdbcTemplate,T> work) {
        var source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),APP_ROLE,APP_ROLE_PASSWORD);
        var jdbc=new JdbcTemplate(source);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(5);
        return transaction.execute(status -> {
            jdbc.execute("SET LOCAL lock_timeout='5s'");
            return work.apply(jdbc);
        });
    }

    /** owner限于夹具与独立最终观察。 */
    private static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
    }

    /** 本例独立父事实身份。 */
    private record Fixture(UUID tenant,UUID project,UUID account,UUID type,UUID model,UUID firmware) { }
}
