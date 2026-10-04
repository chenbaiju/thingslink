package com.things.link.bootstrap.ota;

import com.things.link.device.application.OtaDeviceCommitPort;
import com.things.link.device.application.OtaDeviceReconciliationPort;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.testing.AbstractIntegrationTest;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实PG证明原提交不可变、独立查询来源CAS与单调安全下限，不宣称实机证明。 */
class OtaDeviceReconciliationIntegrationTests extends AbstractIntegrationTest {
    /** 新查询独立本域端口。 */ @Autowired private OtaDeviceReconciliationPort reconciliation;
    /** 原提交事实端口。 */ @Autowired private OtaDeviceCommitPort commits;
    /** 真实APP事务。 */ @Autowired private PlatformTransactionManager transactions;
    /** 真实APP数据库连接。 */ @Autowired private JdbcTemplate app;
    /** 无管理账号的设备范围。 */ @Autowired private TransactionLocalRlsScope rls;
    /** 本例独立资源。 */ private final List<Fixture> fixtures=new ArrayList<>();

    /** 原回执缺失时先记录OBSERVED_ONLY原事实，再用独立query变更模型。 */
    @Test void recordsMissingOriginalAsObservationBeforeIndependentAdoption(){
        var f=seed("DIRECT",true);var target=Uuid7.generate();insertModel(f,target,"2.0.0","b".repeat(64));var c=command(f,target,true);
        assertThatThrownBy(()->reconciliation.apply(c)).isInstanceOf(IllegalTransactionStateException.class);
        var result=inScope(f,()->reconciliation.apply(c));assertThat(result.decision()).isEqualTo(OtaDeviceReconciliationPort.Decision.CHANGED);
        assertThat(owner().queryForMap("SELECT decision,adopt_model FROM dev_ota_commit WHERE id=?",result.originalCommitReceiptId()))
                .containsEntry("decision","OBSERVED_ONLY").containsEntry("adopt_model",false);
        assertThat(owner().queryForObject("SELECT transition_key FROM dev_device_model_binding_history WHERE id=?",UUID.class,result.bindingTransitionId())).isEqualTo(c.reconciliationId());
        assertThat(inScope(f,()->reconciliation.apply(c)).receiptId()).isEqualTo(result.receiptId());
        assertThat(count(f,"dev_ota_commit")).isEqualTo(1);assertThat(count(f,"dev_ota_reconciliation")).isEqualTo(1);
        assertThat(matches(f,c,result,false)).isTrue();
    }

    /** 已有原观察不能改成true；新query本身可以独立完成同模型采用。 */
    @Test void keepsOriginalDecisionAndSupportsSameModelReconciliation(){
        var f=seed("DIRECT",true);var c=command(f,f.model(),true);
        var original=inScope(f,()->commits.apply(original(c,false)));
        var result=inScope(f,()->reconciliation.apply(c));assertThat(result.decision()).isEqualTo(OtaDeviceReconciliationPort.Decision.SAME_MODEL);
        assertThat(result.originalCommitReceiptId()).isEqualTo(original.receiptId());assertThat(result.bindingTransitionId()).isNull();
        assertThat(owner().queryForObject("SELECT decision FROM dev_ota_commit WHERE id=?",String.class,original.receiptId())).isEqualTo("OBSERVED_ONLY");
    }

    /** 同query只观察不能在重放时变成采用，新查询和新证明才可采用。 */
    @Test void newQueryCanAdoptButObservationReplayCannotUpgrade(){
        var f=seed("DIRECT",true);var c=command(f,f.model(),false);var observed=inScope(f,()->reconciliation.apply(c));
        var upgraded=copy(c,c.originalPermitId(),c.reconciliationId(),true,1,"c".repeat(64));
        assertThat(inScope(f,()->reconciliation.apply(upgraded)).decision()).isEqualTo(OtaDeviceReconciliationPort.Decision.SECURITY_CONFLICT);
        var next=copy(c,c.originalPermitId(),Uuid7.generate(),true,1,"c".repeat(64));
        assertThat(inScope(f,()->reconciliation.apply(next)).decision()).isEqualTo(OtaDeviceReconciliationPort.Decision.SAME_MODEL);
        assertThat(inScope(f,()->reconciliation.apply(c)).decision()).isEqualTo(OtaDeviceReconciliationPort.Decision.OBSERVED_ONLY);
        assertThat(observed.receiptId()).isNotNull();assertThat(count(f,"dev_ota_commit")).isEqualTo(1);
    }

