package com.things.link.bootstrap.ota;

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

/** 独立通知预算与迟到观察真实PG边界，不声称HTTP已发出。 */
class OtaNotificationPersistenceTests extends AbstractIntegrationTest {
    /** 每例隔离项目。 */
    private final List<Fixture> fixtures = new ArrayList<>();

    /** owner按子先父清理全部夹具，完整删除事务不留下临时断图。 */
    @AfterEach void cleanup() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        for (Fixture f : fixtures) {
            new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status -> {
                var j = new JdbcTemplate(source);
                for (String table : List.of("ota_job_progress_outbox", "ota_job_progress", "ota_job_expiry", "ota_job_execution_origin", "ota_notification_transport", "ota_notification_delivery", "ota_job_dispatch_outbox", "ota_job_transition", "ota_batch_transition", "ota_campaign_outbox", "ota_campaign_transition", "ota_device_job",
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

    /** 本例显式阶段预算，独立于HTTP重试次数。 */
    private int notificationDeadlineSeconds = 120;

    /** 三次预留上限及5/20秒退避，全部期限沿用原作业。 */
    @Test void exhaustsThreeAttemptsWithoutRefreshingJobDeadline() {
        Fixture f = notification();
        var claim = notificationClaim();
        var originalDeadline = claim.deadline();
        byte[] body = body(claim);
        for (int attempt = 1; attempt <= 3; attempt++) {
            var current = claim;
            var transport = notification(f, r -> r.reserveSend(current, "tc/v1/project/device/down/ota/available", body).orElseThrow());
            assertThat(transport.transportNo()).isEqualTo(attempt);
            assertThat(transport.reservationToken()).isNotEqualTo(transport.deliveryLeaseToken());
            notification(f, r -> r.recordObservation(transport, "REJECTED", 503, "HTTP_REJECTED"));
            var reserved = current(f, current);
            assertThat(OtaNotificationPersistenceTests.<Boolean>notification(f, r -> r.settleCurrent(reserved, transport.id()))).isTrue();
            if (attempt < 3) {
                int delay = attempt == 1 ? 5 : 20;
                assertThat(owner().queryForObject("SELECT extract(epoch FROM next_attempt_at-updated_at)::int"
                        + " FROM ota_notification_delivery WHERE event_id=?", Integer.class, claim.eventId())).isEqualTo(delay);
                awaitDue(claim.eventId(), 26);
                claim = notificationClaim();
            }
        }
        assertThat(owner().queryForObject("SELECT status FROM ota_notification_delivery WHERE event_id=?", String.class, claim.eventId())).isEqualTo("EXHAUSTED");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_notification_transport WHERE event_id=?", Integer.class, claim.eventId())).isEqualTo(3);
        assertThat(owner().queryForObject("SELECT deadline_at FROM ota_device_job WHERE id=?", java.sql.Timestamp.class, claim.jobId()).toInstant()).isEqualTo(originalDeadline);
        var finalCampaignId = claim.campaignId();
        var campaign = runtime(f, r -> r.read(f.project(), finalCampaignId).orElseThrow());
        assertThat(campaign.pauseKind()).isEqualTo("AUTO");
        assertThat(OtaNotificationPersistenceTests.<Boolean>runtime(f, r -> r.resume(f.project(), campaign.campaign().id(),
                campaign.campaign().stateVersion(), f.account(), "不能重置耗尽"))).isFalse();
    }

    /** 失租后响应只补观察，下一次传输继续原event、原字节且不窃取新租约。 */
    @Test void observesLateResponseWithoutAdoptingExpiredTransmission() {
        Fixture f = notification();
        var first = notificationClaim();
        byte[] body = body(first);
        var transport = notification(f, r -> r.reserveSend(first, "tc/v1/project/device/down/ota/available", body).orElseThrow());
        owner().update("UPDATE ota_notification_delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE event_id=?", first.eventId());
        var recovered = notificationClaim();
        assertThat(OtaNotificationPersistenceTests.<Boolean>notification(f, r -> r.recoverExpired(recovered))).isTrue();
        assertThat(OtaNotificationPersistenceTests.<Boolean>notification(f, r -> r.recordObservation(transport, "BROKER_ACCEPTED", 200, null))).isTrue();
        assertThat(OtaNotificationPersistenceTests.<Boolean>notification(f, r -> r.recordObservation(transport, "BROKER_ACCEPTED", 200, null))).isFalse();
        assertThat(OtaNotificationPersistenceTests.<Boolean>notification(f, r -> r.settleCurrent(first, transport.id()))).isFalse();
        assertThat(owner().queryForObject("SELECT lease_expired_at IS NOT NULL AND outcome='BROKER_ACCEPTED'"
                + " FROM ota_notification_transport WHERE id=?", Boolean.class, transport.id())).isTrue();
        awaitDue(first.eventId(), 6);
        var next = notificationClaim();
        assertThat(OtaNotificationPersistenceTests.<java.util.Optional<OtaNotificationRepository.Transport>>notification(f,
                r -> r.reserveSend(next, "tc/v1/project/changed/down/ota/available", body))).isEmpty();
        var second = notification(f, r -> r.reserveSend(next, "tc/v1/project/device/down/ota/available", body).orElseThrow());
        assertThat(second.canonical()).isEqualTo(body);
        assertThat(second.eventId()).isEqualTo(transport.eventId());
        assertThat(OtaNotificationPersistenceTests.<Boolean>notification(f,
                r -> r.recordObservation(second, "UNKNOWN", 200, "RESPONSE_TRUNCATED"))).isTrue();
        assertThat(OtaNotificationPersistenceTests.<Boolean>notification(f,
                r -> r.recordObservation(second, "BROKER_ACCEPTED", 200, null))).isFalse();
        assertThatThrownBy(() -> app(f, j -> j.update("UPDATE ota_notification_transport SET outcome='BROKER_ACCEPTED',error_code=NULL WHERE id=?", second.id())))
                .hasStackTraceContaining("observation immutable");
    }

    /** 原期限到达的在途通知耗尽，不把人工暂停降级为自动暂停，迟到回执不重开。 */
    @Test void preservesManualPauseWhenOriginalDeadlineExpires() {
        notificationDeadlineSeconds = 3;
        Fixture f = notification();
        var claim = notificationClaim();
        var transport = notification(f, r -> r.reserveSend(claim, "tc/v1/project/device/down/ota/available", body(claim)).orElseThrow());
        var reserved = current(f, claim);
        runtime(f, r -> r.pause(f.project(), claim.campaignId(), 2, f.account(), "原人工暂停"));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(4)).until(() -> !Instant.now().isBefore(claim.deadline()));
        assertThat(OtaNotificationPersistenceTests.<Boolean>notification(f, r -> r.pauseSecurity(reserved, "CONFIGURATION"))).isFalse();
        var expired = notificationClaim();
        assertThat(OtaNotificationPersistenceTests.<Boolean>notification(f, r -> r.exhaustDue(expired, "DEADLINE"))).isTrue();
        notification(f, r -> r.recordObservation(transport, "BROKER_ACCEPTED", 200, null));
        assertThat(runtime(f, r -> r.read(f.project(), claim.campaignId()).orElseThrow()).pauseKind()).isEqualTo("MANUAL");
        assertThat(owner().queryForObject("SELECT status FROM ota_notification_delivery WHERE event_id=?", String.class, claim.eventId())).isEqualTo("EXHAUSTED");
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?", String.class, claim.jobId())).isEqualTo("DISPATCHED");
    }

    /** 直接SQL验证不可变期限、空修订与半组回执，不依赖HTTP校验代替数据库守卫。 */
    @Test void rejectsForgedRevisionDeadlineAndIncompleteResponse() {
        Fixture f = notification();
        var claim = notificationClaim();
        assertThatThrownBy(() -> app(f, j -> j.update("UPDATE ota_notification_delivery SET deadline_at=deadline_at+interval '1 second' WHERE event_id=?", claim.eventId())))
                .hasStackTraceContaining("identity or payload immutable");
        assertThatThrownBy(() -> app(f, j -> j.update("INSERT INTO ota_notification_delivery"
                + "(event_id,tenant_id,project_id,campaign_id,job_id,device_id,firmware_id,credential_version,job_attempt_no,manifest_sha256,deadline_at,created_at,updated_at)"
                + " SELECT gen_random_uuid(),tenant_id,project_id,campaign_id,job_id,device_id,firmware_id,credential_version,job_attempt_no,manifest_sha256,deadline_at,created_at,updated_at"
                + " FROM ota_notification_delivery WHERE event_id=?", claim.eventId())))
                .hasStackTraceContaining("ota_notification_delivery_outbox_fk");
        assertThatThrownBy(() -> notification(f, r -> {
            r.reserveSend(claim, "tc/v1/project/device/down/ota/available", body(claim)).orElseThrow();
            throw new IllegalStateException("本例事务回滚");
        })).hasMessageContaining("本例事务回滚");
        assertThat(owner().queryForObject("SELECT transport_count FROM ota_notification_delivery WHERE event_id=?", Integer.class, claim.eventId())).isZero();
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_notification_transport WHERE event_id=?", Integer.class, claim.eventId())).isZero();
        var nullRevision = app(f, j -> j.queryForList("SELECT * FROM ota_notification_reserve(?,?,NULL,?,?)",
                claim.eventId(), claim.leaseToken(), "tc/v1/project/device/down/ota/available", body(claim)));
        assertThat(nullRevision).isEmpty();
        var transport = notification(f, r -> r.reserveSend(claim, "tc/v1/project/device/down/ota/available", body(claim)).orElseThrow());
        assertThatThrownBy(() -> app(f, j -> j.update("UPDATE ota_notification_transport SET observed_at=clock_timestamp() WHERE id=?", transport.id())))
                .hasStackTraceContaining("ota_notification_transport_");
        assertThatThrownBy(() -> app(f, j -> j.update("UPDATE ota_notification_delivery SET status='EXHAUSTED',revision=revision+1,"
                + "exhausted_at=clock_timestamp(),reason='FORGED',lease_token=NULL,lease_until=NULL WHERE event_id=?", claim.eventId())))
                .hasStackTraceContaining("premature exhaustion");
        assertThat(OtaNotificationPersistenceTests.<java.util.Optional<OtaNotificationRepository.Transport>>plain(j ->
                new JdbcOtaNotificationRepository(j).authoritativeTransport(transport.id(), Uuid7.generate()))).isEmpty();
        Fixture other = seed();
        assertThat(OtaNotificationPersistenceTests.<Boolean>notification(other, r -> r.recordObservation(transport, "BROKER_ACCEPTED", 200, null))).isFalse();
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_notification_transport WHERE event_id=?", Integer.class, claim.eventId())).isEqualTo(1);
    }

    /** 暂停立即撤销旧传输能力，恢复不能使在途旧HTTP回执重新成为当前权利。 */
    @Test void neverRevivesOldTransmissionAcrossImmediatePauseAndResume() {
        Fixture f = notification();
        var claim = notificationClaim();
        var transport = notification(f, r -> r.reserveSend(claim, "tc/v1/project/device/down/ota/available", body(claim)).orElseThrow());
        var reserved = current(f, claim);
        runtime(f, r -> r.pause(f.project(), claim.campaignId(), 2, f.account(), "立即暂停"));
        runtime(f, r -> r.resume(f.project(), claim.campaignId(), 3, f.account(), "立即恢复"));
        assertThat(OtaNotificationPersistenceTests.<Boolean>notification(f,
                r -> r.recordObservation(transport, "BROKER_ACCEPTED", 200, null))).isTrue();
        assertThat(OtaNotificationPersistenceTests.<Boolean>notification(f,
                r -> r.settleCurrent(reserved, transport.id()))).isFalse();
        var fresh = notificationClaim();
        assertThat(fresh.leaseToken()).isNotEqualTo(reserved.leaseToken());
        assertThat(OtaNotificationPersistenceTests.<Boolean>notification(f, r -> r.recoverExpired(fresh))).isTrue();
        assertThat(owner().queryForObject("SELECT status FROM ota_notification_delivery WHERE event_id=?", String.class, claim.eventId())).isEqualTo("RETRY_WAIT");
        assertThat(owner().queryForObject("SELECT outcome FROM ota_notification_transport WHERE id=?", String.class, transport.id())).isEqualTo("BROKER_ACCEPTED");
    }

    /** 准入产生真实数据库通知意图，密码学和网络资格另有专项。 */
    private Fixture notification() {
        Fixture f = ready();
        var c = scheduled(f, 1);
        runtime(f, r -> r.start(f.project(), c.id(), 1, f.account()));
        var claim = claimRuntime();
        runtime(f, r -> r.admit(claim, 1, 1, OtaExecutionReportFixture.HASH, Instant.now()));
        return f;
    }
    /** 真实受限领取，不建立虚假管理账号。 */
    private OtaNotificationRepository.Claim notificationClaim() {
        return plain(j -> new JdbcOtaNotificationRepository(j).claimOne().orElseThrow());
    }
    /** 预留后重读修订，不能拿预留前快照推进。 */
    private OtaNotificationRepository.Claim current(Fixture f, OtaNotificationRepository.Claim claim) {
        return notification(f, r -> r.authoritativeClaim(claim.eventId(), claim.leaseToken()).orElseThrow());
    }
    /** 当前作用域与控制锁保护完整业务事务。 */
    private static <T> T notification(Fixture f, Function<JdbcOtaNotificationRepository, T> work) {
        return app(f, j -> {
            new JdbcOtaCampaignRuntimeRepository(j).controlLock(f.tenant(), f.project());
            return work.apply(new JdbcOtaNotificationRepository(j));
        });
    }
    /** 使用真实数据库时间等待固定退避，不篡改生产预算字段。 */
    private void awaitDue(UUID event, int seconds) {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(seconds)).until(() -> owner().queryForObject(
                "SELECT next_attempt_at<=clock_timestamp() FROM ota_notification_delivery WHERE event_id=?", Boolean.class, event));
    }
    /** 精确八字段合同；完整JCS语法由codec专项证明。 */
    private byte[] body(OtaNotificationRepository.Claim c) {
        return ("{\"contractVersion\":\"tc-ota-available/v1\",\"eventId\":\"" + c.eventId()
                + "\",\"campaignId\":\"" + c.campaignId() + "\",\"jobId\":\"" + c.jobId()
                + "\",\"firmwareId\":\"" + c.firmwareId() + "\",\"attemptNo\":1,\"manifestSha256\":\"" + c.manifestSha256()
                + "\",\"deadlineAt\":\"" + c.deadline() + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
