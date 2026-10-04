package com.things.link.bootstrap.ota;

import com.things.link.ota.domain.OtaDownloadRequestRepository;
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

/** 认证申请接纳持久边界，不代表当前设备已得到下载授权。 */
class OtaDownloadRequestPersistenceTests extends AbstractIntegrationTest {
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

    /** 原作业期限按每例显式冻结，不随下载申请延长。 */
    private int notificationDeadlineSeconds = 120;

    /** 接纳一份请求和非秘密排队事实，重读不刷新时间或重复排队。 */
    @Test void persistsImmutableReceiptAndReadsAfterPause() {
        Fixture f = dispatched();
        var context = context(f);
        var request = request(f, context);
        assertThat(OtaDownloadRequestPersistenceTests.<Boolean>requests(f, r -> r.create(request, context.jobRevision()))).isTrue();
        var read = requests(f, r -> r.find(request.deviceId(), request.requestId()).orElseThrow());
        assertThat(read.acceptedAt()).isEqualTo(request.acceptedAt());
        assertThat(read.brokerReceivedAt()).isEqualTo(request.brokerReceivedAt());
        byte[] copy = read.canonical(); copy[0] = 0;
        assertThat(read.canonical()).isEqualTo(request.canonical());
        runtime(f, r -> r.pause(f.project(), context.campaignId(), 2, f.account(), "申请后暂停"));
        assertThat(requests(f, r -> r.findByJobAttempt(context.jobId(), 1).orElseThrow()).acceptedAt()).isEqualTo(request.acceptedAt());
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_download_request_outbox WHERE receipt_id=? AND published_at IS NULL", Integer.class, request.id())).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?", String.class, context.jobId())).isEqualTo("DISPATCHED");
        assertThatThrownBy(() -> app(f, j -> j.update("UPDATE ota_download_request SET accepted_at=now() WHERE id=?", request.id())))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> app(f, j -> j.update("UPDATE ota_download_request_outbox SET published_at=now() WHERE receipt_id=?", request.id())))
                .hasStackTraceContaining("permission denied");
        UUID cleanupToken = Uuid7.generate();
        owner().update("UPDATE sys_project SET status='PURGING',lifecycle_generation=1,deleted_at=now()-interval '31 days',"
                + "cleanup_stage='OTA',cleanup_started_at=now(),cleanup_next_attempt_at=now(),cleanup_lease_token=?,"
                + "cleanup_lease_until=now()+interval '120 seconds' WHERE id=?", cleanupToken, f.project());
        assertThat(cleanBatch(f, cleanupToken)).isEqualTo("OTA_CAMPAIGN_EXECUTION_PENDING");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_download_request WHERE id=?", Integer.class, request.id())).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware_upload_session WHERE firmware_id=?", String.class, f.firmware())).isEqualTo("ADOPTED");
    }

    /** 一作业尝试不能换请求ID占用多份位置；直接断图提交回滚，范围外不能定位。 */
    @Test void rejectsDuplicateAttemptAndMissingQueueFact() {
        Fixture f = dispatched();
        var context = context(f);
        var first = request(f, context);
        assertThatThrownBy(() -> app(f, j -> insertRaw(j, first, context.jobRevision())))
                .hasStackTraceContaining("queue fact incomplete");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_download_request WHERE job_id=?", Integer.class, context.jobId())).isZero();
        requests(f, r -> r.create(first, context.jobRevision()));
        var second = request(f, context);
        assertThatThrownBy(() -> requests(f, r -> r.create(second, context.jobRevision())))
                .hasStackTraceContaining("ota_download_request_job_attempt_uk");
        Fixture other = seed();
        assertThat(OtaDownloadRequestPersistenceTests.<java.util.Optional<OtaDownloadRequestRepository.JobContext>>requests(other,
                r -> r.locate(context.jobId()))).isEmpty();
        assertThat(OtaDownloadRequestPersistenceTests.<java.util.Optional<OtaDownloadRequestRepository.Request>>requests(other,
                r -> r.find(first.deviceId(), first.requestId()))).isEmpty();
    }

    /** CAS、原期限与闭集正文都由数据库再检查；不以请求新ID刷新过期资格。 */
    @Test void rejectsStaleRevisionExpiredDeadlineAndCanonicalSubstitution() {
        notificationDeadlineSeconds = 3;
        Fixture f = dispatched();
        var context = context(f);
        var request = request(f, context);
        assertThat(OtaDownloadRequestPersistenceTests.<Boolean>requests(f, r -> r.create(request, context.jobRevision() - 1))).isFalse();
        var badBytes = new String(request.canonical(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("\"attemptNo\":1", "\"attemptNo\":\"1\"").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var malformed = new OtaDownloadRequestRepository.Request(request.id(), request.tenantId(), request.projectId(),
                request.deviceId(), request.credentialVersion(), request.requestId(), request.jobId(), request.campaignId(),
                request.firmwareId(), request.attemptNo(), request.manifestSha256(), badBytes, hash(badBytes),
                request.originalDeadline(), request.brokerReceivedAt(), request.acceptedAt(), request.reportRevision(), request.reportHash());
        assertThatThrownBy(() -> app(f, j -> insertRaw(j, malformed, context.jobRevision())))
                .hasStackTraceContaining("canonical identity mismatch");
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(4)).until(() -> !Instant.now().isBefore(context.originalDeadline()));
        assertThat(OtaDownloadRequestPersistenceTests.<Boolean>requests(f, r -> r.create(request, context.jobRevision()))).isFalse();
        assertThatThrownBy(() -> app(f, j -> insertRaw(j, request, context.jobRevision())))
                .hasStackTraceContaining("current job fence rejected");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_download_request WHERE job_id=?", Integer.class, context.jobId())).isZero();
    }

    /** 已认证DISPATCHED专用暂停不需要待准入租约，错误目标及NULL活动修订不能触发。 */
    @Test void pausesOnlyExactCurrentJobAndRevokesNotificationCapabilities() {
        Fixture f = dispatched();
        var context = context(f);
        UUID deliveryToken = Uuid7.generate();
        owner().update("UPDATE ota_notification_delivery SET lease_token=?,lease_until=clock_timestamp()+interval '15 seconds' WHERE job_id=?", deliveryToken, context.jobId());
        assertThat(OtaDownloadRequestPersistenceTests.<Boolean>app(f, j -> j.queryForObject(
                "SELECT ota_download_request_safety_pause(?,?,?,?,?,?,?,?,?,?,?,NULL,?)", Boolean.class,
                context.tenantId(), context.projectId(), context.deviceId(), context.jobId(), context.campaignId(), context.firmwareId(),
                context.attemptNo(), context.credentialVersion(), context.manifestSha256(), context.jobRevision(),
                java.sql.Timestamp.from(context.originalDeadline()), "INVALID_NULL_REVISION"))).isFalse();
        var wrongDevice = new OtaDownloadRequestRepository.JobContext(context.tenantId(), context.projectId(), Uuid7.generate(),
                context.campaignId(), context.firmwareId(), context.jobId(), context.jobStatus(), context.jobRevision(),
                context.attemptNo(), context.credentialVersion(), context.manifestSha256(), context.originalDeadline());
        assertThat(OtaDownloadRequestPersistenceTests.<Boolean>requests(f, r -> r.safetyPause(wrongDevice, 2, "WRONG_DEVICE"))).isFalse();
        assertThat(OtaDownloadRequestPersistenceTests.<Boolean>requests(f, r -> r.safetyPause(context, 2, "SIGNATURE_INVALID"))).isTrue();
        var state = runtime(f, r -> r.read(f.project(), context.campaignId()).orElseThrow());
        assertThat(state.pauseKind()).isEqualTo("SECURITY");
        assertThat(state.pauseJobId()).isEqualTo(context.jobId());
        assertThat(owner().queryForObject("SELECT lease_token IS NULL FROM ota_notification_delivery WHERE job_id=?", Boolean.class, context.jobId())).isTrue();
        assertThat(owner().queryForObject("SELECT lease_token IS NULL AND status='DISPATCHED' FROM ota_device_job WHERE id=?", Boolean.class, context.jobId())).isTrue();
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_download_request WHERE job_id=?", Integer.class, context.jobId())).isZero();
    }

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
                + "],\"executionPolicy\":{\"stageTimeoutSeconds\":{\"DISPATCHED\":" + notificationDeadlineSeconds + "}},\"firmwareId\":\""
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
        OtaPublication p=new OtaPublication(Uuid7.generate(),f.tenant(),f.project(),f.firmware(),u.id(),f.account(),Uuid7.generate(),0,0,verified.revision(),("{\"deviceTypeId\":\""+f.type()+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8),new byte[]{2},"PREPARED",0,null,null,null,null,null,null,now,now);
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
                0,1,0,"a".repeat(64),"ota-test-bucket","attempt/"+id,"WAITING",null,null,
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
        return new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status->work.apply(jdbc));
    }

    /** owner限于夹具与独立最终观察。 */
    private static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
    }

    /** 本例独立父事实身份。 */
    private record Fixture(UUID tenant,UUID project,UUID account,UUID type,UUID model,UUID firmware) { }
}
