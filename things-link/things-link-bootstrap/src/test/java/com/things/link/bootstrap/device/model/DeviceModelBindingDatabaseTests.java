package com.things.link.bootstrap.device.model;

import com.things.link.device.application.ThingModelVersionBindingService;
import com.things.link.device.application.ThingModelVersionService;
import com.things.link.device.domain.ThingModelVersion;
import com.things.link.device.domain.ThingModelVersionRepository.BindingTransition;
import com.things.link.shared.tenant.RlsScope;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * ADR0059第3项：真实APP_ROLE旁路写入也须保持设备类型与当前版本归属，旧非空绑定不能分步清空绕过。
 * 行守卫只保护指针边界，历史追加仍由服务负责；本类不把原始SQL指针写入冒称数据库已强制历史合同。
 * 夹具按项目隔离，版本由真实发布服务生成，全程保留已有RLS、拓扑约束及不可变版本触发器。
 */
@DisplayName("设备版本归属数据库约束与双连接竞争")
class DeviceModelBindingDatabaseTests extends AbstractIntegrationTest {

    /** 将数据库整项目JSONB快照结构化，避免错误写入只改了非主字段却被漏检。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** ADR0059旧非空pointer保护的结构化约束标识，不能用其他23514错误冒充。 */
    private static final String BINDING_GUARD = "dev_device_model_binding_guard";
    /** 原生三列外键同时检查project/type/version，不以普通SELECT代替并发参照完整性。 */
    private static final String OWNERSHIP_FK = "dev_device_project_type_model_version_fk";
    /** SQL NULL会绕过普通复合外键，独立CHECK负责禁止无类型却有版本。 */
    private static final String REQUIRES_TYPE_CHECK = "dev_device_model_version_requires_type_ck";
    /** 生成真实不可变版本及摘要，不用手工版本行伪造归属事实。 */
    @Autowired private ThingModelVersionService versionService;
    /** 合法同type升级与回滚通过现有历史追加/CAS事务链验证兼容性。 */
    @Autowired private ThingModelVersionBindingService bindingService;
    /** 服务事务内检查真实APP_ROLE，并观察尚未提交的指针与历史。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 显式外层回滚证明服务追加历史与指针变化属于同一原子单元。 */
    @Autowired private PlatformTransactionManager transactionManager;

    /** 攻击路径分别覆盖改型、清空与同时重写，不能只验证最后三列是否满足外键。 */
    private enum ForbiddenChange {
        /** 保留旧版本却替换设备类型。 */ CHANGE_TYPE,
        /** 保留旧版本却清空设备类型。 */ CLEAR_TYPE,
        /** 先清指针再换类型，第一步即须拒绝。 */ CLEAR_POINTER_THEN_TYPE,
        /** 同时换type和pointer，最终三列匹配仍不得绕过版本化后固定类型的规定。 */ REPLACE_TYPE_AND_POINTER,
        /** 同时清空type和pointer，不能借NULL外键规则抹去绑定。 */ CLEAR_TYPE_AND_POINTER
    }

    /** 每一种已版本化旁路变更都以行守卫23514拒绝，之前同事务的名称修改也必须回滚。 */
    @ParameterizedTest
    @EnumSource(ForbiddenChange.class)
    void boundDeviceCannotChangeTypeOrClearItsPointer(ForbiddenChange change) throws Exception {
        Fixture fixture = fixture(true);
        assertRejectedUnchanged(fixture, "23514", BINDING_GUARD, connection -> {
            switch (change) {
                case CHANGE_TYPE -> execute(connection,
                        "UPDATE public.dev_device SET device_type_id = ? WHERE id = ?", fixture.typeB(), fixture.deviceId());
                case CLEAR_TYPE -> execute(connection,
                        "UPDATE public.dev_device SET device_type_id = NULL WHERE id = ?", fixture.deviceId());
                case CLEAR_POINTER_THEN_TYPE -> {
                    execute(connection, "UPDATE public.dev_device SET thing_model_version_id = NULL WHERE id = ?", fixture.deviceId());
                    execute(connection, "UPDATE public.dev_device SET device_type_id = ? WHERE id = ?", fixture.typeB(), fixture.deviceId());
                }
                case REPLACE_TYPE_AND_POINTER -> execute(connection, """
                        UPDATE public.dev_device SET device_type_id = ?, thing_model_version_id = ? WHERE id = ?
                        """, fixture.typeB(), fixture.versionB(), fixture.deviceId());
                case CLEAR_TYPE_AND_POINTER -> execute(connection, """
                        UPDATE public.dev_device SET device_type_id = NULL, thing_model_version_id = NULL WHERE id = ?
                        """, fixture.deviceId());
            }
        });
    }

