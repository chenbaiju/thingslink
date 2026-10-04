package com.things.link.bootstrap.ota;

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

/** 数据库独立运行与真实租约专项，不模拟MQTT发送成功。 */
class OtaConfirmationPersistenceTests extends AbstractIntegrationTest {
    /** 每例隔离项目。 */
    private final List<Fixture> fixtures = new ArrayList<>();

    /** owner按子先父清理全部夹具，完整删除事务不留下临时断图。 */
    @AfterEach void cleanup() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        for (Fixture f : fixtures) {
            new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status -> {
                var j = new JdbcTemplate(source);
                for (String table : List.of("ota_confirmation_cancellation", "ota_confirmation_outbox", "ota_commit_receipt", "ota_commit_transport", "ota_commit_delivery", "ota_commit_permit", "ota_health_receipt", "ota_job_progress_outbox", "ota_job_progress", "ota_job_expiry", "ota_job_execution_origin", "ota_notification_transport", "ota_notification_delivery", "ota_job_dispatch_outbox", "ota_job_transition", "ota_batch_transition", "ota_campaign_outbox", "ota_campaign_transition", "ota_device_job",
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

    /** 普通应用不能改写任何健康、许可、提交或完成原事实。 */
    @Test void keepsConfirmationEvidenceImmutableForApplicationRole() {
        Fixture f=seed();
        for(String table:List.of("ota_health_receipt","ota_commit_permit","ota_commit_receipt","ota_confirmation_outbox","ota_confirmation_cancellation")) {
            assertThatThrownBy(()->app(f,j->j.update("DELETE FROM "+table+" WHERE project_id=?",f.project())))
                    .rootCause().isInstanceOf(java.sql.SQLException.class)
                    .extracting(failure->((java.sql.SQLException)failure).getSQLState()).isEqualTo("42501");
            assertThat(owner().queryForObject("SELECT relforcerowsecurity FROM pg_class WHERE oid=?::regclass",Boolean.class,table)).isTrue();
        }
    }
    /** 挂接旧派发转移的伪确认出站图不能提交。 */
    @Test void rejectsOldDispatchAsConfirmationTransition() {
        Fixture f=ready();var c=scheduled(f,1);runtime(f,r->r.start(f.project(),c.id(),1,f.account()));
        var claim=claimRuntime();runtime(f,r->r.admit(claim,1,1,OtaExecutionReportFixture.HASH,Instant.now()));
        assertThatThrownBy(()->app(f,j->j.update("INSERT INTO ota_confirmation_outbox(id,tenant_id,project_id,campaign_id,job_id,transition_id,health_receipt_id,created_at) "
                +"SELECT gen_random_uuid(),tenant_id,project_id,campaign_id,job_id,id,gen_random_uuid(),occurred_at FROM ota_job_transition WHERE job_id=?",claim.jobId())))
                .rootCause().isInstanceOf(java.sql.SQLException.class)
                .extracting(failure->((java.sql.SQLException)failure).getSQLState()).isEqualTo("23503");
        assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?",String.class,claim.jobId())).isEqualTo("DISPATCHED");
    }
    /** 缺健康、许可和设备绑定的直接成功跳转不能释放设备占位。 */
    @Test void rejectsSucceededHalfGraphWithoutBindingAndPermit() {
        Fixture f=ready();var c=scheduled(f,1);runtime(f,r->r.start(f.project(),c.id(),1,f.account()));
        var claim=claimRuntime();runtime(f,r->r.admit(claim,1,1,OtaExecutionReportFixture.HASH,Instant.now()));
        assertThatThrownBy(()->app(f,j->j.update("UPDATE ota_device_job SET status='SUCCEEDED',state_version=state_version+1 WHERE id=?",claim.jobId())))
                .rootCause().isInstanceOf(java.sql.SQLException.class)
                .extracting(failure->((java.sql.SQLException)failure).getSQLState()).isEqualTo("23514");
        assertThat(owner().queryForObject("SELECT state_version FROM ota_device_job WHERE id=?",Long.class,claim.jobId())).isEqualTo(1L);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_commit_permit WHERE job_id=?",Integer.class,claim.jobId())).isZero();
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
                + "],\"executionPolicy\":{\"stageTimeoutSeconds\":{\"DISPATCHED\":30}},\"firmwareId\":\""
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
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(5);
        return transaction.execute(status->{jdbc.execute("SET LOCAL lock_timeout='5s'");return work.apply(jdbc);});
    }

    /** owner限于夹具与独立最终观察。 */
    private static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
    }

    /** 本例独立父事实身份。 */
    private record Fixture(UUID tenant,UUID project,UUID account,UUID type,UUID model,UUID firmware) { }
}
