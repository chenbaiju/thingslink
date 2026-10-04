package com.things.link.bootstrap.device.topology;

import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * D-113真实数据库验收：ADR0033要求gateway_id投影与有效拓扑始终一致，同名TEMP表不能改变约束的事实来源。
 * 拒绝场景均使用全新DriverManager应用物理连接首次调用投影函数，避免其他测试预热的PL/pgSQL计划掩盖问题。
 * 保留0340延迟约束与0200角色守卫，不停用触发器、不调整应用角色，拒绝必须发生在COMMIT且无部分写入。
 */
@DisplayName("D-113 旧拓扑投影约束与临时schema隔离")
class DeviceTopologyProjectionSecurityTests extends AbstractIntegrationTest {

    /** 投影拒绝采用专属结构化约束名，不能用相邻角色守卫的23514冒充本边界已经保护。 */
    private static final String PROJECTION_CONSTRAINT = "dev_topo_projection_guard";
    /** 此片只加固原有三项投影函数，目录与执行权限检查不得扩大到其他领域函数。 */
    private static final String[] PROJECTION_FUNCTIONS = {
            "public.dev_topo_check_projection(uuid)",
            "public.dev_topo_validate_projection()",
            "public.dev_device_validate_projection()"
    };

    /** 设备表遮蔽和权威表遮蔽是两条不同漏检路径，NONE提供原始约束正常生效的对照。 */
    private enum TemporaryTable {
        /** 使用真实public事实的无临时表对照。 */ NONE,
        /** 空设备表会让旧IF EXISTS跳过真实设备。 */ DEVICE,
        /** 空拓扑表会让旧查询误认为真实设备没有有效网关。 */ TOPOLOGY
    }

    /** 无TEMP对照证明合法关系确实受0340延迟投影约束保护，UPDATE成功不能代表事务获准提交。 */
    @Test
    void clearingActiveProjectionWithoutTemporaryTableIsRejectedAtCommit() throws Exception {
        assertBrokenProjectionRejected(TemporaryTable.NONE);
    }

    /** 新APP会话可授权约束角色读取空TEMP表，但这不应让真实设备gateway_id清空后成功提交。 */
    @Test
    void temporaryDeviceTableCannotHideBrokenPublicProjectionAtCommit() throws Exception {
        assertBrokenProjectionRejected(TemporaryTable.DEVICE);
    }

    /** 空TEMP权威表不能把真实active关系解释成无绑定，从而放行被清空的投影。 */
    @Test
    void temporaryTopologyTableCannotHideActivePublicBindingAtCommit() throws Exception {
        assertBrokenProjectionRejected(TemporaryTable.TOPOLOGY);
    }

