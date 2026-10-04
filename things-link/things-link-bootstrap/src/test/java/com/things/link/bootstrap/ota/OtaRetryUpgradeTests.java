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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/** 独占0350旧库升级0360，保留真实历史耗尽暂停与原意图，不改变共享测试数据库。 */
@Testcontainers
class OtaRetryUpgradeTests {
    /** 独立数据库角色和历史版本环境。 */
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ota_retry_upgrade").withUsername("thingslink").withPassword("thingslink");
    /** 与应用相同的完整迁移位置。 */
    private static final String[] LOCATIONS={"classpath:db/migration/support","classpath:db/migration/project",
            "classpath:db/migration/device","classpath:db/migration/telemetry","classpath:db/migration/alarm",
            "classpath:db/migration/task","classpath:db/migration/rule","classpath:db/migration/iam",
            "classpath:db/migration/enduser","classpath:db/migration/export","classpath:db/migration/dashboard",
            "classpath:db/migration/ota","classpath:db/migration/integration"};
    /** 真实旧耗尽入口造成PAUSED；升级仅替换合同，不复活作业、不造新尝试或改写已存证据。 */
    @Test void upgrades0350WithoutRevivingPausedFailuresOrChangingActiveIntents() {
        flyway("20260921.0350").migrate();
        notificationDeadlineSeconds = 1;
        Fixture paused = ready();
        var campaign = scheduled(paused, 1);
        assertThat(OtaRetryUpgradeTests.<Boolean>runtime(paused, r -> r.start(paused.project(), campaign.id(), 1, paused.account()))).isTrue();
        var admission = claimRuntime();
        assertThat(OtaRetryUpgradeTests.<Boolean>runtime(paused, r -> r.admit(admission, 1, 1, OtaExecutionReportFixture.HASH, Instant.now()))).isTrue();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> owner().queryForObject(
                "SELECT deadline_at<=clock_timestamp() FROM ota_device_job WHERE id=?", Boolean.class, admission.jobId()));
        var delivery = plain(j -> new JdbcOtaNotificationRepository(j).claimOne().orElseThrow());
        boolean exhausted = app(paused, j -> {
            new JdbcOtaCampaignRuntimeRepository(j).controlLock(paused.tenant(), paused.project());
            return new JdbcOtaNotificationRepository(j).exhaustDue(delivery, "NOTIFICATION_DELIVERY_EXHAUSTED");
        });
        assertThat(exhausted).isTrue();
        assertThat(owner().queryForObject("SELECT status FROM ota_campaign WHERE id=?", String.class, campaign.id())).isEqualTo("PAUSED");
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?", String.class, admission.jobId())).isEqualTo("DISPATCHED");
        notificationDeadlineSeconds = 120;
        Fixture active = ready();
        var running = scheduled(active, 1);
        assertThat(OtaRetryUpgradeTests.<Boolean>runtime(active, r -> r.start(active.project(), running.id(), 1, active.account()))).isTrue();
        var pending = claimRuntime();
        assertThat(OtaRetryUpgradeTests.<Boolean>runtime(active, r -> r.admit(pending, 1, 1, OtaExecutionReportFixture.HASH, Instant.now()))).isTrue();
        var pausedBefore = facts(paused);
        var activeBefore = facts(active);
        var privileges = owner().queryForList("SELECT proname,proowner,proacl::text FROM pg_proc WHERE proname IN "
                + "('ota_job_begin_retry','ota_notification_exhaust','ota_download_exhaust','ota_notification_claim_one','ota_download_claim_one') ORDER BY proname");
        assertThat(flyway("20260921.0360").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(facts(paused)).isEqualTo(pausedBefore);
        assertThat(facts(active)).isEqualTo(activeBefore);
        assertThat(owner().queryForList("SELECT proname,proowner,proacl::text FROM pg_proc WHERE proname IN "
                + "('ota_job_begin_retry','ota_notification_exhaust','ota_download_exhaust','ota_notification_claim_one','ota_download_claim_one') ORDER BY proname")).isEqualTo(privileges);
        assertThat(OtaRetryUpgradeTests.<Boolean>runtime(paused, r -> r.beginRetry(paused.tenant(), paused.project(), admission.jobId(),
                "DISPATCH_TRANSIENT_FAILURE", "HISTORICAL_PAUSE_MUST_NOT_RESUME"))).isFalse();
        assertThat(plain(j -> new JdbcOtaCampaignRuntimeRepository(j).claimRetryDue()).isEmpty()).isTrue();
        assertThat(flyway("20260921.0360").migrate().migrationsExecuted).isZero();
        assertThat(facts(paused)).isEqualTo(pausedBefore);
        assertThat(facts(active)).isEqualTo(activeBefore);

        // 0360真实耗尽入口产生TIMED_OUT与旧PAUSED批次，不手工改作业终态。
        notificationDeadlineSeconds=1; retryLimit=0;
        Fixture terminal=ready(); UUID terminalCampaign=historicalTerminal(terminal);
        Fixture unproven=ready(); UUID unprovenCampaign=historicalTerminal(unproven);
        UUID unprovenJob=owner().queryForObject("SELECT id FROM ota_device_job WHERE campaign_id=?",UUID.class,unprovenCampaign);
        owner().update("DELETE FROM ota_job_execution_origin WHERE job_id=?",unprovenJob);
        activeBefore=facts(active); // 全局领取可能同时持有本测试活动的租约，以迁移前实测图为基线。
        includeSuccessPolicy=false;
        Fixture missingPolicy=ready(); UUID missingPolicyCampaign=historicalTerminal(missingPolicy);
        includeSuccessPolicy=true;
        activeBefore=facts(active);
        var policyBefore=facts(missingPolicy);
        var terminalBefore=facts(terminal); var unprovenBefore=facts(unproven);
        var terminalJobs=owner().queryForList("SELECT * FROM ota_device_job WHERE campaign_id=?",terminalCampaign);
        var terminalHead=owner().queryForMap("SELECT * FROM ota_campaign WHERE id=?",terminalCampaign);
        var terminalFailures=owner().queryForList("SELECT * FROM ota_notification_delivery WHERE campaign_id=?",terminalCampaign);
        flyway("20260921.0440").migrate();
        assertThat(facts(paused)).isEqualTo(pausedBefore);
        assertThat(facts(active)).isEqualTo(activeBefore);
        assertThat(facts(terminal)).isEqualTo(terminalBefore);
        assertThat(facts(unproven)).isEqualTo(unprovenBefore);
        assertThat(preflight(terminal,terminalCampaign)).isEqualTo("READY");
        assertThat(preflight(unproven,unprovenCampaign)).isEqualTo("TERMINAL_EVIDENCE_MISSING");
        assertThat(preflight(missingPolicy,missingPolicyCampaign)).isEqualTo("FROZEN_POLICY_MISSING");
        assertThat(preflight(active,terminalCampaign)).isEqualTo("SCOPE_REJECTED");
        assertThat(OtaRetryUpgradeTests.<String>plain(j->j.queryForObject("SELECT ota_historical_batch_recovery_reason(?)",String.class,terminalCampaign))).isEqualTo("SCOPE_REJECTED");
        try {
            owner().execute(java.nio.file.Files.readString(java.nio.file.Path.of("../../deploy/sql/inspect-ota-historical-batches.sql")));
        } catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
        assertThat(facts(terminal)).isEqualTo(terminalBefore);
        owner().execute("""
                CREATE FUNCTION tc_test_reject_batch_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.action='ota.batch.historical_reconciled' THEN RAISE EXCEPTION 'TEST_HISTORICAL_AUDIT_FAILURE'; END IF; RETURN NEW; END $$;
                CREATE TRIGGER tc_test_reject_batch_audit BEFORE INSERT ON sys_audit_log
                FOR EACH ROW EXECUTE FUNCTION tc_test_reject_batch_audit();
                """);
        try {
            assertThatThrownBy(()->flyway("20260921.0450").migrate()).hasStackTraceContaining("TEST_HISTORICAL_AUDIT_FAILURE");
            assertThat(facts(terminal)).isEqualTo(terminalBefore);
            assertThat(owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE target_id=? AND action='ota.batch.historical_reconciled'",Integer.class,terminalCampaign)).isZero();
        } finally {
            owner().execute("DROP TRIGGER tc_test_reject_batch_audit ON sys_audit_log; DROP FUNCTION tc_test_reject_batch_audit()");
        }
        assertThat(flyway("20260921.0450").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(facts(unproven)).isEqualTo(unprovenBefore);
        assertThat(facts(missingPolicy)).isEqualTo(policyBefore);
        assertThat(owner().queryForMap("SELECT * FROM ota_campaign WHERE id=?",terminalCampaign)).usingRecursiveComparison().isEqualTo(terminalHead);
        assertThat(owner().queryForList("SELECT * FROM ota_device_job WHERE campaign_id=?",terminalCampaign)).usingRecursiveComparison().isEqualTo(terminalJobs);
        assertThat(owner().queryForList("SELECT * FROM ota_notification_delivery WHERE campaign_id=?",terminalCampaign)).usingRecursiveComparison().isEqualTo(terminalFailures);
        assertThat(owner().queryForObject("SELECT status FROM ota_campaign_batch WHERE campaign_id=?",String.class,terminalCampaign)).isEqualTo("FAILED");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_batch_transition WHERE campaign_id=? AND reason='HISTORICAL_RETRY_EXHAUSTED'",Integer.class,terminalCampaign)).isEqualTo(2);
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE target_id=? AND action='ota.batch.historical_reconciled'",Integer.class,terminalCampaign)).isEqualTo(1);
        assertThat(flyway("20260921.0450").migrate().migrationsExecuted).isZero();
        assertThat(preflight(terminal,terminalCampaign)).isEqualTo("NOT_PENDING_PAUSED_BATCH");
        var legacyTerminal=facts(terminal);
        assertThat(flyway("20260921.0460").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_job_completion",Integer.class)).isZero();
        assertThat(facts(terminal)).isEqualTo(legacyTerminal);
        assertThat(flyway("20260921.0460").migrate().migrationsExecuted).isZero();
        // 真实0350旧暂停经0430之后仍须显式授权；没有迁移/后台自动复活。
        long revision=owner().queryForObject("SELECT state_version FROM ota_campaign WHERE id=?",Long.class,campaign.id());
        assertThat(OtaRetryUpgradeTests.<Boolean>runtime(paused,r->r.resume(paused.project(),campaign.id(),revision,paused.account(),"旧库明确授权恢复"))).isTrue();
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?",String.class,admission.jobId())).isEqualTo("RETRY_WAIT");
        assertThat(owner().queryForObject("SELECT attempt_no FROM ota_device_job WHERE id=?",Integer.class,admission.jobId())).isEqualTo(1);
        assertThat(facts(active)).isEqualTo(activeBefore);
    }
    /** 原0360真实通知期限耗尽，不关闭触发器或直接写TIMED_OUT。 */
    private UUID historicalTerminal(Fixture f) {
        var c=scheduled(f,1); runtime(f,r->r.start(f.project(),c.id(),1,f.account()));
        var claim=claimRuntime(); runtime(f,r->r.admit(claim,1,1,OtaExecutionReportFixture.HASH,Instant.now()));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(()->owner().queryForObject(
                "SELECT deadline_at<=clock_timestamp() FROM ota_device_job WHERE id=?",Boolean.class,claim.jobId()));
        OtaNotificationRepository.Claim delivery=null;
        for(int index=0;index<4;index++) {
            var selected=plain(j->new JdbcOtaNotificationRepository(j).claimOne().orElseThrow());
            if(selected.jobId().equals(claim.jobId())) { delivery=selected; break; }
        }
        assertThat(delivery).isNotNull();
        var selectedDelivery=delivery;
        assertThat(OtaRetryUpgradeTests.<Boolean>app(f,j->{
            new JdbcOtaCampaignRuntimeRepository(j).controlLock(f.tenant(),f.project());
            return new JdbcOtaNotificationRepository(j).exhaustDue(selectedDelivery,"NOTIFICATION_DELIVERY_EXHAUSTED");
        })).isTrue();
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?",String.class,claim.jobId())).isEqualTo("TIMED_OUT");
        assertThat(owner().queryForObject("SELECT status FROM ota_campaign_batch WHERE campaign_id=?",String.class,c.id())).isEqualTo("PAUSED");
        return c.id();
    }
    /** 只读预检使用普通应用角色实际scope。 */
    private static String preflight(Fixture f,UUID campaign) {
        return app(f,j->j.queryForObject("SELECT ota_historical_batch_recovery_reason(?)",String.class,campaign));
    }
    /** 整图快照按规范JSON排序，含原转换/失败/执行来源而不依赖随机查询顺序。 */
    private static Map<String, String> facts(Fixture fixture) {
        var result = new java.util.LinkedHashMap<String, String>();
        for (String table : List.of("ota_campaign", "ota_campaign_batch", "ota_device_job", "ota_campaign_transition",
                "ota_batch_transition", "ota_job_transition", "ota_job_dispatch_outbox", "ota_notification_delivery",
                "ota_notification_transport", "ota_job_execution_origin")) {
            result.put(table, owner().queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM "
                    + table + " t WHERE project_id=?", String.class, fixture.project()));
        }
        return result;
    }
    /** 显式目标版本及容器内应用角色口令。 */
    private Flyway flyway(String target) {return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())
            .locations(LOCATIONS).placeholders(Map.of("app_role_password","thingslink")).target(target).load();}
    /** 独占旧库普通应用角色，不能借owner验证业务写入。 */
    private static final String APP_ROLE = "thingslink_app";
    /** 对应本独占容器迁移占位符。 */
    private static final String APP_ROLE_PASSWORD = "thingslink";
    /** 仅记录独占容器夹具身份，由容器生命周期清理。 */
    private final List<Fixture> fixtures = new ArrayList<>();
    /** 旧图预留足够迁移时间，仍保留原期限不刷新。 */
    private int notificationDeadlineSeconds = 120;
    /** 每个活动冻结独立预算，旧暂停默认仍有一次重试。 */
    private int retryLimit = 1;
    /** 旧库缺策略只允许保留并报告，不用默认值回填。 */
    private boolean includeSuccessPolicy = true;
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
                + "],\"executionPolicy\":{\"downloadRetryLimit\":" + retryLimit + ",\"retryBackoffSeconds\":1," + (includeSuccessPolicy ? "\"batchMinSuccessRateBps\":5000," : "") + "\"stageTimeoutSeconds\":{\"DISPATCHED\":" + notificationDeadlineSeconds + "}},\"firmwareId\":\""
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
