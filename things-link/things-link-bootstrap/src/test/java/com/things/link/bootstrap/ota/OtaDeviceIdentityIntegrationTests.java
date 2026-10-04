package com.things.link.bootstrap.ota;

import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.device.application.OtaDeviceNotificationRoutePort;
import com.things.link.device.application.OtaDeviceTargetSnapshotPort;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

/** 无Console账号的真实事务/RLS设备身份投影，不能以设备认证事实推导OTA兼容性。 */
class OtaDeviceIdentityIntegrationTests extends AbstractIntegrationTest {
    /** 正式本域锁端口。 */ @Autowired private OtaDeviceIdentityPort identities;
    /** 其他两个当前OTA资格入口共用同一真实设备夹具。 */
    @Autowired private OtaDeviceNotificationRoutePort routes;
    @Autowired private OtaDeviceTargetSnapshotPort targets;
    @Autowired private JdbcTemplate jdbc;
    /** 真实普通APP事务。 */ @Autowired private PlatformTransactionManager transactions;
    /** 设备主体建立精确事务范围，不制造账号。 */ @Autowired private TransactionLocalRlsScope rls;
    /** 本例所有真实资源，精确清理不清全库。 */ private final List<Fixture> fixtures = new ArrayList<>();

    /** 精确模型与代际来自普通RLS查询，无事务不允许借连接执行。 */
    @Test
    void projectsCurrentBindingWithoutInventingConsoleAccount() {
        Fixture f = seed("DIRECT", true);
        assertThat(TenantContext.current()).isEmpty();
        assertThatThrownBy(() -> identities.lockCurrent(auth(f, 1)))
                .isInstanceOf(IllegalTransactionStateException.class);
        var identity = inScope(f, () -> identities.lockCurrent(auth(f, 1))).orElseThrow();
        assertThat(identity.tenantId()).isEqualTo(f.tenant());
        assertThat(identity.projectId()).isEqualTo(f.project());
        assertThat(identity.deviceId()).isEqualTo(f.device());
        assertThat(identity.deviceTypeId()).isEqualTo(f.type());
        assertThat(identity.productKey()).isEqualTo("product_" + f.type());
        assertThat(identity.credentialVersion()).isEqualTo(1);
        assertThat(identity.thingModelVersionId()).isEqualTo(f.model());
        assertThat(identity.schemaDigestAlgorithm()).isEqualTo("PG_JSONB_TEXT_V1_SHA256");
        assertThat(identity.schemaDigest()).isEqualTo("a".repeat(64));
        assertThat(identity.schemaProfile()).isEqualTo("TC_PROPERTY_COMPOSITE_V1");
        assertThat(TenantContext.current()).isEmpty();
    }

    /** 非DIRECT、未发布、软删除和缺绑定均不能取得当前身份。 */
    @ParameterizedTest
    @ValueSource(strings = {"GATEWAY", "SUB_DEVICE", "DRAFT", "DEVICE_DELETED", "TYPE_DELETED", "NO_MODEL"})
    void rejectsIneligibleTypesDevicesAndMissingBinding(String boundary) {
        String kind = List.of("GATEWAY", "SUB_DEVICE").contains(boundary) ? boundary : "DIRECT";
        Fixture f = seed(kind, !"NO_MODEL".equals(boundary));
        switch (boundary) {
            case "DRAFT" -> owner().update("UPDATE dev_type SET status='DRAFT' WHERE id=?", f.type());
            case "DEVICE_DELETED" -> owner().update("UPDATE dev_device SET deleted_at=now() WHERE id=?", f.device());
            case "TYPE_DELETED" -> owner().update("UPDATE dev_type SET deleted_at=now() WHERE id=?", f.type());
            default -> { }
        }
        assertThat(inScope(f, () -> identities.lockCurrent(auth(f, 1)))).isEmpty();
    }

    /** 错租户、项目、RLS范围与轮换前历史代际不能回查升级为当前身份。 */
    @Test
    void rejectsWrongScopeAndOldCredentialGeneration() {
        Fixture f = seed("DIRECT", true);
        Fixture other = seed("DIRECT", true);
        assertThat(inScope(other, () -> identities.lockCurrent(auth(f, 1)))).isEmpty();
        assertThat(inScope(f, () -> identities.lockCurrent(new AuthenticatedDeviceIdentity(other.tenant(), f.project(), f.device(), 1)))).isEmpty();
        assertThat(inScope(f, () -> identities.lockCurrent(new AuthenticatedDeviceIdentity(f.tenant(), other.project(), f.device(), 1)))).isEmpty();
        owner().update("UPDATE dev_device SET credential_version=2 WHERE id=?", f.device());
        assertThat(inScope(f, () -> identities.lockCurrent(auth(f, 1)))).isEmpty();
        assertThat(inScope(f, () -> identities.lockCurrent(auth(f, 2))).orElseThrow().credentialVersion()).isEqualTo(2);
    }