    /** 同一会话保留两张空TEMP表时，真实public解绑及重新绑定双写仍应正常提交并保留历史。 */
    @Test
    void legitimateUnbindAndRebindCommitWithBothTemporaryTablesPresent() throws Exception {
        Fixture fixture = fixture();
        UUID newTopologyId = UUID.randomUUID();
        try (Connection application = scopedConnection(fixture.projectId())) {
            try {
                int sessionPid = integer(application, "SELECT pg_backend_pid()");
                createTemporaryTable(application, TemporaryTable.DEVICE);
                createTemporaryTable(application, TemporaryTable.TOPOLOGY);
                assertThat(execute(application, "UPDATE public.dev_topo SET unbound_at = now() WHERE id = ?", fixture.topologyId()))
                        .isEqualTo(1);
                assertThat(execute(application, "UPDATE public.dev_device SET gateway_id = NULL WHERE id = ?", fixture.subId()))
                        .isEqualTo(1);
                application.commit();

                // COMMIT清除LOCAL项目参数但保留会话TEMP对象，第二笔双写须显式恢复范围且继续使用原物理连接。
                configureScope(application, fixture.projectId());
                assertThat(integer(application, "SELECT pg_backend_pid()")).isEqualTo(sessionPid);
                assertThat(integer(application, "SELECT count(*) FROM pg_temp.dev_device")).isZero();
                assertThat(integer(application, "SELECT count(*) FROM pg_temp.dev_topo")).isZero();
                assertThat(text(application, "SELECT gateway_id::text FROM public.dev_device WHERE id = ?", fixture.subId())).isNull();
                assertThat(integer(application, "SELECT count(*) FROM public.dev_topo WHERE id = ? AND unbound_at IS NOT NULL",
                        fixture.topologyId())).isEqualTo(1);
                assertThat(execute(application, """
                        INSERT INTO public.dev_topo (id, tenant_id, project_id, gateway_device_id, sub_device_id, bind_source)
                        SELECT ?, tenant_id, project_id, gateway_device_id, sub_device_id, 'CONTROL_PLANE'
                          FROM public.dev_topo WHERE id = ?
                        """, newTopologyId, fixture.topologyId())).isEqualTo(1);
                assertThat(execute(application, "UPDATE public.dev_device SET gateway_id = ? WHERE id = ?", fixture.gatewayId(), fixture.subId()))
                        .isEqualTo(1);
                application.commit();
            } finally {
                application.rollback();
            }
        }
        try (Connection verification = scopedConnection(fixture.projectId())) {
            assertThat(integer(verification, "SELECT count(*) FROM public.dev_topo WHERE unbound_at IS NOT NULL")).isEqualTo(1);
            assertThat(integer(verification, "SELECT count(*) FROM public.dev_topo WHERE id = ? AND unbound_at IS NULL", newTopologyId))
                    .isEqualTo(1);
            assertThat(text(verification, "SELECT gateway_id::text FROM public.dev_device WHERE id = ?", fixture.subId()))
                    .isEqualTo(fixture.gatewayId().toString());
        }
    }

    /** 投影函数保持原只读定义者与VOLATILE语义，但APP/PUBLIC失去直接执行权限且不可替换public对象。 */
    @Test
    void projectionFunctionsKeepSafeCatalogPropertiesAndPrivateExecution() throws Exception {
        Fixture fixture = fixture();
        String before = snapshot(fixture);
        try (Connection application = appConnection()) {
            for (String function : PROJECTION_FUNCTIONS) {
                try (PreparedStatement query = application.prepareStatement("""
                        SELECT p.proowner::regrole::text, p.prosecdef, p.provolatile,
                               'search_path=pg_catalog, public, pg_temp' = ANY(p.proconfig)
                          FROM pg_proc p WHERE p.oid = ?::regprocedure
                        """)) {
                    query.setString(1, function);
                    try (ResultSet definition = query.executeQuery()) {
                        assertThat(definition.next()).isTrue();
                        assertThat(definition.getString(1)).isEqualTo("thingslink_constraint");
                        assertThat(definition.getBoolean(2)).isTrue();
                        assertThat(definition.getString(3)).isEqualTo("v");
                        assertThat(definition.getBoolean(4)).as("%s必须显式把pg_temp放在可信路径之后", function).isTrue();
                    }
                }
                assertThat(text(application, "SELECT has_function_privilege(current_user, ?, 'EXECUTE')::text", function)).isEqualTo("false");
                assertThat(integer(application, """
                        SELECT count(*) FROM pg_proc p,
                               LATERAL aclexplode(COALESCE(p.proacl, acldefault('f', p.proowner))) acl
                         WHERE p.oid = ?::regprocedure AND acl.grantee = 0 AND acl.privilege_type = 'EXECUTE'
                        """, function)).as("PUBLIC不得保留%s执行授权", function).isZero();
                Throwable callFailure = catchThrowable(() -> {
                    if (function.endsWith("(uuid)")) {
                        text(application, "SELECT public.dev_topo_check_projection(?)", fixture.subId());
                    } else if (function.equals("public.dev_topo_validate_projection()")) {
                        text(application, "SELECT public.dev_topo_validate_projection()");
                    } else {
                        text(application, "SELECT public.dev_device_validate_projection()");
                    }
                });
                assertSqlState(callFailure, "42501");
            }
            for (String role : new String[]{"thingslink_constraint", "thingslink_topology_guard"}) {
                assertThat(text(application, "SELECT pg_has_role(current_user, ?, 'MEMBER')::text", role)).isEqualTo("false");
            }
            assertThat(text(application, "SELECT has_schema_privilege(current_user, 'public', 'CREATE')::text")).isEqualTo("false");
            application.setAutoCommit(false);
            try {
                assertSqlState(catchThrowable(() -> execute(application,
                        "CREATE TABLE public.dev_projection_forbidden_probe (id uuid)")), "42501");
            } finally {
                // 即使权限回归导致CREATE意外成功，测试也回滚自身探针，避免污染后续命名检查。
                application.rollback();
            }
        }
        assertThat(snapshot(fixture)).isEqualTo(before);
    }

