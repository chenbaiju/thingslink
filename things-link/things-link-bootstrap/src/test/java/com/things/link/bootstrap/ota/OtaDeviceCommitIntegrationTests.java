package com.things.link.bootstrap.ota;

import com.things.link.device.application.OtaDeviceCommitPort;
import com.things.link.device.application.OtaDeviceCommitPort.Command;
import com.things.link.device.application.OtaDeviceCommitPort.Decision;
import com.things.link.device.application.OtaDeviceCommitPort.ModelIdentity;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实PG设备提交来源CAS与安全下限，软件证据不代表设备实际刷写。 */
class OtaDeviceCommitIntegrationTests extends AbstractIntegrationTest {
    /** 被测MANDATORY本域端口。 */ @Autowired private OtaDeviceCommitPort commits;
    /** 真实APP事务。 */ @Autowired private PlatformTransactionManager transactions;
    /** 与事务关联的真实应用数据库入口。 */ @Autowired private JdbcTemplate app;
    /** 无管理账号的设备RLS范围。 */ @Autowired private TransactionLocalRlsScope rls;
    /** 本例隔离资源。 */ private final List<Fixture> fixtures = new ArrayList<>();

    /** 同模型提交保留下限与证据，不伪造模型转换，重放保留首次事实。 */
    @Test
    void sameModelCommitAndExactReplayPreserveEvidence() {
        var f=seed("DIRECT",true); var c=command(f,f.model(),"a".repeat(64),1);
        assertThatThrownBy(()->commits.apply(c)).isInstanceOf(IllegalTransactionStateException.class);
        var first=inScope(f,()->commits.apply(c));
        assertThat(first.decision()).isEqualTo(Decision.SAME_MODEL);
        assertThat(first.bindingTransitionId()).isNull();
        var replay=inScope(f,()->commits.apply(c));
        assertThat(replay.replay()).isTrue();
        assertThat(replay.receiptId()).isEqualTo(first.receiptId());
        assertThat(replay.recordedAt()).isEqualTo(first.recordedAt());
        assertThat(count(f,"dev_device_model_binding_history")).isZero();
        assertThat(count(f,"dev_ota_commit")).isEqualTo(1);
        assertThat(inScope(f,()->commits.currentFloor(f.tenant(),f.project(),f.device())).orElseThrow().committedSecurityVersion()).isEqualTo(1);
    }

    /** 来源精确匹配时，历史和当前指针与提交事实一起完成。 */
    @Test
    void adoptsDifferentModelAndExposesExactDomainProof() {
        var f=seed("DIRECT",true); UUID target=Uuid7.generate(); insertModel(f,target,"2.0.0","b".repeat(64));
        var c=command(f,target,"b".repeat(64),1); var result=inScope(f,()->commits.apply(c));
        assertThat(result.decision()).isEqualTo(Decision.CHANGED);
        assertThat(result.bindingTransitionId()).isNotNull();
        assertThat(owner().queryForObject("SELECT thing_model_version_id FROM dev_device WHERE id=?",UUID.class,f.device())).isEqualTo(target);
        assertThat(owner().queryForObject("SELECT dev_ota_commit_matches(?,?,?,?,?,?,?,?,?)",Boolean.class,
                f.tenant(),f.project(),f.device(),c.transitionKey(),result.receiptId(),target,"b".repeat(64),1,"c".repeat(64))).isTrue();
        assertThat(inScope(f,()->commits.apply(c)).decision()).isEqualTo(Decision.CHANGED);
    }

    /** 模型指针、权威摘要或类型资格冲突仍保留已发生的计数器提交，不错误绑定。 */
    @ParameterizedTest
    @ValueSource(strings={"pointer","digest","type"})
    void preservesCommittedFloorWhenModelAdoptionConflicts(String boundary) {
        var f=seed("DIRECT",true); UUID target=Uuid7.generate(); insertModel(f,target,"2.0.0","b".repeat(64));
        var c=command(f,target,"digest".equals(boundary)?"d".repeat(64):"b".repeat(64),2);
        if("pointer".equals(boundary)) owner().update("UPDATE dev_device SET thing_model_version_id=? WHERE id=?",target,f.device());
        if("type".equals(boundary)) owner().update("UPDATE dev_type SET status='DRAFT' WHERE id=?",f.type());
        var result=inScope(f,()->commits.apply(c));
        assertThat(result.decision()).isEqualTo(Decision.SOURCE_CONFLICT);
        assertThat(result.floor().committedSecurityVersion()).isEqualTo(2);
        assertThat(count(f,"dev_device_model_binding_history")).isZero();
        assertThat(inScope(f,()->commits.apply(c)).decision()).isEqualTo(Decision.SOURCE_CONFLICT);
    }