    /** 未版本化或已绑定设备只改pointer时，另一类型版本均由三列外键拒绝，不能误归因为换型行守卫。 */
    @Test
    void anotherTypesVersionIsRejectedBeforeAndAfterInitialBinding() throws Exception {
        Fixture fixture = fixture(false);
        assertRejectedUnchanged(fixture, "23503", OWNERSHIP_FK, connection -> execute(connection,
                "UPDATE public.dev_device SET thing_model_version_id = ? WHERE id = ?", fixture.versionB(), fixture.deviceId()));
        try (Connection connection = scopedConnection(fixture.projectId())) {
            writeInitialBinding(connection, fixture, fixture.versionA());
            connection.commit();
        }
        assertRejectedUnchanged(fixture, "23503", OWNERSHIP_FK, connection -> execute(connection,
                "UPDATE public.dev_device SET thing_model_version_id = ? WHERE id = ?", fixture.versionB(), fixture.deviceId()));
    }

    /** 更新和插入都不能利用type为NULL令复合外键跳过检查；CHECK必须准确报告自身约束。 */
    @Test
    void nonNullVersionRequiresTypeForBothInsertAndUpdate() throws Exception {
        Fixture fixture = fixture(false);
        assertRejectedUnchanged(fixture, "23514", REQUIRES_TYPE_CHECK, connection -> execute(connection, """
                UPDATE public.dev_device SET device_type_id = NULL, thing_model_version_id = ? WHERE id = ?
                """, fixture.versionA(), fixture.deviceId()));
        assertRejectedUnchanged(fixture, "23514", REQUIRES_TYPE_CHECK, connection -> execute(connection, """
                INSERT INTO public.dev_device
                    (id, tenant_id, project_id, device_type_id, thing_model_version_id, device_key, name)
                VALUES (?, ?, ?, NULL, ?, 'null_type_pointer', '非法无类型指针')
                """, UUID.randomUUID(), fixture.tenantId(), fixture.projectId(), fixture.versionA()));
    }

    /** 行守卫不阻止同类型版本推进；真实服务仍按INITIAL→UPGRADE→ROLLBACK追加历史并CAS指针。 */
    @Test
    void sameTypeServiceUpgradeAndRollbackRetainInitialHistory() throws Exception {
        Fixture fixture = fixture(true);
        JsonNode before = snapshot(fixture);
        BindingTransition upgrade = inScope(fixture, () -> bindingService.bind(fixture.projectId(), fixture.deviceId(),
                fixture.versionA2(), UUID.randomUUID(), BindingTransition.TransitionType.UPGRADE, Instant.now()));
        assertThat(upgrade.fromVersionId()).isEqualTo(fixture.versionA());
        assertThat(upgrade.toVersionId()).isEqualTo(fixture.versionA2());
        JsonNode upgraded = snapshot(fixture);
        assertPointer(upgraded, fixture.typeA(), fixture.versionA2());
        assertThat(upgraded.get("history")).hasSize(2);
        assertThat(upgraded.get("history")).contains(before.get("history").get(0));
        assertThat(upgraded.get("versions")).isEqualTo(before.get("versions"));

        BindingTransition rollback = inScope(fixture, () -> bindingService.bind(fixture.projectId(), fixture.deviceId(),
                fixture.versionA(), UUID.randomUUID(), BindingTransition.TransitionType.ROLLBACK, Instant.now()));
        assertThat(rollback.fromVersionId()).isEqualTo(fixture.versionA2());
        assertThat(rollback.toVersionId()).isEqualTo(fixture.versionA());
        JsonNode rolledBack = snapshot(fixture);
        assertPointer(rolledBack, fixture.typeA(), fixture.versionA());
        assertThat(rolledBack.get("history")).hasSize(3);
        assertThat(rolledBack.get("history")).contains(before.get("history").get(0));
        assertThat(rolledBack.get("versions")).isEqualTo(before.get("versions"));
    }