    /** 当前绑定合法切换后返回新权威模型，不缓存旧报告声明或旧模型身份。 */
    @Test
    void readsNewAuthoritativeModelAfterBindingChanges() {
        Fixture f = seed("DIRECT", true);
        UUID nextModel = Uuid7.generate();
        insertModel(f, nextModel, "2.0.0", "b".repeat(64));
        owner().update("UPDATE dev_device SET thing_model_version_id=? WHERE id=?", nextModel, f.device());
        var changed = inScope(f, () -> identities.lockCurrent(auth(f, 1))).orElseThrow();
        assertThat(changed.thingModelVersionId()).isEqualTo(nextModel);
        assertThat(changed.schemaDigest()).isEqualTo("b".repeat(64));
    }

    /** 共享锁真实阻塞轮换代际、设备软删除和类型修改，事务结束后锁释放。 */
    @ParameterizedTest
    @ValueSource(strings = {"generation", "deviceDelete", "typeDelete", "binding"})
    void holdsAuthoritativeRowsAgainstConcurrentMutation(String change) throws Exception {
        Fixture f = seed("DIRECT", true);
        UUID nextModel = Uuid7.generate();
        insertModel(f, nextModel, "2.0.0", "b".repeat(64));
        String sql = switch (change) {
            case "generation" -> "UPDATE dev_device SET credential_version=credential_version+1 WHERE id=?";
            case "deviceDelete" -> "UPDATE dev_device SET deleted_at=now() WHERE id=?";
            case "typeDelete" -> "UPDATE dev_type SET deleted_at=now() WHERE id=?";
            case "binding" -> "UPDATE dev_device SET thing_model_version_id='" + nextModel + "' WHERE id=?";
            default -> throw new AssertionError(change);
        };
        UUID target = "typeDelete".equals(change) ? f.type() : f.device();
        inScope(f, () -> {
            assertThat(identities.lockCurrent(auth(f, 1))).isPresent();
            try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var setting = connection.createStatement(); var mutation = connection.prepareStatement(sql)) {
                setting.execute("SET lock_timeout='150ms'");
                mutation.setObject(1, target);
                assertThatThrownBy(mutation::executeUpdate).isInstanceOfSatisfying(SQLException.class,
                        failure -> assertThat(failure.getSQLState()).isEqualTo("55P03"));
            } catch (SQLException failure) { throw new IllegalStateException(failure); }
            return true;
        });
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var mutation = connection.prepareStatement(sql)) {
            mutation.setObject(1, target);
            assertThat(mutation.executeUpdate()).isEqualTo(1);
        }
    }

    /** 自然到期不提升代际，但首次读取及同事务最终校验都必须拒绝。 */
    @Test
    void rejectsMissingExpiredAndNaturallyExpiringCredentials() {
        Fixture missing = seed("DIRECT", true);
        owner().update("DELETE FROM dev_credential WHERE device_id=?", missing.device());
        assertThat(inScope(missing, () -> identities.lockCurrent(auth(missing, 1)))).isEmpty();
        Fixture f = seed("DIRECT", true);
        owner().update("UPDATE dev_credential SET expires_at=clock_timestamp()+interval '2 seconds' WHERE device_id=?", f.device());
        inScope(f, () -> {
            var identity = identities.lockCurrent(auth(f, 1)).orElseThrow();
            assertThat(identity.credentialExpiresAt()).isNotNull();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(4))
                    .pollInSameThread().until(() -> !identities.credentialValid(auth(f, 1)));
            assertThat(identities.lockCurrent(auth(f, 1))).isEmpty();
            return true;
        });
    }

    /** 未提交凭据更新不阻塞普通MVCC读取；若引入反向凭据锁本例会超时失败。 */
    @Test
    void readsCredentialWithoutWaitingForCredentialRowLock() throws Exception {
        Fixture f = seed("DIRECT", true);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setAutoCommit(false);
            try (var update = connection.prepareStatement("UPDATE dev_credential SET deleted_at=clock_timestamp() WHERE device_id=?")) {
                update.setObject(1, f.device());
                update.executeUpdate();
            }
            try {
                inScope(f, () -> {
                    assertThat(identities.lockCurrent(auth(f, 1))).isPresent();
                    assertThat(identities.credentialValid(auth(f, 1))).isTrue();
                    // 人工持有凭据行锁的夹具验证MVCC；正式凭据写者已按ADR0193先锁设备。
                    try (var setting = connection.createStatement(); var update = connection.prepareStatement(
                            "UPDATE dev_device SET credential_version=credential_version+1 WHERE id=?")) {
                        setting.execute("SET LOCAL lock_timeout='150ms'");
                        update.setObject(1, f.device());
                        assertThatThrownBy(update::executeUpdate).isInstanceOfSatisfying(SQLException.class,
                                failure -> assertThat(failure.getSQLState()).isEqualTo("55P03"));
                    } catch (SQLException failure) { throw new IllegalStateException(failure); }
                    return true;
                });
            } finally { connection.rollback(); }
        }
    }

    /** 每个入口都必须拒绝当前原生协议或禁用配置，凭据有效不等于MQTT资格。 */
    @ParameterizedTest
    @ValueSource(strings = {"HTTP", "COAP", "TCP", "MQTT"})
    void rejectsNonMqttConfigurationAcrossAllOtaPorts(String protocol) {
        Fixture f = seed("DIRECT", true);
        owner().update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol,enabled) VALUES(?,?,?,?,?)",
                f.device(), f.tenant(), f.project(), protocol, !"MQTT".equals(protocol));
        List<Boolean> admitted = inScope(f, () -> List.of(identities.lockCurrent(auth(f, 1)).isPresent(),
                routes.lockCurrent(auth(f, 1)).isPresent(),
                targets.lockExplicit(f.tenant(), f.project(), f.type(), List.of(f.device())).isPresent(),
                identities.credentialValid(auth(f, 1))));
        assertThat(admitted).as("identity, notification, targets, final credential check").containsOnly(false);
    }

    /** 显式MQTT正代次与无行存量MQTT同样可用，不能把新增配置行一概拒绝。 */
    @Test
    void explicitEnabledMqttRemainsEligible() {
        Fixture f = seed("DIRECT", true);
        owner().update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol,config_version) VALUES(?,?,?,'MQTT',7)",
                f.device(), f.tenant(), f.project());
        assertThat(inScope(f, () -> List.of(identities.lockCurrent(auth(f, 1)).isPresent(),
                routes.lockCurrent(auth(f, 1)).isPresent(),
                targets.lockExplicit(f.tenant(), f.project(), f.type(), List.of(f.device())).isPresent(),
                identities.credentialValid(auth(f, 1))))).containsOnly(true);
    }

    /** 真实设备锁等待后必须用新语句读刚提交配置，不能依赖等待前的JOIN快照。 */
    @ParameterizedTest
    @ValueSource(strings = {"identity", "notification", "targets"})
    void rechecksConfigurationAfterDeviceLockWait(String port) throws Exception {
        Fixture f = seed("DIRECT", true);
        var waitingPid = new CompletableFuture<Integer>();
        try (var holder = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            holder.setAutoCommit(false);
            try {
                int blocker;
                try (var query = holder.createStatement(); var rows = query.executeQuery("SELECT pg_backend_pid()")) {
                    rows.next(); blocker = rows.getInt(1);
                }
                try (var lock = holder.prepareStatement("SELECT id FROM dev_device WHERE id=? FOR NO KEY UPDATE")) {
                    lock.setObject(1, f.device()); lock.executeQuery().close();
                }
                var result = pool.submit(() -> inScope(f, () -> {
                    waitingPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    return switch (port) {
                        case "identity" -> identities.lockCurrent(auth(f, 1)).isPresent();
                        case "notification" -> routes.lockCurrent(auth(f, 1)).isPresent();
                        case "targets" -> targets.lockExplicit(f.tenant(), f.project(), f.type(), List.of(f.device())).isPresent();
                        default -> throw new AssertionError(port);
                    };
                }));
                int waiter = waitingPid.get(5, TimeUnit.SECONDS);
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).pollInSameThread()
                        .until(() -> Boolean.TRUE.equals(owner().queryForObject("SELECT ?=ANY(pg_blocking_pids(?))", Boolean.class, blocker, waiter)));
                try (var insert = holder.prepareStatement("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol) VALUES(?,?,?,'HTTP')")) {
                    insert.setObject(1, f.device()); insert.setObject(2, f.tenant()); insert.setObject(3, f.project()); insert.executeUpdate();
                }
                holder.commit();
                assertThat(result.get(10, TimeUnit.SECONDS)).isFalse();
            } finally { holder.rollback(); }
        }
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
            owner().update("DELETE FROM dev_access_binding WHERE project_id=?", f.project());
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