    /** 下限不能降低，同安全版本不能换制品，同许可键不能换完整元组。 */
    @Test
    void rejectsSecurityConflictsWithoutWriting() {
        var f=seed("DIRECT",true); var c=command(f,f.model(),"a".repeat(64),2);
        inScope(f,()->commits.apply(c));
        assertThat(inScope(f,()->commits.apply(command(f,f.model(),"a".repeat(64),1))).decision()).isEqualTo(Decision.SECURITY_CONFLICT);
        var changed=new Command(c.identity(),c.deviceTypeId(),c.expectedSource(),c.target(),Uuid7.generate(),0,2,"d".repeat(64),true);
        assertThat(inScope(f,()->commits.apply(changed)).decision()).isEqualTo(Decision.SECURITY_CONFLICT);
        var sameKey=new Command(c.identity(),c.deviceTypeId(),c.expectedSource(),c.target(),c.transitionKey(),0,3,c.artifactSha256(),true);
        assertThat(inScope(f,()->commits.apply(sameKey)).decision()).isEqualTo(Decision.SECURITY_CONFLICT);
        assertThat(count(f,"dev_ota_commit")).isEqualTo(1);
    }

    /** 错误RLS、旧代际和自然到期均不能通过模型冲突分支写入下限。 */
    @Test
    void rejectsInvalidIdentityWithoutEvidence() {
        var f=seed("DIRECT",true); var other=seed("DIRECT",true); var c=command(f,f.model(),"a".repeat(64),1);
        assertThat(inScope(other,()->commits.apply(c)).decision()).isEqualTo(Decision.IDENTITY_REJECTED);
        owner().update("UPDATE dev_device SET credential_version=2 WHERE id=?",f.device());
        assertThat(inScope(f,()->commits.apply(c)).decision()).isEqualTo(Decision.IDENTITY_REJECTED);
        owner().update("UPDATE dev_device SET credential_version=1 WHERE id=?",f.device());
        owner().update("UPDATE dev_credential SET expires_at=clock_timestamp()-interval '1 second' WHERE device_id=?",f.device());
        assertThat(inScope(f,()->commits.apply(c)).decision()).isEqualTo(Decision.IDENTITY_REJECTED);
        assertThat(count(f,"dev_ota_commit")).isZero();
    }

