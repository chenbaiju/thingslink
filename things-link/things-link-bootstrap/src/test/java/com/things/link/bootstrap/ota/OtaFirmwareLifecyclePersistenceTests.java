package com.things.link.bootstrap.ota;

import com.things.link.ota.domain.OtaUploadSession;
import com.things.link.ota.domain.OtaFirmwareLifecycleState;
import com.things.link.ota.infrastructure.persistence.JdbcOtaFirmwareLifecycleRepository;
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

/** ADR0121：真实普通角色验证三条软终态路径、元数据不可变、CAS和对象责任保留。 */
class OtaFirmwareLifecyclePersistenceTests extends AbstractIntegrationTest {
    /** 每例只删除自己的隔离项目图，不影响其他共享夹具。 */
    private final List<Fixture> fixtures = new ArrayList<>();

    /** owner只用于最终夹具清理，生产DELETE权限仍保持禁止。 */
    @AfterEach
    void cleanup() {
        JdbcTemplate jdbc = owner();
        for (Fixture f : fixtures) {
            jdbc.update("DELETE FROM ota_firmware_release WHERE project_id=?",f.project());
            jdbc.update("DELETE FROM ota_firmware_publication WHERE project_id=?",f.project());
            jdbc.update("DELETE FROM ota_firmware_upload_session WHERE project_id=?", f.project());
            jdbc.update("DELETE FROM ota_firmware_creation_request WHERE project_id=?", f.project());
            jdbc.update("DELETE FROM ota_firmware WHERE project_id=?", f.project());
            jdbc.update("DELETE FROM dev_thing_model_version WHERE project_id=?", f.project());
            jdbc.update("DELETE FROM dev_type WHERE project_id=?", f.project());
            jdbc.update("DELETE FROM sys_project WHERE id=?", f.project());
            jdbc.update("DELETE FROM sys_tenant WHERE id=?", f.tenant());
            jdbc.update("DELETE FROM sys_account WHERE id=?", f.account());
        }
    }



    /** 三条合法路径必须保留原发布关系，退役后撤销不得改写既有原因。 */
    @Test void preservesPublicationAcrossAllLifecycleTransitions() {
        Fixture f=seed(); OtaPublication p=ready(f); Instant now=time();
        assertThat(OtaFirmwareLifecyclePersistenceTests.<Boolean>life(f,r->r.deprecate(f.tenant(),f.project(),f.firmware(),2,"退役原因",f.account(),now))).isTrue();
        var deprecated=state(f); assertThat(deprecated.firmware().status()).isEqualTo("DEPRECATED");
        assertThat(deprecated.firmware().revision()).isEqualTo(3);
        assertThat(deprecated.deprecation()).isEqualTo(new OtaFirmwareLifecycleState.Transition("退役原因",f.account(),now));
        assertThat(deprecated.revocation()).isNull();
        assertThat(OtaFirmwareLifecyclePersistenceTests.<Boolean>life(f,r->r.revoke(f.tenant(),f.project(),f.firmware(),3,"撤销原因",f.account(),now))).isTrue();
        var revoked=state(f); assertThat(revoked.firmware().revision()).isEqualTo(4);
        assertThat(revoked.deprecation()).isEqualTo(deprecated.deprecation());
        assertThat(revoked.revocation()).isEqualTo(new OtaFirmwareLifecycleState.Transition("撤销原因",f.account(),now));
        assertThat(publication(f,p).status()).isEqualTo("COMMITTED");
        assertThat(upload(f,r->r.find(f.project(),f.firmware(),p.uploadSessionId(),false).orElseThrow()).status()).isEqualTo("ADOPTED");
        Fixture direct=seed(); ready(direct);
        assertThat(OtaFirmwareLifecyclePersistenceTests.<Boolean>life(direct,r->r.revoke(direct.tenant(),direct.project(),direct.firmware(),2,"直接撤销",direct.account(),time()))).isTrue();
        assertThat(state(direct).firmware().revision()).isEqualTo(3);
        assertThat(state(direct).deprecation()).isNull();
    }

    /** 数据库本身拒绝缺失元数据、非法倒退、越界原因和原发布内容改写。 */
    @Test void rejectsIllegalStatesMetadataAndPostPublicationMutation() {
        Fixture f=seed(); ready(f);
        for(String reason:List.of("", " "," 起始空格","尾部空格 ","含\n换行","含\t制表","a".repeat(513))) {
            assertThatThrownBy(()->life(f,r->r.deprecate(f.tenant(),f.project(),f.firmware(),2,reason,f.account(),time())))
                    .hasStackTraceContaining("ota_firmware_lifecycle_metadata_ck");
        }
        for(String assignment:List.of("status='DRAFT',revision=0", "firmware_version='changed'", "status='DEPRECATED',revision=3")) {
            assertThatThrownBy(()->app(f,j->j.update("UPDATE ota_firmware SET "+assignment+" WHERE id=?",f.firmware())));
        }
        assertThatThrownBy(()->app(f,j->j.update("UPDATE ota_firmware SET status='DEPRECATED',revision=3,deprecated_reason='只有原因' WHERE id=?",f.firmware())))
                .hasStackTraceContaining("ota_firmware_lifecycle_metadata_ck");
        String unicode="😀".repeat(512);
        life(f,r->r.deprecate(f.tenant(),f.project(),f.firmware(),2,unicode,f.account(),time()));
        assertThat(state(f).deprecation().reason()).isEqualTo(unicode);
        assertThatThrownBy(()->app(f,j->j.update("UPDATE ota_firmware SET status='REVOKED',revision=4,deprecated_reason='篡改',revoked_reason='撤销',revoked_by=?,revoked_at=clock_timestamp() WHERE id=?",f.account(),f.firmware())))
                .hasStackTraceContaining("lifecycle evidence is immutable");
        Instant tooEarly=state(f).deprecation().occurredAt().minusNanos(1000);
        assertThatThrownBy(()->life(f,r->r.revoke(f.tenant(),f.project(),f.firmware(),3,"倒序时间",f.account(),tooEarly)))
                .hasStackTraceContaining("ota_firmware_lifecycle_metadata_ck");
        life(f,r->r.revoke(f.tenant(),f.project(),f.firmware(),3,"撤销",f.account(),time()));
        assertThatThrownBy(()->app(f,j->j.update("UPDATE ota_firmware SET revoked_reason='篡改' WHERE id=?",f.firmware())))
                .hasStackTraceContaining("immutable");
        assertThatThrownBy(()->app(f,j->j.update("DELETE FROM ota_firmware WHERE id=?",f.firmware())))
                .hasStackTraceContaining("permission denied");
    }