    /** 当前来源漂移保留原提交下限，但不覆盖已改变的指针。 */
    @Test void sourceConflictKeepsKnownFloorAndCurrentModel(){
        var f=seed("DIRECT",true);var target=Uuid7.generate();insertModel(f,target,"2.0.0","b".repeat(64));var c=command(f,target,true);
        owner().update("UPDATE dev_device SET thing_model_version_id=? WHERE id=?",target,f.device());
        var result=inScope(f,()->reconciliation.apply(c));assertThat(result.decision()).isEqualTo(OtaDeviceReconciliationPort.Decision.SOURCE_CONFLICT);
        assertThat(inScope(f,()->commits.currentFloor(f.tenant(),f.project(),f.device())).orElseThrow().committedSecurityVersion()).isEqualTo(1);
        assertThat(count(f,"dev_device_model_binding_history")).isZero();assertThat(matches(f,c,result,true)).isTrue();assertThat(matches(f,c,result,false)).isFalse();
    }

    /** 原许可完整元组不允许换制品、来源或目标，新query不提供覆盖原许可的权限。 */
    @Test void rejectsDifferentOriginalTupleWithoutNewFacts(){
        var f=seed("DIRECT",true);var c=command(f,f.model(),false);inScope(f,()->reconciliation.apply(c));
        var changed=copy(c,c.originalPermitId(),Uuid7.generate(),true,1,"d".repeat(64));
        assertThat(inScope(f,()->reconciliation.apply(changed)).decision()).isEqualTo(OtaDeviceReconciliationPort.Decision.SECURITY_CONFLICT);
        var wrongPermit=copy(c,Uuid7.generate(),c.reconciliationId(),false,1,"c".repeat(64));
        assertThat(inScope(f,()->reconciliation.apply(wrongPermit)).decision()).isEqualTo(OtaDeviceReconciliationPort.Decision.SECURITY_CONFLICT);
        assertThat(count(f,"dev_ota_commit")).isEqualTo(1);assertThat(count(f,"dev_ota_reconciliation")).isEqualTo(1);
    }

    /** 后续已知更高安全版本使旧query不能再绑定，不能降低floor。 */
    @Test void higherKnownFloorBlocksOldReconciliation(){
        var f=seed("DIRECT",true);var c=command(f,f.model(),true);inScope(f,()->commits.apply(original(c,false)));
        var higher=copy(c,Uuid7.generate(),Uuid7.generate(),false,2,"d".repeat(64));inScope(f,()->commits.apply(original(higher,false)));
        assertThat(inScope(f,()->reconciliation.apply(c)).decision()).isEqualTo(OtaDeviceReconciliationPort.Decision.SECURITY_CONFLICT);
        assertThat(count(f,"dev_ota_reconciliation")).isZero();
        assertThat(inScope(f,()->commits.currentFloor(f.tenant(),f.project(),f.device())).orElseThrow().committedSecurityVersion()).isEqualTo(2);
    }

    /** 错scope、旧代际和自然到期不产生原提交或新query事实。 */
    @Test void rejectsInvalidIdentityWithoutOriginalSideEffects(){
        var f=seed("DIRECT",true);var other=seed("DIRECT",true);var c=command(f,f.model(),true);
        assertThat(inScope(other,()->reconciliation.apply(c)).decision()).isEqualTo(OtaDeviceReconciliationPort.Decision.IDENTITY_REJECTED);
        owner().update("UPDATE dev_device SET credential_version=2 WHERE id=?",f.device());
        assertThat(inScope(f,()->reconciliation.apply(c)).decision()).isEqualTo(OtaDeviceReconciliationPort.Decision.IDENTITY_REJECTED);
        assertThat(count(f,"dev_ota_commit")).isZero();assertThat(count(f,"dev_ota_reconciliation")).isZero();
    }

    /** 调用者事务失败时，原提交和新采用图必须一起回滚。 */
    @Test void callerRollbackRemovesOriginalAndReconciliationAtomically(){
        var f=seed("DIRECT",true);var c=command(f,f.model(),true);
        assertThatThrownBy(()->inScope(f,()->{reconciliation.apply(c);throw new IllegalStateException("audit failed");})).isInstanceOf(IllegalStateException.class);
        assertThat(count(f,"dev_ota_commit")).isZero();assertThat(count(f,"dev_ota_security_floor")).isZero();assertThat(count(f,"dev_ota_reconciliation")).isZero();
    }

