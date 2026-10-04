package com.things.link.bootstrap.ota;

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

/** ADR0119：真实普通角色验证发布原子采用、租约与删除责任边界；不声称密码学验签。 */
class OtaPublicationPersistenceIntegrationTests extends AbstractIntegrationTest {
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


    /** 完整四事实同事务提交后，普通取消不能收回READY对象。 */
    @Test void commitsReleaseAtomicallyAndProtectsAdoptedObject() {
        Fixture f=seed(); OtaPublication claimed=prepared(f);
        assertThat(OtaPublicationPersistenceIntegrationTests.<Boolean>run(f,r->r.recordSigned(claimed,claimed.leaseToken(),new byte[32],new byte[64],"receipt-one"))).isTrue();
        OtaPublication signed=publication(f,claimed);
        assertThat(OtaPublicationPersistenceIntegrationTests.<Boolean>run(f,r->r.commitRelease(signed,signed.leaseToken(),release(signed)))).isTrue();
        assertThat(publication(f,signed).status()).isEqualTo("COMMITTED");
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware WHERE id=?",String.class,f.firmware())).isEqualTo("READY");
        OtaUploadSession adopted=upload(f,r->r.find(f.project(),f.firmware(),signed.uploadSessionId(),false).orElseThrow());
        assertThat(adopted.status()).isEqualTo("ADOPTED");
        assertThat(OtaPublicationPersistenceIntegrationTests.<Boolean>upload(f,r->r.requestCancel(adopted,adopted.revision()))).isFalse();
        assertThat(OtaPublicationPersistenceIntegrationTests.<Boolean>run(f,r->r.renew(signed,signed.leaseToken()))).isFalse();
        assertThatThrownBy(()->app(f,j->j.update("UPDATE ota_firmware_upload_session SET status='CLEANUP_PENDING',revision=revision+1,cancel_requested_at=clock_timestamp() WHERE id=?",adopted.id())))
                .hasStackTraceContaining("OTA");
        assertThatThrownBy(()->app(f,j->j.update("UPDATE ota_firmware_release SET receipt='changed' WHERE id=?",signed.id())))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(()->app(f,j->j.update("DELETE FROM ota_firmware_release WHERE id=?",signed.id())))
                .hasStackTraceContaining("permission denied");
    }

    /** PostgreSQL正则重复上限不缩减合同：256字符完整放行，257与非ASCII拒绝。 */
    @Test void preservesReceiptLengthAndAsciiContractThroughRelease() {
        Fixture f=seed(); OtaPublication claimed=prepared(f);
        for(String receipt:List.of("a".repeat(257),"回执","", "receipt\n")) {
            assertThatThrownBy(()->run(f,r->r.recordSigned(claimed,claimed.leaseToken(),new byte[32],new byte[64],receipt)))
                    .hasStackTraceContaining("ota_firmware_publication_signature_ck");
        }
        String maximum="a".repeat(256);
        assertThat(OtaPublicationPersistenceIntegrationTests.<Boolean>run(f,r->r.recordSigned(claimed,claimed.leaseToken(),new byte[32],new byte[64],maximum))).isTrue();
        OtaPublication signed=publication(f,claimed);
        assertThat(OtaPublicationPersistenceIntegrationTests.<Boolean>run(f,r->r.commitRelease(signed,signed.leaseToken(),release(signed)))).isTrue();
        assertThat(run(f,r->r.findRelease(f.project(),f.firmware()).orElseThrow()).receipt()).isEqualTo(maximum);
    }

