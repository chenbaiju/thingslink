package com.things.link.bootstrap.ota;

import com.things.link.ota.domain.OtaUploadSession;
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

/** ADR0117：普通数据库角色证明会话不可变、租约、UNKNOWN与项目清理边界。 */
class OtaUploadPersistenceIntegrationTests extends AbstractIntegrationTest {
    /** 每例只删除自己的隔离项目图，不影响其他共享夹具。 */
    private final List<Fixture> fixtures = new ArrayList<>();

    /** owner只用于最终夹具清理，生产DELETE权限仍保持禁止。 */
    @AfterEach
    void cleanup() {
        JdbcTemplate jdbc = owner();
        for (Fixture f : fixtures) {
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

    /** 每个不可变字段独立事务反例，失败不污染后续断言。 */
    @Test
    void rejectsIdentityMutationIllegalTransitionsAndConcurrentActiveSessions() {
        Fixture f = seed();
        OtaUploadSession session = create(f);
        for (String assignment : List.of("object_key='changed'", "bucket='changed'", "expected_length=2",
                "expected_sha256=repeat('b',64)", "project_generation=1", "request_id=gen_random_uuid()",
                "project_id=gen_random_uuid()", "firmware_id=gen_random_uuid()", "key_digest=repeat('c',64)",
                "status='WRITING',write_started_at=clock_timestamp()", "status='UNKNOWN'")) {
            assertThatThrownBy(() -> app(f, jdbc -> jdbc.update("UPDATE ota_firmware_upload_session SET "
                    + assignment + " WHERE id=?", session.id()))).hasStackTraceContaining("OTA upload");
        }
        assertThatThrownBy(() -> create(f)).hasStackTraceContaining("ota_firmware_upload_session_active_idx");
        assertThatThrownBy(() -> app(f, jdbc -> jdbc.update("DELETE FROM ota_firmware_upload_session WHERE id=?", session.id())))
                .hasStackTraceContaining("permission denied");
    }

    /** JVM创建时刻领先数据库时，事实时间逻辑单调，而租约仍使用数据库实际时钟。 */
    @Test
    void recordsMonotoneEvidenceWhenApplicationClockIsAhead() {
        Fixture f=seed();
        Instant ahead=owner().queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class)
                .toInstant().plusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        OtaUploadSession session=create(f,ahead);
        UUID token=Uuid7.generate();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f,r->r.claimReceive(session,token))).isTrue();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f,r->r.markWriting(session,token))).isTrue();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f,r->r.recordVersion(session,token,"clock-version"))).isTrue();
        OtaUploadSession actual=current(f,session);
        assertThat(actual.writeStartedAt()).isAfterOrEqualTo(ahead);
        assertThat(actual.writeSettledAt()).isAfterOrEqualTo(actual.writeStartedAt());
    }

    /** 当前租约只允许一次接收，旧token及真实过期时间不能重新进入写入。 */
    @Test
    void fencesReceiveAndWritingWithCurrentLease() {
        Fixture f = seed();
        OtaUploadSession session = create(f);
        UUID token = Uuid7.generate();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f, repo -> repo.claimReceive(session, token))).isTrue();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f, repo -> repo.claimReceive(session, Uuid7.generate()))).isFalse();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f, repo -> repo.markWriting(session, Uuid7.generate()))).isFalse();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f, repo -> repo.renew(session, token))).isTrue();
        owner().update("UPDATE ota_firmware_upload_session SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", session.id());
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f, repo -> repo.renew(session, token))).isFalse();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f, repo -> repo.markWriting(session, token))).isFalse();
    }

    /** WRITING失联必须留下UNKNOWN，即使恢复盘点返回空也无权宣称CLEANED。 */
    @Test
    void preservesUnknownWhenWritingLeaseExpires() {
        Fixture f = seed();
        OtaUploadSession session = create(f);
        UUID token = Uuid7.generate();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f, repo -> repo.claimReceive(session, token))).isTrue();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f, repo -> repo.markWriting(session, token))).isTrue();
        owner().update("UPDATE ota_firmware_upload_session SET lease_until=clock_timestamp()-interval '1 second',"
                + "next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?", session.id());
        // 故意无租户项目RLS上下文领取，返回只能是数据库选择的可信持久scope。
        OtaUploadSession claimed = plain(jdbc -> new JdbcOtaUploadRepository(jdbc).claimRecovery().orElseThrow());
        assertThat(claimed.id()).isEqualTo(session.id());
        assertThat(claimed.tenantId()).isEqualTo(f.tenant());
        assertThat(claimed.projectId()).isEqualTo(f.project());
        assertThat(claimed.status()).isEqualTo("UNKNOWN");
        assertThat(claimed.leaseToken()).isNotEqualTo(token);
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f, repo -> repo.finishCleanup(claimed, claimed.leaseToken()))).isFalse();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f, repo -> repo.fail(claimed, claimed.leaseToken(), "CURRENT_CALL_NOT_SENT", false))).isFalse();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f, repo -> repo.postpone(claimed, claimed.leaseToken(), "WRITE_OUTCOME_UNKNOWN"))).isTrue();
        assertThat(run(f, repo -> repo.find(f.project(), f.firmware(), session.id(), false).orElseThrow()).status())
                .isEqualTo("UNKNOWN");
    }

    /** 取消中断后续续租与发布采用，已知对象只能交给回收。 */
    @Test
    void cancellationRejectsRenewalAndVerifiedAdoption() {
        Fixture f = seed();
        OtaUploadSession session = create(f);
        UUID token = Uuid7.generate();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f, repo -> repo.claimReceive(session, token))).isTrue();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f, repo -> repo.markWriting(session, token))).isTrue();
        OtaUploadSession writing = current(f, session);
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f, repo -> repo.requestCancel(writing, writing.revision()))).isTrue();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f, repo -> repo.renew(writing, token))).isFalse();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f, repo -> repo.recordVersion(writing, token, "version-one"))).isTrue();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f, repo -> repo.finishVerified(writing, token))).isFalse();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f, repo -> repo.markCleanup(writing, token, "UPLOAD_CANCELLED"))).isTrue();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f, repo -> repo.finishCleanup(writing, token))).isTrue();
        assertThatThrownBy(() -> app(f, jdbc -> jdbc.update("UPDATE ota_firmware_upload_session SET status='WAITING' WHERE id=?",session.id())))
                .hasStackTraceContaining("immutable");
        assertThat(create(f).id()).isNotEqualTo(session.id());
    }

    /** 取消中写入者失联后，新UNKNOWN恢复租约可续期，旧写入token永不复活。 */
    @Test
    void renewsRecoveryTokenAfterCancelledWriterExpires() {
        Fixture f=seed(); OtaUploadSession session=create(f); UUID token=Uuid7.generate();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f,r->r.claimReceive(session,token))).isTrue();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f,r->r.markWriting(session,token))).isTrue();
        OtaUploadSession writing=current(f,session);
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f,r->r.requestCancel(writing,writing.revision()))).isTrue();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f,r->r.renew(writing,token))).isFalse();
        owner().update("UPDATE ota_firmware_upload_session SET lease_until=clock_timestamp()-interval '1 second',"
                +"next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?",session.id());
        OtaUploadSession recovered=plain(jdbc->new JdbcOtaUploadRepository(jdbc).claimRecovery().orElseThrow());
        assertThat(recovered.id()).isEqualTo(session.id());
        assertThat(recovered.status()).isEqualTo("UNKNOWN");
        assertThat(recovered.cancelRequestedAt()).isNotNull();
        assertThat(recovered.leaseToken()).isNotEqualTo(token);
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f,r->r.renew(recovered,recovered.leaseToken()))).isTrue();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f,r->r.renew(recovered,token))).isFalse();
    }

    /** 普通查询和更新不能穿过项目RLS，即使调用方知道精确另项目UUID。 */
    @Test
    void keepsSessionScopeUnderForcedRls() {
        Fixture first = seed();
        Fixture second = seed();
        OtaUploadSession session = create(first);
        assertThat(OtaUploadPersistenceIntegrationTests.<java.util.Optional<OtaUploadSession>>run(second, repo -> repo.find(first.project(), first.firmware(), session.id(), false))).isEmpty();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(second, repo -> repo.claimReceive(session, Uuid7.generate()))).isFalse();
        assertThat(OtaUploadPersistenceIntegrationTests.<Long>plain(jdbc -> jdbc.queryForObject("SELECT count(*) FROM ota_firmware_upload_session WHERE id=?",
                Long.class, session.id()))).isZero();
        assertThat(owner().queryForObject("SELECT relforcerowsecurity FROM pg_class WHERE oid='ota_firmware_upload_session'::regclass",
                Boolean.class)).isTrue();
    }

    /** 项目取消不把UNKNOWN空写入变成终态；只有网络收束后才能删会话和父固件。 */
    @Test
    void blocksProjectCleanupUntilUploadIsSettledAndCleaned() {
        Fixture f = seed();
        OtaUploadSession session = create(f);
        UUID token = Uuid7.generate();
        run(f, repo -> repo.claimReceive(session, token));
        run(f, repo -> repo.markWriting(session, token));
        run(f, repo -> repo.fail(session, token, "WRITE_UNKNOWN", true));
        UUID cleanupToken = Uuid7.generate();
        owner().update("""
                UPDATE sys_project SET status='PURGING',lifecycle_generation=1,deleted_at=now()-interval '31 days',
                    cleanup_stage='OTA',cleanup_started_at=now(),cleanup_next_attempt_at=now(),
                    cleanup_lease_token=?,cleanup_lease_until=now()+interval '120 seconds' WHERE id=?
                """, cleanupToken,f.project());
        assertThat(cleanBatch(f, cleanupToken)).isEqualTo("OTA_UPLOAD_CANCELLATION_REQUESTED");
        assertThat(cleanBatch(f, cleanupToken)).isEqualTo("OTA_UPLOAD_RECOVERY_PENDING");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_firmware WHERE id=?",Long.class,f.firmware())).isEqualTo(1);
        OtaUploadSession claimed = plain(jdbc -> new JdbcOtaUploadRepository(jdbc).claimRecovery().orElseThrow());
        assertThat(claimed.id()).isEqualTo(session.id());
        assertThat(claimed.status()).isEqualTo("CLEANUP_PENDING");
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f, repo -> repo.renew(claimed, claimed.leaseToken()))).isTrue();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f, repo -> repo.renew(claimed, token))).isFalse();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f, repo -> repo.finishCleanup(claimed, claimed.leaseToken()))).isFalse();
        // 模拟恢复worker确已取得固定版本回执，再明确执行外部清理后的条件完成。
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f, repo -> repo.recordVersion(claimed, claimed.leaseToken(), "recovered-version"))).isTrue();
        assertThat(OtaUploadPersistenceIntegrationTests.<Boolean>run(f, repo -> repo.finishCleanup(claimed, claimed.leaseToken()))).isTrue();
        for (int i=0;i<3;i++) cleanBatch(f,cleanupToken);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_firmware_upload_session WHERE project_id=?",Long.class,f.project())).isZero();
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_firmware WHERE id=?",Long.class,f.firmware())).isZero();
    }

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
        run(f,repo->{repo.create(session);return true;});
        return session;
    }

    /** 当前revision来自实际持久状态，避免用构造时快照掩盖CAS语义。 */
    private OtaUploadSession current(Fixture f,OtaUploadSession s) {
        return run(f,repo->repo.find(f.project(),f.firmware(),s.id(),false).orElseThrow());
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
    private static <T> T run(Fixture f,Function<JdbcOtaUploadRepository,T> work) {
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