    /** 安全定义函数不扩大业务RLS：无范围及跨项目直读/直写依然零行，真实关系与投影不变。 */
    @Test
    void projectionHardeningDoesNotExposeRowsWithoutMatchingProjectScope() throws Exception {
        Fixture fixture = fixture();
        Fixture other = fixture();
        String before = snapshot(fixture);
        String otherBefore = snapshot(other);
        try (Connection application = appConnection()) {
            assertThat(integer(application, "SELECT count(*) FROM public.dev_device WHERE id = ?", fixture.subId())).isZero();
            assertThat(integer(application, "SELECT count(*) FROM public.dev_topo WHERE id = ?", fixture.topologyId())).isZero();
            assertThat(execute(application, "UPDATE public.dev_device SET gateway_id = NULL WHERE id = ?", fixture.subId())).isZero();
        }
        try (Connection application = scopedConnection(other.projectId())) {
            try {
                assertThat(integer(application, "SELECT count(*) FROM public.dev_device WHERE id = ?", fixture.subId())).isZero();
                assertThat(integer(application, "SELECT count(*) FROM public.dev_topo WHERE id = ?", fixture.topologyId())).isZero();
                assertThat(execute(application, "UPDATE public.dev_device SET gateway_id = NULL WHERE id = ?", fixture.subId())).isZero();
                assertThat(execute(application, "UPDATE public.dev_topo SET unbound_at = now() WHERE id = ?", fixture.topologyId())).isZero();
                application.commit();
            } finally {
                application.rollback();
            }
        }
        assertThat(snapshot(fixture)).isEqualTo(before);
        assertThat(snapshot(other)).isEqualTo(otherBefore);
    }

    /**
     * 先证明原始绑定与投影合法，再在新物理连接制造单边变更；只捕获COMMIT错误以验证延迟检查时点。
     * 错误提交时仍读取完整前后快照并并列报告，不用提前异常断言遮住已经持久化的投影破坏证据。
     */
    private void assertBrokenProjectionRejected(TemporaryTable temporaryTable) throws Exception {
        Fixture fixture = fixture();
        String before = snapshot(fixture);
        Throwable commitFailure;
        try (Connection application = scopedConnection(fixture.projectId())) {
            try {
                assertThat(integer(application, "SELECT pg_backend_pid()"))
                        .as("被测约束必须在不同于夹具会话的新应用物理连接中首次执行")
                        .isNotEqualTo(fixture.fixtureBackendPid());
                createTemporaryTable(application, temporaryTable);
                assertThat(execute(application, """
                        UPDATE public.dev_device SET gateway_id = NULL, name = '不得部分提交的名称'
                         WHERE project_id = ? AND id = ?
                        """, fixture.projectId(), fixture.subId()))
                        .as("只改投影的语句应先成功，约束须在提交双写结果时检查")
                        .isEqualTo(1);
                assertThat(text(application, "SELECT gateway_id::text FROM public.dev_device WHERE id = ?", fixture.subId())).isNull();
                assertThat(integer(application, "SELECT count(*) FROM public.dev_topo WHERE id = ? AND unbound_at IS NULL",
                        fixture.topologyId())).isEqualTo(1);
                commitFailure = catchThrowable(application::commit);
            } finally {
                application.rollback();
            }
        }
        String after = snapshot(fixture);
        String sqlState = commitFailure instanceof SQLException postgres ? postgres.getSQLState() : null;
        String constraint = commitFailure instanceof PSQLException postgres && postgres.getServerErrorMessage() != null
                ? postgres.getServerErrorMessage().getConstraint() : null;
        assertSoftly(softly -> {
            softly.assertThat(commitFailure).as("破坏真实gateway_id投影的事务必须被提交约束拒绝").isInstanceOf(SQLException.class);
            softly.assertThat(sqlState).as("必须是投影一致性23514，权限或语法错误不能冒充安全拒绝").isEqualTo("23514");
            softly.assertThat(constraint).as("必须由本次投影边界拒绝").isEqualTo(PROJECTION_CONSTRAINT);
            softly.assertThat(after).as("提交拒绝后设备整行与权威拓扑都必须保持原样").isEqualTo(before);
        });
    }

