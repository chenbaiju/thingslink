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

/** 下载授权真实PG状态和额度边界，不代表设备已收到或下载字节。 */
class OtaDownloadAuthorizationPersistenceTests extends AbstractIntegrationTest {
    /** 每例隔离项目。 */
    private final List<Fixture> fixtures = new ArrayList<>();

    /** owner按子先父清理全部夹具，完整删除事务不留下临时断图。 */
    @AfterEach void cleanup() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        for (Fixture f : fixtures) {
            new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status -> {
                var j = new JdbcTemplate(source);
                for (String table : List.of("ota_job_progress_outbox", "ota_job_progress", "ota_job_expiry", "ota_job_execution_origin", "ota_download_transport", "ota_download_authorization_outbox", "ota_download_authorization", "ota_download_bandwidth", "ota_download_request_outbox", "ota_download_request", "ota_notification_transport", "ota_notification_delivery", "ota_job_dispatch_outbox", "ota_job_transition", "ota_batch_transition", "ota_campaign_outbox", "ota_campaign_transition", "ota_device_job",
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

    /** 所有测试明确使用原始阶段期限，不刷新原通知事实。 */
    private int notificationDeadlineSeconds = 120;
    /** 独立用例显式冻结并发上限。 */
    private int concurrentDownloads = 1;
    /** 每秒新授权字节预算，测试不宣称物理网络限速。 */
    private long bytesPerSecond = 1;
    /** 真实上传数据库证据与清单一致的字节数。 */
    private long artifactSize = 1;

    /** 封存必须同时推进作业、独立历史和非秘密队列，原通知被取代。 */
    @Test void sealsCiphertextAndTransitionsJobAtomically() {
        Fixture f = accepted();
        var initial = claimAuthorization();
        var signing = authorize(f, r -> r.reserveSigning(initial, initial.jobRevision()).orElseThrow());
        assertThat(signing.status()).isEqualTo("SIGNING");
        assertThat(signing.slotRetainUntil()).isEqualTo(signing.responseExpiresAt().plusSeconds(6));
        assertThat(signing.slotRetainUntil()).isBeforeOrEqualTo(initial.request().originalDeadline());
        assertThat(OtaDownloadAuthorizationPersistenceTests.<java.util.Optional<com.things.link.ota.domain.OtaDownloadAuthorizationRepository.Claim>>authorize(f, r -> r.reserveSigning(signing, signing.jobRevision()))).isEmpty();
        assertThat(OtaDownloadAuthorizationPersistenceTests.<Boolean>authorize(f, r -> r.seal(signing,
                signing.jobRevision(), "key-v1", new byte[12], new byte[32], "a".repeat(64), topic()))).isTrue();
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?", String.class, signing.request().jobId())).isEqualTo("DOWNLOADING");
        assertThat(owner().queryForObject("SELECT status FROM ota_notification_delivery WHERE job_id=?", String.class, signing.request().jobId())).isEqualTo("SUPERSEDED");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_download_authorization_outbox WHERE authorization_id=? AND published_at IS NULL", Integer.class, signing.authorizationId())).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_transition WHERE job_id=?", Integer.class, signing.request().jobId())).isEqualTo(2);
        assertThatThrownBy(() -> app(f, j -> j.update("UPDATE ota_download_authorization SET ciphertext=? WHERE id=?", new byte[33], signing.authorizationId())))
                .hasStackTraceContaining("ciphertext immutable");
        UUID token = Uuid7.generate();
        owner().update("UPDATE sys_project SET status='PURGING',lifecycle_generation=1,deleted_at=now()-interval '31 days',"
                + "cleanup_stage='OTA',cleanup_started_at=now(),cleanup_next_attempt_at=now(),cleanup_lease_token=?,cleanup_lease_until=now()+interval '120 seconds' WHERE id=?", token, f.project());
        assertThat(cleanBatch(f, token)).isEqualTo("OTA_CAMPAIGN_EXECUTION_PENDING");
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware_upload_session WHERE firmware_id=?", String.class, f.firmware())).isEqualTo("ADOPTED");
    }

    /** 一次签址失租只能收未知，保留额度且手工恢复不能重签。 */
    @Test void recoversExpiredSigningAsUnknownWithoutReissuing() {
        Fixture f = accepted();
        var first = claimAuthorization();
        var signing = authorize(f, r -> r.reserveSigning(first, first.jobRevision()).orElseThrow());
        owner().update("UPDATE ota_download_authorization SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", signing.authorizationId());
        var recovered = claimAuthorization();
        assertThat(recovered.leaseToken()).isNotEqualTo(signing.leaseToken());
        assertThat(OtaDownloadAuthorizationPersistenceTests.<Boolean>authorize(f, r -> r.recoverExpired(recovered))).isTrue();
        assertThat(owner().queryForObject("SELECT status FROM ota_download_authorization WHERE id=?", String.class, signing.authorizationId())).isEqualTo("UNKNOWN");
        assertThat(owner().queryForObject("SELECT slot_retain_until FROM ota_download_authorization WHERE id=?", java.sql.Timestamp.class, signing.authorizationId()).toInstant()).isEqualTo(signing.slotRetainUntil());
        assertThat(OtaDownloadAuthorizationPersistenceTests.<Boolean>runtime(f, r -> r.resume(f.project(), signing.request().campaignId(), 3, f.account(), "不得重签"))).isFalse();
        assertThat(OtaDownloadAuthorizationPersistenceTests.<Boolean>authorize(f, r -> r.seal(signing, signing.jobRevision(), "key-v1", new byte[12], new byte[32], "a".repeat(64), topic()))).isFalse();
        assertThat(owner().queryForObject("SELECT revision FROM ota_download_bandwidth WHERE campaign_id=?", Long.class, signing.request().campaignId())).isEqualTo(1);
    }

    /** 原可用窗口不足不签发，直接记录耗尽并保留设备作业。 */
    @Test void exhaustsInsufficientWindowWithoutSigningBudget() {
        notificationDeadlineSeconds = 15;
        Fixture f = accepted();
        var first = claimAuthorization();
        assertThat(OtaDownloadAuthorizationPersistenceTests.<java.util.Optional<com.things.link.ota.domain.OtaDownloadAuthorizationRepository.Claim>>authorize(f, r -> r.reserveSigning(first, first.jobRevision()))).isEmpty();
        assertThat(owner().queryForObject("SELECT status FROM ota_download_authorization WHERE id=?", String.class, first.authorizationId())).isEqualTo("EXHAUSTED");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_download_bandwidth WHERE campaign_id=?", Integer.class, first.request().campaignId())).isZero();
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?", String.class, first.request().jobId())).isEqualTo("DISPATCHED");
    }

    /** 无带宽扣款的直接签址和缺作业的密文半图均不能提交。 */
    @Test void rejectsDirectReservationAndHalfSealedGraph() {
        Fixture f = accepted();
        var first = claimAuthorization();
        assertThatThrownBy(() -> app(f, j -> j.update("WITH t AS (SELECT clock_timestamp() at_time) UPDATE ota_download_authorization a"
                + " SET status='SIGNING',revision=revision+1,signing_lease_token=lease_token,signing_reserved_at=t.at_time,"
                + "response_expires_at=date_trunc('second',t.at_time)+interval '54 seconds',"
                + "slot_retain_until=date_trunc('second',t.at_time)+interval '60 seconds',artifact_size=1,updated_at=t.at_time"
                + " FROM t WHERE a.id=?", first.authorizationId()))).hasStackTraceContaining("bandwidth reservation count mismatch");
        var signing = authorize(f, r -> r.reserveSigning(first, first.jobRevision()).orElseThrow());
        assertThatThrownBy(() -> app(f, j -> j.update("UPDATE ota_download_authorization SET status='SEALED',revision=revision+1,"
                + "key_version='key-v1',nonce=?,ciphertext=?,plaintext_sha256=repeat('a',64),topic=?,sealed_at=clock_timestamp(),"
                + "updated_at=clock_timestamp(),lease_token=NULL,lease_until=NULL WHERE id=?", new byte[12], new byte[32], topic(), signing.authorizationId())))
                .hasStackTraceContaining("sealed job graph incomplete");
        assertThat(authorize(f, r -> r.findCurrent(signing.authorizationId()).orElseThrow()).authorizationId()).isEqualTo(signing.authorizationId());
        Fixture other = dispatched();
        assertThat(OtaDownloadAuthorizationPersistenceTests.<java.util.Optional<OtaDownloadAuthorizationRepository.Claim>>authorize(other,
                r -> r.findCurrent(signing.authorizationId()))).isEmpty();
        assertThat(OtaDownloadAuthorizationPersistenceTests.<Integer>app(other, j -> j.queryForObject("SELECT count(*) FROM ota_download_authorization WHERE id=?", Integer.class, signing.authorizationId()))).isZero();
    }

    /** 暂停后立即恢复不能采用旧发送回执，迟到观察只追加一次。 */
    @Test void pauseAndResumeCannotReviveOldTransport() {
        Fixture f = accepted();
        var waiting = claimAuthorization();
        var signing = authorize(f, r -> r.reserveSigning(waiting, waiting.jobRevision()).orElseThrow());
        authorize(f, r -> r.seal(signing, signing.jobRevision(), "key-v1", new byte[12], new byte[32], "a".repeat(64), topic()));
        var sealed = claimAuthorization();
        var transport = authorize(f, r -> r.reserveSend(sealed).orElseThrow());
        var flight = authorize(f, r -> r.authoritativeClaim(sealed.authorizationId(), sealed.leaseToken()).orElseThrow());
        runtime(f, r -> r.pause(f.project(), waiting.request().campaignId(), 2, f.account(), "暂停交付"));
        runtime(f, r -> r.resume(f.project(), waiting.request().campaignId(), 3, f.account(), "恢复活动"));
        assertThat(OtaDownloadAuthorizationPersistenceTests.<Boolean>authorize(f, r -> r.recordObservation(transport, "BROKER_ACCEPTED", 200, null))).isTrue();
        assertThat(OtaDownloadAuthorizationPersistenceTests.<Boolean>authorize(f, r -> r.recordObservation(transport, "BROKER_ACCEPTED", 200, null))).isFalse();
        assertThat(OtaDownloadAuthorizationPersistenceTests.<Boolean>authorize(f, r -> r.settleCurrent(flight, transport.id()))).isFalse();
        var recovery = claimAuthorization();
        assertThat(OtaDownloadAuthorizationPersistenceTests.<Boolean>authorize(f, r -> r.recoverExpired(recovery))).isTrue();
        assertThat(owner().queryForObject("SELECT status FROM ota_download_authorization WHERE id=?", String.class, sealed.authorizationId())).isEqualTo("SEALED");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_download_transport WHERE authorization_id=? AND outcome='BROKER_ACCEPTED' AND lease_expired_at IS NOT NULL", Integer.class, sealed.authorizationId())).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT transport_count FROM ota_download_authorization WHERE id=?", Integer.class, sealed.authorizationId())).isEqualTo(1);
    }

