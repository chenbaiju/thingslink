package com.things.link.bootstrap.ota;

import com.things.link.device.application.OtaDeviceTargetSnapshotPort;
import com.things.link.shared.id.Uuid7;
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

/** 真实PG批量快照只冻结显式DIRECT目标，不额外要求模型、在线或凭据。 */
class OtaDeviceTargetSnapshotIntegrationTests extends AbstractIntegrationTest {
    /** 正式本域锁端口。 */ @Autowired private OtaDeviceTargetSnapshotPort identities;
    /** 真实普通APP事务。 */ @Autowired private PlatformTransactionManager transactions;
    /** 设备主体建立精确事务范围，不制造账号。 */ @Autowired private TransactionLocalRlsScope rls;
    /** 本例所有真实资源，精确清理不清全库。 */ private final List<Fixture> fixtures = new ArrayList<>();

    /** 无模型/凭据仍可排程，结果稳定使用规范UUID排序并保持不可变。 */
    @Test
    void freezesCompleteCanonicalOrderWithoutRequiringModelCredentialOrReport() {
        Fixture f = seed("DIRECT", false);
        UUID high = UUID.fromString("80000000-0000-4000-8000-000000000001");
        addDevice(f, high);
        assertThat(high.compareTo(f.device())).isNegative();
        var result = inScope(f, () -> identities.lockExplicit(f.tenant(),f.project(),f.type(),List.of(high,f.device()))).orElseThrow();
        assertThat(result).extracting(com.things.link.device.application.OtaDeviceTargetSnapshot::deviceId).containsExactly(f.device(),high);
        assertThat(result).allSatisfy(value -> {
            assertThat(value.tenantId()).isEqualTo(f.tenant()); assertThat(value.projectId()).isEqualTo(f.project());
            assertThat(value.deviceTypeId()).isEqualTo(f.type()); assertThat(value.thingModelVersionId()).isNull();
            assertThat(value.credentialVersion()).isEqualTo(1);
        });
        assertThat(owner().queryForObject("SELECT count(*) FROM dev_credential WHERE project_id=?",Long.class,f.project())).isZero();
        assertThatThrownBy(result::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> identities.lockExplicit(f.tenant(),f.project(),f.type(),List.of(f.device())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    /** 上限一千是真实整批读取，不以少量数据掩盖SQL参数和排序边界。 */
    @Test
    void locksAllThousandExplicitTargetsWithinTheFrozenBound() {
        Fixture f=seed("DIRECT",false); var ids=new ArrayList<UUID>(); ids.add(f.device());
        for (int i=1;i<1000;i++) ids.add(Uuid7.generate());
        owner().batchUpdate("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name) VALUES (?,?,?,?,?,'批量快照测试')",
                ids.subList(1,ids.size()),1000,(statement,id)->{
                    statement.setObject(1,id); statement.setObject(2,f.tenant()); statement.setObject(3,f.project());
                    statement.setObject(4,f.type()); statement.setString(5,"device_"+id);
                });
        java.util.Collections.reverse(ids);
        var result=inScope(f,()->identities.lockExplicit(f.tenant(),f.project(),f.type(),ids)).orElseThrow();
        assertThat(result).hasSize(1000);
        assertThat(result).extracting(value->value.deviceId().toString()).isSorted();
    }

    /** 一组中任一目标切到非MQTT，必须整组拒绝，不能返回可用子集。 */
    @Test
    void rejectsMixedProtocolBatchAtomically() {
        Fixture f = seed("DIRECT", false);
        UUID second = Uuid7.generate();
        addDevice(f, second);
        owner().update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol) VALUES(?,?,?,'TCP')",
                second, f.tenant(), f.project());
        try {
            assertThat(inScope(f, () -> identities.lockExplicit(f.tenant(), f.project(), f.type(),
                    List.of(f.device(), second)))).isEmpty();
            assertThat(inScope(f, () -> identities.lockExplicit(f.tenant(), f.project(), f.type(),
                    List.of(f.device())))).isPresent();
        } finally {
            owner().update("DELETE FROM dev_access_binding WHERE device_id=?", second);
        }
    }

    /** 空、重复、null和超过上限名单不是部分查询，必须明确拒绝。 */
    @Test
    void rejectsMalformedExplicitSelection() {
        Fixture f=seed("DIRECT",false);
        var tooMany=java.util.stream.IntStream.range(0,1001).mapToObj(ignored->Uuid7.generate()).toList();
        for (List<UUID> ids:List.of(List.<UUID>of(),List.of(f.device(),f.device()),java.util.Arrays.asList(f.device(),null),tooMany)) {
            // 真实@Repository代理翻译API误用，保留原始参数错误为首因。
            assertThatThrownBy(()->inScope(f,()->identities.lockExplicit(f.tenant(),f.project(),f.type(),ids)))
                    .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    /** 缺失、错类型或跨租户目标均整体空，不泄漏可见子集。 */
    @Test
    void rejectsPartialAndCrossScopeSelection() {
        Fixture f=seed("DIRECT",true), other=seed("DIRECT",true);
        assertThat(inScope(f,()->identities.lockExplicit(f.tenant(),f.project(),f.type(),List.of(f.device(),Uuid7.generate())))).isEmpty();
        assertThat(inScope(f,()->identities.lockExplicit(f.tenant(),f.project(),f.type(),List.of(f.device(),other.device())))).isEmpty();
        assertThat(inScope(other,()->identities.lockExplicit(f.tenant(),f.project(),f.type(),List.of(f.device())))).isEmpty();
        assertThat(inScope(f,()->identities.lockExplicit(other.tenant(),f.project(),f.type(),List.of(f.device())))).isEmpty();
    }

    /** 类型和软删除资格必须保持严格，不能因为排程不查报告而绕过目标身份。 */
    @ParameterizedTest
    @ValueSource(strings={"GATEWAY","SUB_DEVICE","DRAFT","TYPE_DELETED","DEVICE_DELETED"})
    void rejectsUnavailableDirectTargets(String boundary) {
        Fixture f=seed(List.of("GATEWAY","SUB_DEVICE").contains(boundary)?boundary:"DIRECT",false);
        switch(boundary) {
            case "DRAFT" -> owner().update("UPDATE dev_type SET status='DRAFT' WHERE id=?",f.type());
            case "TYPE_DELETED" -> owner().update("UPDATE dev_type SET deleted_at=now() WHERE id=?",f.type());
            case "DEVICE_DELETED" -> owner().update("UPDATE dev_device SET deleted_at=now() WHERE id=?",f.device());
            default -> { }
        }
        assertThat(inScope(f,()->identities.lockExplicit(f.tenant(),f.project(),f.type(),List.of(f.device())))).isEmpty();
    }

    /** 真实并发更新因共享锁返回55P03，外层事务结束后完全相同修改才可提交。 */
    @ParameterizedTest
    @ValueSource(strings={"generation","deviceDelete","typeDelete","binding"})
    void retainsTypeAndEveryDeviceLockUntilCallerTransactionEnds(String change) throws Exception {
        Fixture f=seed("DIRECT",false); UUID second=Uuid7.generate(); addDevice(f,second);
        String sql=switch(change) {
            case "generation" -> "UPDATE dev_device SET credential_version=credential_version+1 WHERE id=?";
            case "deviceDelete" -> "UPDATE dev_device SET deleted_at=now() WHERE id=?";
            case "typeDelete" -> "UPDATE dev_type SET deleted_at=now() WHERE id=?";
            case "binding" -> "UPDATE dev_device SET thing_model_version_id='"+f.model()+"' WHERE id=?";
            default -> throw new AssertionError(change);
        };
        UUID target="typeDelete".equals(change)?f.type():second;
        inScope(f,()->{
            assertThat(identities.lockExplicit(f.tenant(),f.project(),f.type(),List.of(second,f.device()))).isPresent();
            try(var connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
                var setting=connection.createStatement();var statement=connection.prepareStatement(sql)) {
                setting.execute("SET lock_timeout='150ms'"); statement.setObject(1,target);
                assertThatThrownBy(statement::executeUpdate).isInstanceOfSatisfying(SQLException.class,
                        failure->assertThat(failure.getSQLState()).isEqualTo("55P03"));
            } catch(SQLException failure) { throw new IllegalStateException(failure); }
            return true;
        });
        assertThat(owner().update(sql,target)).isEqualTo(1);
    }

    /** 添加同类型未绑定且无凭据的明确设备，保留完整真实FK。 */
    private static void addDevice(Fixture f,UUID id) {
        owner().update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name) VALUES (?,?,?,?,?,'目标测试设备')",
                id,f.tenant(),f.project(),f.type(),"device_"+id);
    }

    /** 只建立已确认设备范围，无ThreadLocal管理身份或owner权限替身。 */
    private <T> T inScope(Fixture f, Supplier<T> work) {
        var transaction = new TransactionTemplate(transactions);
        transaction.setTimeout(5);
        return transaction.execute(status -> {
            rls.establish(f.tenant(), f.project());
            return work.get();
        });
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