    /** TEMP对象与读授权仅存在于本APP会话，不修改public权限；表名只有预定义两个选择。 */
    private void createTemporaryTable(Connection application, TemporaryTable temporaryTable) throws SQLException {
        if (temporaryTable == TemporaryTable.NONE) {
            return;
        }
        String table;
        if (temporaryTable == TemporaryTable.DEVICE) {
            table = "dev_device";
            execute(application, "CREATE TEMP TABLE dev_device (id uuid, deleted_at timestamptz, gateway_id uuid)");
        } else {
            table = "dev_topo";
            execute(application, "CREATE TEMP TABLE dev_topo (sub_device_id uuid, gateway_device_id uuid, unbound_at timestamptz)");
        }
        execute(application, "GRANT SELECT ON TABLE pg_temp." + table + " TO thingslink_constraint");
        assertThat(integer(application, "SELECT count(*) FROM pg_temp." + table)).isZero();
        assertThat(text(application, "SELECT has_table_privilege('thingslink_constraint', ?, 'SELECT')::text", "pg_temp." + table))
                .isEqualTo("true");
    }

    /** 直接执行只认可真实SQLSTATE权限拒绝，函数不能执行的其他错误并不证明ACL收口。 */
    private void assertSqlState(Throwable failure, String expected) {
        assertThat(failure).isInstanceOf(SQLException.class);
        assertThat(((SQLException) failure).getSQLState()).isEqualTo(expected);
    }