    /** 失联SIGNING不可重发；SIGNED可换租约恢复提交，但旧能力永不复活。 */
    @Test void fencesUnknownAndReclaimsOnlySignedEvidence() {
        Fixture f=seed(); OtaPublication signing=prepared(f);
        expire(signing);
        OtaPublication unknown=claim();
        assertThat(unknown.id()).isEqualTo(signing.id());
        assertThat(unknown.status()).isEqualTo("UNKNOWN");
        assertThat(unknown.leaseToken()).isNull();
        assertThat(OtaPublicationPersistenceIntegrationTests.<Boolean>run(f,r->r.recordSigned(signing,signing.leaseToken(),new byte[32],new byte[64],"late"))).isFalse();
        assertThat(OtaPublicationPersistenceIntegrationTests.<java.util.Optional<OtaPublication>>plain(j->new JdbcOtaPublicationRepository(j).claimPreparedOrSigned())).isEmpty();
        Fixture other=seed(); OtaPublication first=prepared(other);
        run(other,r->r.recordSigned(first,first.leaseToken(),new byte[32],new byte[64],"signed"));
        expire(first); OtaPublication resumed=claim();
        assertThat(resumed.status()).isEqualTo("SIGNED");
        assertThat(resumed.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(OtaPublicationPersistenceIntegrationTests.<Boolean>run(other,r->r.renew(resumed,first.leaseToken()))).isFalse();
        assertThat(OtaPublicationPersistenceIntegrationTests.<Boolean>run(other,r->r.commitRelease(resumed,resumed.leaseToken(),release(resumed)))).isTrue();
    }

    /** 所有身份与规范字节冻结，且另一项目不能读取精确已知ID。 */
    @Test void rejectsMutationCrossScopeAndPrematureCommit() {
        Fixture f=seed(); OtaPublication s=prepared(f); Fixture other=seed();
        for (String assignment:List.of("canonical_manifest='changed'::bytea","trust_snapshot='changed'::bytea", "request_id=gen_random_uuid()","upload_revision=42","status='COMMITTED',revision=revision+1,lease_token=NULL,lease_until=NULL")) {
            assertThatThrownBy(()->app(f,j->j.update("UPDATE ota_firmware_publication SET "+assignment+" WHERE id=?",s.id())))
                    .hasStackTraceContaining("OTA publication");
        }
        assertThat(OtaPublicationPersistenceIntegrationTests.<java.util.Optional<OtaPublication>>run(other,r->r.find(f.project(),f.firmware(),s.id(),false))).isEmpty();
        run(f,r->r.recordSigned(s,s.leaseToken(),new byte[32],new byte[64],"receipt"));
        OtaPublication signed=publication(f,s);
        OtaUploadSession u=upload(f,r->r.find(f.project(),f.firmware(),s.uploadSessionId(),false).orElseThrow());
        upload(f,r->r.requestCancel(u,u.revision()));
        assertThat(OtaPublicationPersistenceIntegrationTests.<Boolean>run(f,r->r.commitRelease(signed,signed.leaseToken(),release(signed)))).isFalse();
        assertThat(OtaPublicationPersistenceIntegrationTests.<java.util.Optional<OtaRelease>>run(f,r->r.findRelease(f.project(),f.firmware()))).isEmpty();
        assertThat(owner().queryForObject("SELECT status FROM ota_firmware WHERE id=?",String.class,f.firmware())).isEqualTo("DRAFT");
    }

    /** 插入release不等于完成采用，延迟检查拒绝半图事务提交。 */
    @Test void rejectsReleaseWithoutCompleteAdoptionAtTransactionCommit() {
        Fixture f=seed(); OtaPublication s=prepared(f);
        run(f,r->r.recordSigned(s,s.leaseToken(),new byte[32],new byte[64],"receipt"));
        OtaPublication signed=publication(f,s);
        assertThatThrownBy(()->app(f,j->j.update("""
                INSERT INTO ota_firmware_release(id,tenant_id,project_id,firmware_id,upload_session_id,
                    publication_id,canonical_manifest,trust_snapshot,spki,signature,receipt,created_at)
                SELECT id,tenant_id,project_id,firmware_id,upload_session_id,id,canonical_manifest,
                    trust_snapshot,spki,signature,receipt,clock_timestamp()
                FROM ota_firmware_publication WHERE id=?
                """,signed.id()))).hasStackTraceContaining("OTA release adoption graph incomplete");
        assertThat(OtaPublicationPersistenceIntegrationTests.<java.util.Optional<OtaRelease>>run(f,r->r.findRelease(f.project(),f.firmware()))).isEmpty();
        assertThat(publication(f,s).status()).isEqualTo("SIGNED");
        // SIGNED不能退回UNKNOWN；另一真实SIGNING用于不可绕过的未知结果反例。
        Fixture other=seed(); OtaPublication pending=prepared(other);
        run(other,r->r.unknown(pending,pending.leaseToken(),"UNCERTAIN"));
        assertThatThrownBy(()->app(other,j->j.update("UPDATE ota_firmware_publication SET status='REJECTED',revision=revision+1 WHERE id=?",pending.id())))
                .hasStackTraceContaining("controlled project cleanup");
    }

    /** 项目先移交ADOPTED物理回收，release留存到网络责任真正完成后。 */
    @Test void projectCleanupWaitsForAdoptedPhysicalCleanup() {
        Fixture f=seed(); OtaPublication s=prepared(f);
        run(f,r->r.recordSigned(s,s.leaseToken(),new byte[32],new byte[64],"receipt"));
        OtaPublication signed=publication(f,s); run(f,r->r.commitRelease(signed,signed.leaseToken(),release(signed)));
        UUID token=Uuid7.generate();
        owner().update("UPDATE sys_project SET status='PURGING',lifecycle_generation=1,deleted_at=now()-interval '31 days',cleanup_stage='OTA',cleanup_started_at=now(),cleanup_next_attempt_at=now(),cleanup_lease_token=?,cleanup_lease_until=now()+interval '120 seconds' WHERE id=?",token,f.project());
        assertThat(cleanBatch(f,token)).isEqualTo("OTA_UPLOAD_CANCELLATION_REQUESTED");
        assertThat(cleanBatch(f,token)).isEqualTo("OTA_UPLOAD_RECOVERY_PENDING");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_firmware_release WHERE project_id=?",Long.class,f.project())).isEqualTo(1);
        OtaUploadSession recovering=plain(j->new JdbcOtaUploadRepository(j).claimRecovery().orElseThrow());
        assertThat(recovering.id()).isEqualTo(s.uploadSessionId());
        // 仅验证DB完成条件；真实对象删除由生命周期Minio专项证明。
        assertThat(OtaPublicationPersistenceIntegrationTests.<Boolean>upload(f,r->r.finishCleanup(recovering,recovering.leaseToken()))).isTrue();
        for(int i=0;i<6;i++) cleanBatch(f,token);
        for(String table:List.of("ota_firmware_release","ota_firmware_publication","ota_firmware_upload_session","ota_firmware"))
            assertThat(owner().queryForObject("SELECT count(*) FROM "+table+" WHERE project_id=?",Long.class,f.project())).isZero();
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