    /** 有真实原提交但缺实际目标绑定的app伪成功仍被SQL拒绝。 */
    @Test void databaseRejectsForgedSameModelAdoption(){
        var f=seed("DIRECT",true);var target=Uuid7.generate();insertModel(f,target,"2.0.0","b".repeat(64));var c=command(f,target,true);
        var old=inScope(f,()->commits.apply(original(c,false)));
        assertThatThrownBy(()->inScope(f,()->app.update("""
                INSERT INTO dev_ota_reconciliation(id,tenant_id,project_id,device_id,reconciliation_id,original_permit_id,
                original_commit_receipt_id,adopt_model,decision,binding_transition_id,recorded_at)
                VALUES(?,?,?,?,?,?,?,true,'SAME_MODEL',NULL,clock_timestamp())
                """,Uuid7.generate(),f.tenant(),f.project(),f.device(),c.reconciliationId(),c.originalPermitId(),old.receiptId())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(count(f,"dev_ota_reconciliation")).isZero();
    }

    /** 对账持有真实设备锁直至外层事务结束，凭据轮换不能穿过。 */
    @Test void holdsDeviceLockDuringIndependentAdoption(){
        var f=seed("DIRECT",true);inScope(f,()->{
            reconciliation.apply(command(f,f.model(),true));
            try(var connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
                var statement=connection.createStatement();var update=connection.prepareStatement("UPDATE dev_device SET credential_version=2 WHERE id=?")){
                statement.execute("SET lock_timeout='150ms'");update.setObject(1,f.device());
                assertThatThrownBy(update::executeUpdate).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("55P03"));
            }catch(SQLException failure){throw new IllegalStateException(failure);}return true;
        });
    }

    /** DEVICE完整租约先清独立query，再清原提交，不能以owner删除绕过生产入口。 */
    @Test void productionDeviceCleanupDeletesReconciliationBeforeOriginal(){
        var f=seed("DIRECT",true);var c=command(f,f.model(),false);inScope(f,()->reconciliation.apply(c));
        UUID token=Uuid7.generate();owner().update("""
                UPDATE sys_project SET status='PURGING',lifecycle_generation=1,deleted_at=now()-interval '31 days',cleanup_stage='DEVICE',
                cleanup_started_at=now(),cleanup_next_attempt_at=now(),cleanup_lease_token=?,cleanup_lease_until=clock_timestamp()+interval '60 seconds' WHERE id=?
                """,token,f.project());
        var claim=new com.things.link.project.application.ProjectCleanupClaim(f.tenant(),f.project(),1,"DEVICE",token,java.time.Instant.now().plusSeconds(60),false);
        var first=inScope(f,()->new com.things.link.device.infrastructure.persistence.JdbcDeviceProjectCleanupRepository(app).clean(claim));
        assertThat(first.deletedRows()).isEqualTo(1);assertThat(count(f,"dev_ota_reconciliation")).isZero();assertThat(count(f,"dev_ota_commit")).isEqualTo(1);
        boolean done=false;for(int i=0;i<32;i++){
            var result=inScope(f,()->new com.things.link.device.infrastructure.persistence.JdbcDeviceProjectCleanupRepository(app).clean(claim));
            assertThat(result.deletedRows()).isBetween(0,500);assertThat(result.blockedReason()).isNull();if(result.complete()){done=true;break;}
        }
        assertThat(done).isTrue();assertThat(count(f,"dev_ota_commit")).isZero();assertThat(count(f,"dev_ota_security_floor")).isZero();
    }

    /** 本域公开函数对同一个新query及原permit做精确证明。 */
    private static boolean matches(Fixture f,OtaDeviceReconciliationPort.Command c,OtaDeviceReconciliationPort.Result r,boolean observation){
        String name=observation?"dev_ota_reconciliation_observation_matches":"dev_ota_reconciliation_matches";
        return owner().queryForObject("SELECT "+name+"(?,?,?,?,?,?,?,?,?,?)",Boolean.class,f.tenant(),f.project(),f.device(),c.reconciliationId(),r.receiptId(),c.originalPermitId(),c.target().id(),c.target().digest(),c.committedSecurityVersion(),c.artifactSha256());
    }
    /** 冻结源模型和目标，不通过同键变更复活旧采用决定。 */
    private static OtaDeviceReconciliationPort.Command command(Fixture f,UUID target,boolean adopt){
        return new OtaDeviceReconciliationPort.Command(auth(f,1),f.type(),new OtaDeviceCommitPort.ModelIdentity(f.model(),"PG_JSONB_TEXT_V1_SHA256","a".repeat(64),"TC_PROPERTY_COMPOSITE_V1"),
                new OtaDeviceCommitPort.ModelIdentity(target,"PG_JSONB_TEXT_V1_SHA256",(target.equals(f.model())?"a":"b").repeat(64),"TC_PROPERTY_COMPOSITE_V1"),Uuid7.generate(),Uuid7.generate(),0,1,"c".repeat(64),adopt);
    }
    /** 只转换测试明确指定的独立轴，其他原元组原样保留。 */
    private static OtaDeviceReconciliationPort.Command copy(OtaDeviceReconciliationPort.Command c,UUID permit,UUID query,boolean adopt,long counter,String artifact){
        return new OtaDeviceReconciliationPort.Command(c.identity(),c.deviceTypeId(),c.expectedSource(),c.target(),permit,query,c.sourceCommittedSecurityVersion(),counter,artifact,adopt);
    }
    /** 原许可提交只由独立端口保留，不通过对账覆盖原decision。 */
    private static OtaDeviceCommitPort.Command original(OtaDeviceReconciliationPort.Command c,boolean adopt){
        return new OtaDeviceCommitPort.Command(c.identity(),c.deviceTypeId(),c.expectedSource(),c.target(),c.originalPermitId(),c.sourceCommittedSecurityVersion(),c.committedSecurityVersion(),c.artifactSha256(),adopt);
    }
    /** 静态表名统计本例项目事实。 */
    private static int count(Fixture f,String table){return owner().queryForObject("SELECT count(*) FROM "+table+" WHERE project_id=?",Integer.class,f.project());}

    /** 只建立已确认设备范围，无ThreadLocal管理身份或owner权限替身。 */
    private <T> T inScope(Fixture f, Supplier<T> work) {
        var transaction = new TransactionTemplate(transactions);
        transaction.setTimeout(5);
        return transaction.execute(status -> {
            rls.establish(f.tenant(), f.project());
            return work.get();
        });
    }

    /** 认证时代际显式给出，不从当前表自动补充。 */
    private static AuthenticatedDeviceIdentity auth(Fixture f, long generation) {
        return new AuthenticatedDeviceIdentity(f.tenant(), f.project(), f.device(), generation);
    }

    /** owner只播种身份和不可变版本，不作被测读取。 */
    private Fixture seed(String kind, boolean bound) {
        Fixture f = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
        fixtures.add(f);
        owner().update("INSERT INTO sys_tenant(id,name) VALUES (?,'OTA设备身份测试')", f.tenant());
        owner().update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'OTA设备身份测试','sh-1',?)",
                f.project(), f.tenant(), "ota_" + f.project().toString().replace("-", ""));
        boolean subDevice = "SUB_DEVICE".equals(kind);
        owner().update("""
                INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status,product_key,product_secret_hash)
                VALUES (?,?,?,?,'OTA身份测试类型',?,?,'WIFI','PUBLISHED',?,?)
                """, f.type(), f.tenant(), f.project(), "type_" + f.type(), kind,
                "GATEWAY".equals(kind) ? "STANDARD_GATEWAY" : "STANDARD",
                subDevice ? null : "product_" + f.type(), subDevice ? null : "a".repeat(64));
        insertModel(f, f.model(), "1.0.0", "a".repeat(64));
        owner().update("""
                INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,thing_model_version_id)
                VALUES (?,?,?,?,?,'OTA身份测试设备',?)
                """, f.device(), f.tenant(), f.project(), f.type(), "device_" + f.device(), bound ? f.model() : null);
        // 子设备依赖网关接入，不能播种独立产品密钥或设备凭据。
        if (!subDevice) {
            owner().update("INSERT INTO dev_credential(id,tenant_id,project_id,device_id,auth_type,credential_hash,display_name)"
                    + " VALUES (?,?,?,?,'ACCESS_TOKEN',repeat('a',64),'身份测试凭据')",
                    Uuid7.generate(), f.tenant(), f.project(), f.device());
        }
        return f;
    }

    /** 同scope/type不可变模型，完整摘要来自模型持久事实。 */
    private static void insertModel(Fixture f, UUID id, String version, String digest) {
        owner().update("""
                INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                    version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                VALUES (?,?,?,?,?, ?,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1',
                    '{"properties":{},"events":{},"commands":{}}'::jsonb,?,'PG_JSONB_TEXT_V1_SHA256')
                """, id, f.tenant(), f.project(), f.type(), version, Integer.parseInt(version.substring(0,1)), digest);
    }

    /** 顺序删除本例设备、模型、类型，再清项目，保留共享事实。 */
    @AfterEach
    void cleanup() {
        for (Fixture f : fixtures) {
            owner().update("DELETE FROM dev_credential WHERE project_id=?", f.project());
            owner().update("DELETE FROM dev_device WHERE project_id=?", f.project());
            owner().update("DELETE FROM dev_thing_model_version WHERE project_id=?", f.project());
            owner().update("DELETE FROM dev_type WHERE project_id=?", f.project());
            owner().update("DELETE FROM sys_project WHERE id=?", f.project());
            owner().update("DELETE FROM sys_tenant WHERE id=?", f.tenant());
        }
        fixtures.clear();
    }

    /** 独立owner连接只用于准备、竞争和清理。 */
    private static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    /** 本例全部数据库范围。
     * @param tenant 租户
     * @param project 项目
     * @param type 类型
     * @param model 初始模型
     * @param device 设备
     */
    private record Fixture(UUID tenant, UUID project, UUID type, UUID model, UUID device) { }
}