    /**
     * 每场独占随机租户/项目；owner仅准备平台身份，类型、设备、权威关系和投影均以APP_ROLE合法双写。
     * 夹具连接提交后立即关闭，攻击连接不会复用其已解析过public表的函数计划；测试容器最终回收全部夹具。
     */
    private Fixture fixture() throws SQLException {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID gatewayTypeId = UUID.randomUUID();
        UUID subTypeId = UUID.randomUUID();
        UUID gatewayId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        UUID topologyId = UUID.randomUUID();
        try (Connection owner = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, '投影约束安全验收租户')", tenantId);
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, project_key) VALUES (?, ?, '投影约束安全验收项目', ?)",
                    projectId, tenantId, "projection_" + projectId.toString().replace("-", ""));
        }
        int fixturePid;
        try (Connection connection = scopedConnection(projectId)) {
            try {
                fixturePid = integer(connection, "SELECT pg_backend_pid()");
                execute(connection, """
                        INSERT INTO public.dev_type
                            (id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type)
                        VALUES (?, ?, ?, 'gateway_type', '合法网关类型', 'GATEWAY', 'STANDARD_GATEWAY', 'ETHERNET'),
                               (?, ?, ?, 'sub_type', '合法子设备类型', 'SUB_DEVICE', 'STANDARD', 'ZIGBEE')
                        """, gatewayTypeId, tenantId, projectId, subTypeId, tenantId, projectId);
                execute(connection, """
                        INSERT INTO public.dev_device (id, tenant_id, project_id, device_type_id, device_key, name)
                        VALUES (?, ?, ?, ?, 'gateway', '合法网关'), (?, ?, ?, ?, 'child', '原始子设备名称')
                        """, gatewayId, tenantId, projectId, gatewayTypeId, subId, tenantId, projectId, subTypeId);
                execute(connection, """
                        INSERT INTO public.dev_topo (id, tenant_id, project_id, gateway_device_id, sub_device_id, bind_source)
                        VALUES (?, ?, ?, ?, ?, 'CONTROL_PLANE')
                        """, topologyId, tenantId, projectId, gatewayId, subId);
                assertThat(execute(connection, "UPDATE public.dev_device SET gateway_id = ? WHERE id = ?", gatewayId, subId)).isEqualTo(1);
                connection.commit();
            } finally {
                connection.rollback();
            }
        }
        Fixture fixture = new Fixture(projectId, gatewayId, subId, topologyId, fixturePid);
        try (Connection verification = scopedConnection(projectId)) {
            assertThat(integer(verification, "SELECT count(*) FROM public.dev_device")).isEqualTo(2);
            assertThat(integer(verification, "SELECT count(*) FROM public.dev_topo WHERE id = ? AND unbound_at IS NULL", topologyId)).isEqualTo(1);
            assertThat(text(verification, "SELECT gateway_id::text FROM public.dev_device WHERE id = ?", subId)).isEqualTo(gatewayId.toString());
            assertThat(text(verification, "SELECT gateway_device_id::text FROM public.dev_topo WHERE id = ?", topologyId)).isEqualTo(gatewayId.toString());
        }
        return fixture;
    }

    /** 每次前后快照都另开应用连接并限定项目，包含设备全部字段与权威拓扑的完整行。 */
    private String snapshot(Fixture fixture) throws SQLException {
        try (Connection connection = scopedConnection(fixture.projectId())) {
            return text(connection, """
                    SELECT jsonb_build_object(
                        'devices', (SELECT jsonb_agg(to_jsonb(d) ORDER BY d.id) FROM public.dev_device d WHERE project_id = ?),
                        'topology', (SELECT jsonb_agg(to_jsonb(t) ORDER BY t.id) FROM public.dev_topo t WHERE project_id = ?))::text
                    """, fixture.projectId(), fixture.projectId());
        }
    }

    /** 新建真实应用物理连接，显式RC和项目范围保持0200角色守卫前置，LOCAL超时随事务结束清除。 */
    private Connection scopedConnection(UUID projectId) throws SQLException {
        Connection connection = appConnection();
        try {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            configureScope(connection, projectId);
            return connection;
        } catch (SQLException | RuntimeException | Error failure) {
            connection.close();
            throw failure;
        }
    }

    /** DriverManager不复用连接池后端，新会话没有其他用例已经解析的投影函数缓存。 */
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

    /** 每次COMMIT后恢复LOCAL范围与有界等待，不能假设会话TEMP对象和事务参数生命周期相同。 */
    private void configureScope(Connection connection, UUID projectId) throws SQLException {
        assertThat(text(connection, "SELECT set_config('app.project_id', ?, true)", projectId.toString())).isEqualTo(projectId.toString());
        text(connection, "SELECT set_config('lock_timeout', '10s', true)");
        text(connection, "SELECT set_config('statement_timeout', '15s', true)");
    }

    /** 可变UUID全部参数绑定，固定SQL执行后关闭语句资源。 */
    private int execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            parameters(statement, arguments);
            return statement.executeUpdate();
        }
    }

    /** 保留真实SQL NULL，用于区分被清空的投影与不存在的查询行。 */
    private String text(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            parameters(statement, arguments);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    /** PID与计数必须来自非空结果，不能把JDBC对NULL返回的零当作隔离或连接证据。 */
    private int integer(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            parameters(statement, arguments);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                int value = row.getInt(1);
                assertThat(row.wasNull()).isFalse();
                return value;
            }
        }
    }

    /** 参数绑定不改变数据库UUID/NULL类型，也不拼接项目或设备身份。 */
    private void parameters(PreparedStatement statement, Object[] arguments) throws SQLException {
        for (int index = 0; index < arguments.length; index++) {
            statement.setObject(index + 1, arguments[index]);
        }
    }

    /**
     * 已正常提交的有效拓扑身份以及夹具物理连接PID。
     * @param projectId 随机独占RLS项目
     * @param gatewayId 真实GATEWAY设备
     * @param subId 真实SUB_DEVICE设备
     * @param topologyId 已提交有效权威关系
     * @param fixtureBackendPid 仅用于证明被测会话未复用夹具物理连接
     */
    private record Fixture(UUID projectId, UUID gatewayId, UUID subId, UUID topologyId, int fixtureBackendPid) { }
}