    /** 外层失败回滚指针、历史、提交证据与下限，不留下部分成功。 */
    @Test
    void rollsBackEntireCommitWithCallingTransaction() {
        var f=seed("DIRECT",true); UUID target=Uuid7.generate(); insertModel(f,target,"2.0.0","b".repeat(64));
        assertThatThrownBy(()->inScope(f,()->{ commits.apply(command(f,target,"b".repeat(64),1)); throw new IllegalStateException("audit failed"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count(f,"dev_ota_commit")).isZero(); assertThat(count(f,"dev_ota_security_floor")).isZero();
        assertThat(count(f,"dev_device_model_binding_history")).isZero();
        assertThat(owner().queryForObject("SELECT thing_model_version_id FROM dev_device WHERE id=?",UUID.class,f.device())).isEqualTo(f.model());
    }

    /** 提交锁保留到调用事务结束，真实并发代际更新不能穿过。 */
    @Test
    void holdsDeviceLockUntilCallerCommits() {
        var f=seed("DIRECT",true);
        inScope(f,()->{
            commits.apply(command(f,f.model(),"a".repeat(64),1));
            try(var connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
                var setting=connection.createStatement();var update=connection.prepareStatement("UPDATE dev_device SET credential_version=2 WHERE id=?")) {
                setting.execute("SET lock_timeout='150ms'");update.setObject(1,f.device());
                assertThatThrownBy(update::executeUpdate).isInstanceOfSatisfying(SQLException.class,
                        ex->assertThat(ex.getSQLState()).isEqualTo("55P03"));
            } catch(SQLException ex) { throw new IllegalStateException(ex); }
            return true;
        });
    }

    /** 数据库直接篡改下限或不可变证据同样失败。 */
    @Test
    void databaseRejectsFloorRegressionAndReceiptMutation() {
        var f=seed("DIRECT",true);inScope(f,()->commits.apply(command(f,f.model(),"a".repeat(64),2)));
        assertThatThrownBy(()->owner().update("UPDATE dev_ota_security_floor SET committed_security_version=1 WHERE device_id=?",f.device()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->owner().update("UPDATE dev_ota_commit SET decision='SOURCE_CONFLICT' WHERE device_id=?",f.device()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /** 迟到真实提交保留下限但不采用模型，重放不能把观察升级为成功。 */
    @Test
    void recordsLateCommitWithoutAdoptingModel() {
        var f=seed("DIRECT",true);UUID target=Uuid7.generate();insertModel(f,target,"2.0.0","b".repeat(64));
        var c=command(f,target,"b".repeat(64),1);
        var late=new Command(c.identity(),c.deviceTypeId(),c.expectedSource(),c.target(),c.transitionKey(),0,1,c.artifactSha256(),false);
        var first=inScope(f,()->commits.apply(late));
        assertThat(first.decision()).isEqualTo(Decision.OBSERVED_ONLY);
        assertThat(first.floor().committedSecurityVersion()).isEqualTo(1);
        assertThat(count(f,"dev_device_model_binding_history")).isZero();
        assertThat(inScope(f,()->commits.apply(late)).decision()).isEqualTo(Decision.OBSERVED_ONLY);
        assertThat(inScope(f,()->commits.apply(c)).decision()).isEqualTo(Decision.SECURITY_CONFLICT);
        assertThat(owner().queryForObject("SELECT thing_model_version_id FROM dev_device WHERE id=?",UUID.class,f.device())).isEqualTo(f.model());
    }

    /** 应用角色直接伪造不存在模型的SAME_MODEL，不能获得成功证据或下限。 */
    @Test
    void rejectsFabricatedSameModelReceiptUsingActualApplicationRole() {
        var f=seed("DIRECT",true);UUID fake=Uuid7.generate();
        assertThatThrownBy(()->inScope(f,()->{
            app.update("""
                    INSERT INTO dev_ota_commit(id,tenant_id,project_id,device_id,device_type_id,credential_version,
                    transition_key,source_model_id,source_digest_algorithm,source_digest,source_profile,
                    target_model_id,target_digest_algorithm,target_digest,target_profile,source_security_version,
                    committed_security_version,artifact_sha256,decision,binding_transition_id,recorded_at,adopt_model)
                    VALUES(?,?,?,?,?,1,?,?,'PG_JSONB_TEXT_V1_SHA256',repeat('a',64),'TC_PROPERTY_COMPOSITE_V1',
                    ?,'PG_JSONB_TEXT_V1_SHA256',repeat('a',64),'TC_PROPERTY_COMPOSITE_V1',0,1,repeat('c',64),'SAME_MODEL',NULL,clock_timestamp(),true)
                    """,Uuid7.generate(),f.tenant(),f.project(),f.device(),f.type(),Uuid7.generate(),fake,fake);
            return true;
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(count(f,"dev_ota_commit")).isZero();assertThat(count(f,"dev_ota_security_floor")).isZero();
    }

    /** 真实成功证据保留历史，但当前指针改变后不能再充当当前成功元组证明。 */
    @Test
    void successProjectionRejectsCurrentModelDrift() {
        var f=seed("DIRECT",true);var c=command(f,f.model(),"a".repeat(64),1);var receipt=inScope(f,()->commits.apply(c));
        UUID next=Uuid7.generate();insertModel(f,next,"2.0.0","b".repeat(64));
        owner().update("UPDATE dev_device SET thing_model_version_id=? WHERE id=?",next,f.device());
        assertThat(owner().queryForObject("SELECT dev_ota_commit_matches(?,?,?,?,?,?,?,?,?)",Boolean.class,
                f.tenant(),f.project(),f.device(),c.transitionKey(),receipt.receiptId(),f.model(),"a".repeat(64),1,"c".repeat(64))).isFalse();
        assertThat(owner().queryForObject("SELECT dev_ota_commit_observation_matches(?,?,?,?,?,?,?,?,?)",Boolean.class,
                f.tenant(),f.project(),f.device(),c.transitionKey(),receipt.receiptId(),f.model(),"a".repeat(64),1,"c".repeat(64))).isTrue();
    }

    /** 本例明确模型摘要与保护计数器元组。 */
    private static Command command(Fixture f,UUID target,String digest,long counter) {
        return new Command(auth(f,1),f.type(),new ModelIdentity(f.model(),"PG_JSONB_TEXT_V1_SHA256","a".repeat(64),"TC_PROPERTY_COMPOSITE_V1"),
                new ModelIdentity(target,"PG_JSONB_TEXT_V1_SHA256",digest,"TC_PROPERTY_COMPOSITE_V1"),Uuid7.generate(),0,counter,"c".repeat(64),true);
    }
    /** 仅静态测试表名统计本例归属行。 */
    private static int count(Fixture f,String table) { return owner().queryForObject("SELECT count(*) FROM "+table+" WHERE project_id=?",Integer.class,f.project()); }

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