    /** 并发槽不足仅退避，未知签址仍保留原上界且不退回额度。 */
    @Test void concurrentSlotDefersWithoutChargingAndUnknownRetainsSlot() {
        bytesPerSecond = 1_073_741_824L;
        Fixture f = twoAccepted();
        var first = claimAuthorization();
        var signing = authorize(f, r -> r.reserveSigning(first,first.jobRevision()).orElseThrow());
        var second = claimAuthorization();
        assertThat(second.authorizationId()).isNotEqualTo(first.authorizationId());
        assertThat(OtaDownloadAuthorizationPersistenceTests.<java.util.Optional<OtaDownloadAuthorizationRepository.Claim>>authorize(f,
                r -> r.reserveSigning(second,second.jobRevision()))).isEmpty();
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_download_authorization WHERE campaign_id=? AND signing_lease_token IS NOT NULL",
                Integer.class, first.request().campaignId())).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT revision FROM ota_download_bandwidth WHERE campaign_id=?",Long.class,
                first.request().campaignId())).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT status FROM ota_download_authorization WHERE id=?",String.class,second.authorizationId())).isEqualTo("WAITING");
        authorize(f,r -> r.signingUnknown(signing,"DOWNLOAD_SIGNING_UNKNOWN"));
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_download_authorization WHERE campaign_id=? AND status='UNKNOWN'"
                + " AND slot_retain_until>clock_timestamp()",Integer.class,first.request().campaignId())).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT revision FROM ota_download_bandwidth WHERE campaign_id=?",Long.class,
                first.request().campaignId())).isEqualTo(1);
    }

    /** 额度允许首次一包，后续因持久虚拟时刻退避，不再次扣费。 */
    @Test void bandwidthVirtualClockDefersWithoutDoubleCharging() {
        concurrentDownloads = 2;
        artifactSize = 1024;
        bytesPerSecond = 1;
        Fixture f = twoAccepted();
        var first = claimAuthorization();
        var signing = authorize(f,r -> r.reserveSigning(first,first.jobRevision()).orElseThrow());
        var before = owner().queryForMap("SELECT next_available_at,revision FROM ota_download_bandwidth WHERE campaign_id=?",first.request().campaignId());
        assertThat(((java.sql.Timestamp)before.get("next_available_at")).toInstant()).isEqualTo(signing.signingReservedAt().plusSeconds(1024));
        var second = claimAuthorization();
        assertThat(OtaDownloadAuthorizationPersistenceTests.<java.util.Optional<OtaDownloadAuthorizationRepository.Claim>>authorize(f,
                r -> r.reserveSigning(second,second.jobRevision()))).isEmpty();
        assertThat(owner().queryForMap("SELECT next_available_at,revision FROM ota_download_bandwidth WHERE campaign_id=?",first.request().campaignId())).isEqualTo(before);
        assertThat(owner().queryForObject("SELECT signing_reserved_at IS NULL AND transport_count=0 AND lease_token IS NULL"
                + " FROM ota_download_authorization WHERE id=?",Boolean.class,second.authorizationId())).isTrue();
    }

    /** 同一冻结批次中的两个真实作业分别接纳原认证申请。 */
    private Fixture twoAccepted() {
        Fixture f = ready();
        var campaign = scheduled(f,2);
        runtime(f,r -> r.start(f.project(),campaign.id(),1,f.account()));
        for (int index=0;index<2;index++) {
            var pending = claimRuntime();
            runtime(f,r -> r.admit(pending,1,1,OtaExecutionReportFixture.HASH,Instant.now()));
            var context = requests(f,r -> r.locate(pending.jobId()).orElseThrow());
            var accepted = request(f,context);
            requests(f,r -> r.create(accepted,context.jobRevision()));
        }
        return f;
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
    private OtaCampaign scheduled(Fixture f, int count) {
        var ids = devices(f, count);
        var c = campaign(f, ids, count);
        campaignRun(f, r -> r.schedule(0, c, targets(f, ids), count, f.account(), c.createdAt()));
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
        upload(f,r->r.recordVersion(u,token,"version-one")); upload(f,r->r.finishVerified(u,token));
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
                0,artifactSize,0,"a".repeat(64),"ota-test-bucket","attempt/"+id,"WAITING",null,null,
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