    /** 无拓扑设备可编辑或软删，但旧当前版本、INITIAL与不可变发布快照不能被附带清除。 */
    @Test
    void metadataAndSoftDeletionPreserveCurrentVersionFacts() throws Exception {
        Fixture fixture = fixture(true);
        JsonNode before = snapshot(fixture);
        try (Connection connection = scopedConnection(fixture.projectId())) {
            assertThat(execute(connection, """
                    UPDATE public.dev_device SET name = '合法编辑后删除', deleted_at = now(), updated_at = now() WHERE id = ?
                    """, fixture.deviceId())).isEqualTo(1);
            connection.commit();
        }
        JsonNode after = snapshot(fixture);
        assertPointer(after, fixture.typeA(), fixture.versionA());
        assertThat(after.get("device").get("name").asString()).isEqualTo("合法编辑后删除");
        assertThat(after.get("device").get("deleted_at").isNull()).isFalse();
        assertThat(after.get("history")).isEqualTo(before.get("history"));
        assertThat(after.get("versions")).isEqualTo(before.get("versions"));
        assertThat(after.get("types")).isEqualTo(before.get("types"));
    }

    /** 外层业务回滚必须同时撤销服务CAS和历史追加，不能只恢复指针却留下一条已提交转换。 */
    @Test
    void outerTransactionRollbackRestoresPointerHistoryAndMetadata() throws Exception {
        Fixture fixture = fixture(true);
        JsonNode before = snapshot(fixture);
        inScope(fixture, () -> new TransactionTemplate(transactionManager).execute(status -> {
            assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
            jdbcTemplate.update("UPDATE public.dev_device SET name = '必须回滚的名称' WHERE id = ?", fixture.deviceId());
            BindingTransition transition = bindingService.bind(fixture.projectId(), fixture.deviceId(), fixture.versionA2(),
                    UUID.randomUUID(), BindingTransition.TransitionType.UPGRADE, Instant.now());
            assertThat(transition.toVersionId()).isEqualTo(fixture.versionA2());
            assertThat(jdbcTemplate.queryForObject("SELECT thing_model_version_id FROM public.dev_device WHERE id = ?",
                    UUID.class, fixture.deviceId())).isEqualTo(fixture.versionA2());
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM public.dev_device_model_binding_history WHERE device_id = ?",
                    Integer.class, fixture.deviceId())).isEqualTo(2);
            status.setRollbackOnly();
            return null;
        }));
        assertThat(snapshot(fixture)).isEqualTo(before);
    }

    /** 新原生外键和行守卫不能打开项目读取权限，无范围及另一项目范围均不可观察或修改目标。 */
    @Test
    void bindingConstraintsPreserveFailClosedProjectRls() throws Exception {
        Fixture fixture = fixture(true);
        Fixture other = fixture(false);
        JsonNode before = snapshot(fixture);
        try (Connection connection = appConnection()) {
            assertThat(integer(connection, "SELECT count(*) FROM public.dev_device WHERE id = ?", fixture.deviceId())).isZero();
            assertThat(integer(connection, "SELECT count(*) FROM public.dev_thing_model_version WHERE id = ?", fixture.versionA())).isZero();
            assertThat(integer(connection, "SELECT count(*) FROM public.dev_device_model_binding_history WHERE device_id = ?",
                    fixture.deviceId())).isZero();
        }
        try (Connection connection = scopedConnection(other.projectId())) {
            assertThat(integer(connection, "SELECT count(*) FROM public.dev_device WHERE id = ?", fixture.deviceId())).isZero();
            assertThat(execute(connection, "UPDATE public.dev_device SET thing_model_version_id = NULL WHERE id = ?",
                    fixture.deviceId())).isZero();
            connection.commit();
        }
        assertThat(snapshot(fixture)).isEqualTo(before);
    }

    /** 首次绑定先写并持设备行锁时，换型等待者必须看到已提交的非空OLD pointer并由23514守卫拒绝。 */
    @Test
    void initialPointerWriterMakesWaitingTypeChangeRecheckCommittedBinding() throws Exception {
        Fixture fixture = fixture(false);
        ExecutorService requests = Executors.newSingleThreadExecutor();
        try (Connection first = scopedConnection(fixture.projectId())) {
            try {
                writeInitialBinding(first, fixture, fixture.versionA());
                JsonNode committed = snapshot(first, fixture);
                CompletableFuture<Integer> waiterPid = new CompletableFuture<>();
                Future<Throwable> waiting = submitWrite(requests, fixture, waiterPid, connection -> execute(connection,
                        "UPDATE public.dev_device SET device_type_id = ?, name = '不得提交的换型' WHERE id = ?",
                        fixture.typeB(), fixture.deviceId()));
                assertBlocking(waiterPid.get(5, TimeUnit.SECONDS), integer(first, "SELECT pg_backend_pid()"), waiting);
                first.commit();
                assertDatabaseFailure(waiting.get(10, TimeUnit.SECONDS), "23514", BINDING_GUARD);
                assertThat(snapshot(fixture)).isEqualTo(committed);
            } finally {
                first.rollback();
            }
        } finally {
            stopRequests(requests);
        }
    }

    /** 未版本化换型先写时，旧类型版本写者真实等待；提交后须由三列FK按新type拒绝，而非沿用旧快照。 */
    @Test
    void typeWriterMakesWaitingOldTypePointerFailNativeOwnershipForeignKey() throws Exception {
        Fixture fixture = fixture(false);
        ExecutorService requests = Executors.newSingleThreadExecutor();
        try (Connection first = scopedConnection(fixture.projectId())) {
            try {
                assertThat(execute(first, "UPDATE public.dev_device SET device_type_id = ?, name = '先提交类型B' WHERE id = ?",
                        fixture.typeB(), fixture.deviceId())).isEqualTo(1);
                JsonNode committed = snapshot(first, fixture);
                CompletableFuture<Integer> waiterPid = new CompletableFuture<>();
                Future<Throwable> waiting = submitWrite(requests, fixture, waiterPid,
                        connection -> writeInitialBinding(connection, fixture, fixture.versionA()));
                assertBlocking(waiterPid.get(5, TimeUnit.SECONDS), integer(first, "SELECT pg_backend_pid()"), waiting);
                first.commit();
                assertDatabaseFailure(waiting.get(10, TimeUnit.SECONDS), "23503", OWNERSHIP_FK);
                assertThat(snapshot(fixture)).isEqualTo(committed);
            } finally {
                first.rollback();
            }
        } finally {
            stopRequests(requests);
        }
        // 失败事务已回滚，按真实当前类型选择正确版本后可建立合法首次绑定。
        try (Connection retry = scopedConnection(fixture.projectId())) {
            writeInitialBinding(retry, fixture, fixture.versionB());
            retry.commit();
        }
        JsonNode retried = snapshot(fixture);
        assertPointer(retried, fixture.typeB(), fixture.versionB());
        assertThat(retried.get("history")).hasSize(1);
        assertThat(retried.get("history").get(0).get("to_model_version_id").asString()).isEqualTo(fixture.versionB().toString());
    }

    /** 同名临时版本表和搜索路径不能欺骗原生外键；其约束应始终引用迁移绑定的public关系身份。 */
    @Test
    void temporaryVersionTableCannotShadowNativeOwnershipConstraint() throws Exception {
        Fixture fixture = fixture(false);
        assertRejectedUnchanged(fixture, "23503", OWNERSHIP_FK, connection -> {
            execute(connection, "CREATE TEMP TABLE dev_thing_model_version AS SELECT * FROM public.dev_thing_model_version WHERE project_id = ?",
                    fixture.projectId());
            execute(connection, "UPDATE pg_temp.dev_thing_model_version SET device_type_id = ? WHERE id = ?",
                    fixture.typeA(), fixture.versionB());
            text(connection, "SELECT set_config('search_path', 'pg_temp, public', true)");
            assertThat(text(connection, "SELECT device_type_id::text FROM dev_thing_model_version WHERE id = ?", fixture.versionB()))
                    .isEqualTo(fixture.typeA().toString());
            execute(connection, "UPDATE public.dev_device SET thing_model_version_id = ? WHERE id = ?",
                    fixture.versionB(), fixture.deviceId());
        });
    }

    /**
     * 平台owner仅准备随机租户/项目；所有设备域写入均为APP_ROLE，版本由真实发布服务生成。
     * 原始设备/INITIAL仅是合法数据库夹具，不借其声称验证了HTTP认证或数据库强制追加历史。
     */
    private Fixture fixture(boolean bound) throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID typeA = UUID.randomUUID();
        UUID typeB = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        try (Connection owner = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, '设备版本数据库验收租户')", tenantId);
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, project_key) VALUES (?, ?, '设备版本数据库验收项目', ?)",
                    projectId, tenantId, "dbversion_" + projectId.toString().replace("-", ""));
        }
        try (Connection connection = scopedConnection(projectId)) {
            for (UUID typeId : new UUID[]{typeA, typeB}) {
                execute(connection, """
                        INSERT INTO public.dev_type
                            (id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type, status)
                        VALUES (?, ?, ?, ?, '版本归属类型', 'DIRECT', 'STANDARD', 'WIFI', 'PUBLISHED')
                        """, typeId, tenantId, projectId, "type_" + typeId.toString().replace("-", ""));
            }
            connection.commit();
        }
        RlsScopeContext.set(new RlsScope(tenantId, projectId));
        Fixture fixture;
        try {
            fixture = new TransactionTemplate(transactionManager).execute(status -> {
                assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
                JsonNode model = JSON.readTree("{\"properties\":{},\"events\":{},\"commands\":{}}");
                ThingModelVersion versionA = versionService.publish(projectId, typeA, "1.0.0", ThingModelVersion.ChangeLevel.MAJOR, model);
                ThingModelVersion versionA2 = versionService.publish(projectId, typeA, "1.0.1", ThingModelVersion.ChangeLevel.PATCH, model);
                ThingModelVersion versionB = versionService.publish(projectId, typeB, "1.0.0", ThingModelVersion.ChangeLevel.MAJOR, model);
                return new Fixture(tenantId, projectId, typeA, typeB, deviceId, versionA.id(), versionA2.id(), versionB.id());
            });
        } finally {
            RlsScopeContext.clear();
        }
        assertThat(fixture).isNotNull();
        try (Connection connection = scopedConnection(projectId)) {
            execute(connection, """
                    INSERT INTO public.dev_device (id, tenant_id, project_id, device_type_id, device_key, name)
                    VALUES (?, ?, ?, ?, 'binding_device', '初始名称')
                    """, deviceId, tenantId, projectId, typeA);
            if (bound) {
                writeInitialBinding(connection, fixture, fixture.versionA());
            }
            connection.commit();
        }
        JsonNode state = snapshot(fixture);
        assertPointer(state, typeA, bound ? fixture.versionA() : null);
        assertThat(state.get("history")).hasSize(bound ? 1 : 0);
        assertThat(state.get("versions")).hasSize(3);
        return fixture;
    }

    /** 精确写入合法首次pointer及INITIAL夹具，同一事务中失败时两者一起撤销；不禁用任何触发器。 */
    private void writeInitialBinding(Connection connection, Fixture fixture, UUID versionId) throws SQLException {
        assertThat(execute(connection, """
                UPDATE public.dev_device SET thing_model_version_id = ? WHERE project_id = ? AND id = ?
                """, versionId, fixture.projectId(), fixture.deviceId())).isEqualTo(1);
        assertThat(execute(connection, """
                INSERT INTO public.dev_device_model_binding_history
                    (id, tenant_id, project_id, device_id, from_model_version_id, to_model_version_id,
                     transition_key, transition_type, effective_at)
                VALUES (?, ?, ?, ?, NULL, ?, ?, 'INITIAL', now())
                """, UUID.randomUUID(), fixture.tenantId(), fixture.projectId(), fixture.deviceId(), versionId, UUID.randomUUID()))
                .isEqualTo(1);
    }

    /** 失败前加入同事务编辑，准确检查SQLSTATE/CONSTRAINT后回滚，并比对整个项目的版本与设备事实。 */
    private void assertRejectedUnchanged(Fixture fixture, String sqlState, String constraint, SqlAction action) throws Exception {
        JsonNode before = snapshot(fixture);
        try (Connection connection = scopedConnection(fixture.projectId())) {
            try {
                assertThat(execute(connection, "UPDATE public.dev_device SET name = '失败应一起回滚' WHERE id = ?", fixture.deviceId()))
                        .isEqualTo(1);
                Throwable failure = catchThrowable(() -> {
                    action.run(connection);
                    connection.commit();
                });
                assertDatabaseFailure(failure, sqlState, constraint);
            } finally {
                connection.rollback();
            }
        }
        assertThat(snapshot(fixture)).isEqualTo(before);
    }

    /** 服务器结构化SQLSTATE和约束名共同证明拒绝来自本合同，语法错误或锁超时均不能冒充成功。 */
    private void assertDatabaseFailure(Throwable failure, String sqlState, String constraint) {
        assertThat(failure).isInstanceOf(PSQLException.class);
        PSQLException postgres = (PSQLException) failure;
        assertThat(postgres.getSQLState()).isEqualTo(sqlState);
        assertThat(postgres.getServerErrorMessage()).isNotNull();
        assertThat(postgres.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
    }

    /** 工作线程拥有独立APP连接，先公布物理PID再执行SQL，错误跨完整事务边界返回供主线程检查。 */
    private Future<Throwable> submitWrite(ExecutorService requests, Fixture fixture, CompletableFuture<Integer> pid, SqlAction action) {
        return requests.submit(() -> {
            try (Connection connection = scopedConnection(fixture.projectId())) {
                try {
                    pid.complete(integer(connection, "SELECT pg_backend_pid()"));
                    return catchThrowable(() -> {
                        action.run(connection);
                        connection.commit();
                    });
                } finally {
                    connection.rollback();
                }
            } catch (Throwable failure) {
                pid.completeExceptionally(failure);
                return failure;
            }
        });
    }

    /** 独立APP观察器读取真实阻塞图；两个业务连接和观察连接的PID都必须不同，不能用sleep推测顺序。 */
    private void assertBlocking(int waitingPid, int blockingPid, Future<?> waiting) throws SQLException {
        assertThat(waitingPid).isNotEqualTo(blockingPid);
        try (Connection observer = appConnection(); PreparedStatement query = observer.prepareStatement(
                "SELECT pg_backend_pid(), ? = ANY(pg_blocking_pids(?))")) {
            query.setInt(1, blockingPid);
            query.setInt(2, waitingPid);
            query.setQueryTimeout(3);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                try (ResultSet row = query.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getInt(1)).isNotEqualTo(waitingPid).isNotEqualTo(blockingPid);
                    if (row.getBoolean(2)) {
                        return;
                    }
                }
                assertThat(waiting.isDone()).as("写者应真实等待设备锁，不能提前结束").isFalse();
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("未观察到版本写入事务 " + waitingPid + " 等待设备持锁事务 " + blockingPid);
    }

    /** 调用处先释放全部外层行锁，再有界停止工作线程，数据库超时只作为最后清理保障。 */
    private void stopRequests(ExecutorService requests) throws InterruptedException {
        requests.shutdownNow();
        assertThat(requests.awaitTermination(25, TimeUnit.SECONDS)).as("数据库并发工作线程必须退出").isTrue();
    }

    /** 服务没有HTTP身份入口，本处只提供真实项目RLS范围，不冒称通过了控制台权限链。 */
    private <T> T inScope(Fixture fixture, Supplier<T> action) {
        RlsScopeContext.set(new RlsScope(fixture.tenantId(), fixture.projectId()));
        try {
            return action.get();
        } finally {
            RlsScopeContext.clear();
        }
    }

    /** 一次应用读取快照覆盖设备整行、类型、版本与全部绑定历史，包括软删除设备。 */
    private JsonNode snapshot(Fixture fixture) throws SQLException {
        try (Connection connection = scopedConnection(fixture.projectId())) {
            return snapshot(connection, fixture);
        }
    }

    /** 指定连接允许竞争先到者在提交前固定预期快照，随后比对失败等待者没有任何附带变更。 */
    private JsonNode snapshot(Connection connection, Fixture fixture) throws SQLException {
        return JSON.readTree(text(connection, """
                SELECT jsonb_build_object(
                    'device', (SELECT to_jsonb(d) FROM public.dev_device d WHERE d.project_id = ? AND d.id = ?),
                    'devices', (SELECT jsonb_agg(to_jsonb(d) ORDER BY d.id) FROM public.dev_device d),
                    'types', (SELECT jsonb_agg(to_jsonb(t) ORDER BY t.id) FROM public.dev_type t),
                    'versions', (SELECT jsonb_agg(to_jsonb(v) ORDER BY v.id) FROM public.dev_thing_model_version v),
                    'history', COALESCE((SELECT jsonb_agg(to_jsonb(h) ORDER BY h.id)
                        FROM public.dev_device_model_binding_history h), '[]'::jsonb))::text
                """, fixture.projectId(), fixture.deviceId()));
    }

    /** 同时比较type和具体版本UUID，避免跨类型相同1.0.0文本造成虚假归属一致。 */
    private void assertPointer(JsonNode state, UUID typeId, UUID versionId) {
        assertThat(state.get("device").get("device_type_id").asString()).isEqualTo(typeId.toString());
        if (versionId == null) {
            assertThat(state.get("device").get("thing_model_version_id").isNull()).isTrue();
        } else {
            assertThat(state.get("device").get("thing_model_version_id").asString()).isEqualTo(versionId.toString());
        }
    }

    /** 原生应用连接不占CONTROL池容量，也不借迁移owner绕过RLS。 */
    private Connection appConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD);
        try {
            assertThat(text(connection, "SELECT current_user")).isEqualTo(APP_ROLE);
            return connection;
        } catch (SQLException | RuntimeException | Error failure) {
            connection.close();
            throw failure;
        }
    }

    /** 项目范围、锁超时与语句超时均随事务结束消失；READ COMMITTED符合已有拓扑守卫合同。 */
    private Connection scopedConnection(UUID projectId) throws SQLException {
        Connection connection = appConnection();
        try {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            text(connection, "SELECT set_config('app.project_id', ?, true)", projectId.toString());
            text(connection, "SELECT set_config('lock_timeout', '15s', true)");
            text(connection, "SELECT set_config('statement_timeout', '20s', true)");
            return connection;
        } catch (SQLException | RuntimeException | Error failure) {
            connection.close();
            throw failure;
        }
    }

    /** 直接写入仍使用参数绑定，SQL NULL/UUID由驱动处理，语句资源立即关闭。 */
    private int execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            parameters(statement, arguments);
            return statement.executeUpdate();
        }
    }

    /** 单值文本读取保留SQL NULL，查询必须返回真实一行，不能把空结果当作隔离成功。 */
    private String text(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            parameters(query, arguments);
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    /** PID和计数必须为非空整数，避免JDBC对NULL返回0带来误判。 */
    private int integer(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            parameters(query, arguments);
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                int result = row.getInt(1);
                assertThat(row.wasNull()).isFalse();
                return result;
            }
        }
    }

    /** 所有运行时值通过PreparedStatement绑定，不拼接身份或用户输入。 */
    private void parameters(PreparedStatement statement, Object[] arguments) throws SQLException {
        for (int index = 0; index < arguments.length; index++) {
            statement.setObject(index + 1, arguments[index]);
        }
    }

    /** 一个应用事务内的SQL工作单元，可表达先清指针再换型等多语句攻击路径。 */
    @FunctionalInterface
    private interface SqlAction {
        /** @param connection 已设置项目范围及有界等待的独立APP_ROLE连接 */
        void run(Connection connection) throws SQLException;
    }

    /**
     * 每例独占数据库身份，版本ID均来自真实发布服务。
     * @param tenantId 平台归属租户
     * @param projectId RLS隔离项目
     * @param typeA 原始DIRECT类型
     * @param typeB 另一个DIRECT类型
     * @param deviceId 唯一设备
     * @param versionA 原类型1.0.0
     * @param versionA2 原类型1.0.1
     * @param versionB 另一类型1.0.0
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID typeA, UUID typeB, UUID deviceId,
                           UUID versionA, UUID versionA2, UUID versionB) { }
}