    /** 跨项目与旧修订均不能推进，当前修订只允许一个CAS胜出。 */
    @Test void fencesScopeAndCompetingExpectedRevision() throws Exception {
        Fixture f=seed(); ready(f); Fixture other=seed();
        assertThat(OtaFirmwareLifecyclePersistenceTests.<java.util.Optional<OtaFirmwareLifecycleState>>life(other,r->r.find(f.project(),f.firmware(),false))).isEmpty();
        assertThat(OtaFirmwareLifecyclePersistenceTests.<Boolean>life(other,r->r.revoke(f.tenant(),f.project(),f.firmware(),2,"越权",other.account(),time()))).isFalse();
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{start.await();return life(f,r->r.deprecate(f.tenant(),f.project(),f.firmware(),2,"退役竞争",f.account(),time()));});
            var second=pool.submit(()->{start.await();return life(f,r->r.revoke(f.tenant(),f.project(),f.firmware(),2,"撤销竞争",f.account(),time()));});
            start.countDown();
            assertThat(List.of(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true,false);
        }
        assertThat(state(f).firmware().revision()).isEqualTo(3);
        assertThat(OtaFirmwareLifecyclePersistenceTests.<Boolean>life(f,r->r.revoke(f.tenant(),f.project(),f.firmware(),2,"旧修订",f.account(),time()))).isFalse();
    }

    /** 已撤销固件仍需等待上传网络清理后，才允许项目有界删除父图。 */
    @Test void retainsRevokedReferencesUntilProjectCleanupSettles() {
        Fixture f=seed(); OtaPublication p=ready(f);
        life(f,r->r.revoke(f.tenant(),f.project(),f.firmware(),2,"停止分发",f.account(),time()));
        UUID token=Uuid7.generate();
        owner().update("UPDATE sys_project SET status='PURGING',lifecycle_generation=1,deleted_at=now()-interval '31 days',cleanup_stage='OTA',cleanup_started_at=now(),cleanup_next_attempt_at=now(),cleanup_lease_token=?,cleanup_lease_until=now()+interval '120 seconds' WHERE id=?",token,f.project());
        assertThat(cleanBatch(f,token)).isEqualTo("OTA_UPLOAD_CANCELLATION_REQUESTED");
        assertThat(cleanBatch(f,token)).isEqualTo("OTA_UPLOAD_RECOVERY_PENDING");
        assertThat(state(f).revocation()).isNotNull();
        OtaUploadSession recovering=plain(j->new JdbcOtaUploadRepository(j).claimRecovery().orElseThrow());
        assertThat(recovering.id()).isEqualTo(p.uploadSessionId());
        // 本例验证完成条件，真实对象删除由独立MinIO集成证明。
        upload(f,r->r.finishCleanup(recovering,recovering.leaseToken()));
        for(int i=0;i<6;i++)cleanBatch(f,token);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_firmware WHERE id=?",Long.class,f.firmware())).isZero();
    }

    /** 通过原发布仓储构造合法READY图，不关闭任何数据库守卫。 */
    private OtaPublication ready(Fixture f) {
        OtaPublication p=prepared(f);run(f,r->r.recordSigned(p,p.leaseToken(),new byte[32],new byte[64],"receipt"));
        OtaPublication signed=publication(f,p);run(f,r->r.commitRelease(signed,signed.leaseToken(),release(signed)));return signed;
    }
    /** 与固件创建约束共用数据库时钟，不能拿宿主JVM时钟解释容器中的先后关系。 */
    private static Instant time() {
        return plain(jdbc -> jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant());
    }
    /** 完整当前生命周期。 */
    private OtaFirmwareLifecycleState state(Fixture f) { return life(f,r->r.find(f.project(),f.firmware(),false).orElseThrow()); }
    /** 普通应用身份与原事务执行生命周期仓储。 */
    private static <T> T life(Fixture f,Function<JdbcOtaFirmwareLifecycleRepository,T> work) {
        return app(f,j->work.apply(new JdbcOtaFirmwareLifecycleRepository(j)));
    }

    /** 正常上传状态机建立固定版本DB证据，不伪造生产验签成功。 */
    private OtaPublication prepared(Fixture f) {
        OtaUploadSession u=create(f); UUID token=Uuid7.generate();
        upload(f,r->r.claimReceive(u,token)); upload(f,r->r.markWriting(u,token));
        upload(f,r->r.recordVersion(u,token,"version-one")); upload(f,r->r.finishVerified(u,token));
        OtaUploadSession verified=current(f,u); Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        OtaPublication p=new OtaPublication(Uuid7.generate(),f.tenant(),f.project(),f.firmware(),u.id(),f.account(),Uuid7.generate(),0,0,verified.revision(),new byte[]{1},new byte[]{2},"PREPARED",0,null,null,null,null,null,null,now,now);
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
