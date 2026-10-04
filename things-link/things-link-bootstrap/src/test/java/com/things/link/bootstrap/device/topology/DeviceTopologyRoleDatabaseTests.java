package com.things.link.bootstrap.device.topology;

import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.util.PSQLException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * ADR0057 的库内角色守卫：真实 APP_ROLE 直接 SQL 也不能提交类型漂移或并发写偏差。
 * 每例使用随机独占项目，始终开启0340延迟投影约束；测试不能靠故意写错投影来冒充角色拒绝。
 */
@DisplayName("数据库拓扑角色守卫：直接写入和真实并发")
class DeviceTopologyRoleDatabaseTests extends AbstractIntegrationTest {

    /** 类型非键变更、设备换型分别拥有不同锁边界，两个方向都必须验证。 */
    private enum MutationTarget {
        /** 已被设备引用的草稿类型改变分类。 */ TYPE,
        /** 子设备改用另一个已存在的 DIRECT 类型。 */ DEVICE
    }

    /** 当前有效两端的类型分类及软删除不能被直接 SQL 绕过；失败后全部事实不变。 */
    @Test
    void directTypeKindChangesAndTypeSoftDeletionAreRejected() throws Exception {
        Fixture fixture = fixture(true);
        assertRejectedUnchanged(fixture, "23514", connection -> execute(connection, """
                UPDATE dev_type SET device_kind = 'DIRECT', access_protocol = 'STANDARD' WHERE id = ?
                """, fixture.subTypeId()));
        assertRejectedUnchanged(fixture, "23514", connection -> execute(connection, """
                UPDATE dev_type SET device_kind = 'SUB_DEVICE', access_protocol = 'STANDARD' WHERE id = ?
                """, fixture.gatewayTypeId()));
        for (UUID typeId : new UUID[]{fixture.gatewayTypeId(), fixture.subTypeId()}) {
            assertRejectedUnchanged(fixture, "23514", connection ->
                    execute(connection, "UPDATE dev_type SET deleted_at = now() WHERE id = ?", typeId));
        }
    }

    /** 改设备类型、清类型和软删任一端都会使仍有效的关系非法；设备外键仍存在不足以放行。 */
    @Test
    void directDeviceTypeChangesAndEndpointSoftDeletionAreRejected() throws Exception {
        Fixture fixture = fixture(true);
        assertRejectedUnchanged(fixture, "23514", connection -> execute(connection,
                "UPDATE dev_device SET device_type_id = ? WHERE id = ?", fixture.directTypeId(), fixture.subId()));
        assertRejectedUnchanged(fixture, "23514", connection -> execute(connection,
                "UPDATE dev_device SET device_type_id = NULL WHERE id = ?", fixture.subId()));
        assertRejectedUnchanged(fixture, "23514", connection -> execute(connection,
                "UPDATE dev_device SET device_type_id = ? WHERE id = ?", fixture.subTypeId(), fixture.gatewayId()));
        for (UUID deviceId : new UUID[]{fixture.gatewayId(), fixture.subId()}) {
            assertRejectedUnchanged(fixture, "23514", connection ->
                    execute(connection, "UPDATE dev_device SET deleted_at = now() WHERE id = ?", deviceId));
        }
    }

    /** ADR0060先拒绝身份UPDATE，因此用投影已配对的非法INSERT独立验证两端角色，不能把身份守卫归因给D111。 */
    @Test
    void insertingEitherTopologyEndpointWithDirectDeviceIsRejectedByRoleGuard() throws Exception {
        Fixture fixture = fixture(false);
        assertRoleRejectedUnchanged(fixture, connection -> {
            execute(connection, "UPDATE dev_device SET gateway_id = ? WHERE id = ?", fixture.directId(), fixture.subId());
            execute(connection, """
                    INSERT INTO dev_topo (id, tenant_id, project_id, gateway_device_id, sub_device_id, bind_source)
                    VALUES (?, ?, ?, ?, ?, 'CONTROL_PLANE')
                    """, fixture.topologyId(), fixture.tenantId(), fixture.projectId(), fixture.directId(), fixture.subId());
        });
        assertRoleRejectedUnchanged(fixture, connection -> {
            execute(connection, "UPDATE dev_device SET gateway_id = ? WHERE id = ?", fixture.gatewayId(), fixture.directId());
            execute(connection, """
                    INSERT INTO dev_topo (id, tenant_id, project_id, gateway_device_id, sub_device_id, bind_source)
                    VALUES (?, ?, ?, ?, ?, 'CONTROL_PLANE')
                    """, fixture.topologyId(), fixture.tenantId(), fixture.projectId(), fixture.gatewayId(), fixture.directId());
        });
    }

    /** 明确关闭关系后可以改变分类，但不能再把那条历史复活成角色错误的有效关系。 */
    @Test
    void historicalRelationshipCannotBeReactivatedAfterItsRoleChanged() throws Exception {
        Fixture fixture = fixture(true);
        try (Connection connection = scopedConnection(fixture.projectId())) {
            closeBinding(connection, fixture);
            execute(connection, "UPDATE dev_device SET device_type_id = ? WHERE id = ?",
                    fixture.directTypeId(), fixture.subId());
            connection.commit();
        }
        assertRejectedUnchanged(fixture, "23514", connection -> {
            execute(connection, "UPDATE dev_topo SET unbound_at = NULL, unbound_by = NULL WHERE id = ?", fixture.topologyId());
            execute(connection, "UPDATE dev_device SET gateway_id = ? WHERE id = ?", fixture.gatewayId(), fixture.subId());
        });
    }

    /** 合法分类内的元信息修改、明确解绑后的改型/删除可以提交，角色守卫不能把历史误当有效关系。 */
    @Test
    void compatibleMetadataAndExplicitlyClosedHistoryRemainWritable() throws Exception {
        Fixture fixture = fixture(true);
        try (Connection connection = scopedConnection(fixture.projectId())) {
            execute(connection, "UPDATE dev_type SET name = '合法名称' WHERE id = ?", fixture.subTypeId());
            execute(connection, "UPDATE dev_device SET name = '合法设备名称' WHERE id = ?", fixture.subId());
            connection.commit();
        }
        try (Connection connection = scopedConnection(fixture.projectId())) {
            assertThat(textValue(connection, "SELECT name FROM dev_type WHERE id = ?", fixture.subTypeId())).isEqualTo("合法名称");
            closeBinding(connection, fixture);
            execute(connection, "UPDATE dev_type SET device_kind = 'DIRECT', access_protocol = 'STANDARD' WHERE id = ?",
                    fixture.subTypeId());
            execute(connection, "UPDATE dev_type SET deleted_at = now() WHERE id = ?", fixture.gatewayTypeId());
            execute(connection, "UPDATE dev_device SET deleted_at = now() WHERE id = ?", fixture.gatewayId());
            connection.commit();
        }
        try (Connection connection = scopedConnection(fixture.projectId())) {
            assertThat(intValue(connection, "SELECT count(*) FROM dev_topo WHERE id = ? AND unbound_at IS NOT NULL",
                    fixture.topologyId())).isEqualTo(1);
            assertThat(textValue(connection, "SELECT gateway_id::text FROM dev_device WHERE id = ?", fixture.subId())).isNull();
            assertThat(textValue(connection, "SELECT device_kind FROM dev_type WHERE id = ?", fixture.subTypeId())).isEqualTo("DIRECT");
        }
    }

    /** 库内特权检查函数不能扩大应用查询权限，无范围与另一项目范围仍看不到目标数据。 */
    @Test
    void roleGuardsDoNotOpenApplicationRls() throws Exception {
        Fixture fixture = fixture(true);
        Fixture other = fixture(false);
        String before = snapshot(fixture.projectId());
        try (Connection connection = appConnection()) {
            assertThat(intValue(connection, "SELECT count(*) FROM dev_topo WHERE id = ?", fixture.topologyId())).isZero();
            assertThat(intValue(connection, "SELECT count(*) FROM dev_device WHERE id = ?", fixture.subId())).isZero();
            assertThat(intValue(connection, "SELECT count(*) FROM dev_type WHERE id = ?", fixture.subTypeId())).isZero();
        }
        try (Connection connection = scopedConnection(other.projectId())) {
            assertThat(intValue(connection, "SELECT count(*) FROM dev_topo WHERE id = ?", fixture.topologyId())).isZero();
            assertThat(execute(connection, "UPDATE dev_type SET name = '越权' WHERE id = ?", fixture.subTypeId())).isZero();
            assertThat(execute(connection, "UPDATE dev_device SET device_type_id = NULL WHERE id = ?", fixture.subId())).isZero();
            connection.commit();
        }
        assertThat(snapshot(fixture.projectId())).isEqualTo(before);
    }

    /** 新关系引用另一真实租户虽满足外键，仍须由D111拒绝与真实两端归属不一致，避免新版身份UPDATE守卫抢先拒绝。 */
    @Test
    void insertingTopologyWithAnotherExistingTenantIsRejectedByRoleGuard() throws Exception {
        Fixture fixture = fixture(false);
        Fixture other = fixture(false);
        assertThat(other.tenantId()).isNotEqualTo(fixture.tenantId());
        assertRoleRejectedUnchanged(fixture, connection -> {
            execute(connection, "UPDATE dev_device SET gateway_id = ? WHERE id = ?", fixture.gatewayId(), fixture.subId());
            execute(connection, """
                    INSERT INTO dev_topo (id, tenant_id, project_id, gateway_device_id, sub_device_id, bind_source)
                    VALUES (?, ?, ?, ?, ?, 'CONTROL_PLANE')
                    """, fixture.topologyId(), other.tenantId(), fixture.projectId(), fixture.gatewayId(), fixture.subId());
        });
    }

    /** 独立守卫角色只有锁读取所需的最小权限，应用既不能切换成该角色，也不能直接调用安全定义函数。 */
    @Test
    void guardRoleAndFunctionsExposeOnlyTheirRequiredLockReadPrivileges() throws Exception {
        Fixture fixture = fixture(true);
        try (Connection application = appConnection()) {
            try (PreparedStatement query = application.prepareStatement("""
                    SELECT rolcanlogin, rolinherit, rolbypassrls, rolsuper
                      FROM pg_roles WHERE rolname = 'thingslink_topology_guard'
                    """); ResultSet role = query.executeQuery()) {
                assertThat(role.next()).isTrue();
                assertThat(role.getBoolean("rolcanlogin")).isFalse();
                assertThat(role.getBoolean("rolinherit")).isFalse();
                assertThat(role.getBoolean("rolbypassrls")).isTrue();
                assertThat(role.getBoolean("rolsuper")).isFalse();
            }
            assertThat(textValue(application, """
                    SELECT pg_has_role(current_user, 'thingslink_topology_guard', 'MEMBER')::text
                    """)).isEqualTo("false");
            assertSqlState("42501", () -> execute(application, "SET ROLE thingslink_topology_guard"));
            assertThat(textValue(application, """
                    SELECT string_agg(c.relname, ',' ORDER BY c.relname)
                      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                     WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
                       AND has_table_privilege('thingslink_topology_guard', c.oid, 'SELECT')
                       AND NOT (c.relname = 'spatial_ref_sys' AND EXISTS (
                           SELECT 1 FROM pg_depend d JOIN pg_extension e ON e.oid = d.refobjid
                            WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid
                              AND d.refclassid = 'pg_extension'::regclass AND d.deptype = 'e'
                              AND e.extname = 'postgis'))
                    """)).isEqualTo("dev_device,dev_topo,dev_type");
            // PostGIS标准坐标目录由PUBLIC提供只读访问，不是新增业务表授权；必须核实来源和只读边界。
            assertThat(intValue(application, """
                    SELECT count(*) FROM pg_class c,
                         LATERAL aclexplode(COALESCE(c.relacl, acldefault('r', c.relowner))) a
                     WHERE c.oid = 'public.spatial_ref_sys'::regclass
                       AND a.grantee = 0 AND a.privilege_type = 'SELECT' AND NOT a.is_grantable
                    """)).isEqualTo(1);
            for (String privilege : new String[]{"INSERT", "DELETE", "TRUNCATE", "UPDATE", "REFERENCES", "TRIGGER"}) {
                assertThat(textValue(application,
                        "SELECT has_table_privilege('thingslink_topology_guard', 'public.spatial_ref_sys', ?)::text",
                        privilege)).as("PostGIS目录不得向守卫开放%s", privilege).isEqualTo("false");
            }
            assertThat(textValue(application, """
                    SELECT has_any_column_privilege('thingslink_topology_guard',
                        'public.spatial_ref_sys', 'UPDATE')::text
                    """)).isEqualTo("false");
            for (String table : new String[]{"public.dev_type", "public.dev_device", "public.dev_topo"}) {
                for (String privilege : new String[]{"INSERT", "DELETE", "TRUNCATE", "UPDATE"}) {
                    assertThat(textValue(application, "SELECT has_table_privilege('thingslink_topology_guard', ?, ?)::text",
                            table, privilege)).as("%s 不得有表级 %s", table, privilege).isEqualTo("false");
                }
                assertThat(textValue(application,
                        "SELECT has_any_column_privilege('thingslink_constraint', ?, 'UPDATE')::text", table))
                        .as("原投影约束角色不能因新守卫取得写权限").isEqualTo("false");
                if (table.equals("public.dev_topo")) {
                    assertThat(textValue(application,
                            "SELECT has_any_column_privilege('thingslink_topology_guard', ?, 'UPDATE')::text", table))
                            .isEqualTo("false");
                } else {
                    assertThat(textValue(application,
                            "SELECT has_column_privilege('thingslink_topology_guard', ?, 'id', 'UPDATE')::text", table))
                            .isEqualTo("true");
                    assertThat(intValue(application, """
                            SELECT count(*) FROM pg_attribute
                             WHERE attrelid = ?::regclass AND attnum > 0 AND NOT attisdropped AND attname <> 'id'
                               AND has_column_privilege('thingslink_topology_guard', attrelid, attnum, 'UPDATE')
                            """, table)).as("仅id列可供锁读取授权").isZero();
                }
            }
            for (String function : new String[]{"dev_topo_assert_role_isolation()", "dev_topo_check_roles(uuid)",
                    "dev_topo_validate_roles()", "dev_device_validate_topology_roles()", "dev_type_validate_topology_roles()"}) {
                assertThat(textValue(application,
                        "SELECT has_function_privilege(current_user, ?, 'EXECUTE')::text", "public." + function))
                        .as("应用不可直接调用%s", function).isEqualTo("false");
                if (function.equals("dev_topo_check_roles(uuid)")) {
                    assertSqlState("42501", () -> textValue(application,
                            "SELECT public.dev_topo_check_roles(?)", fixture.topologyId()));
                } else {
                    assertSqlState("42501", () -> textValue(application, "SELECT public." + function));
                }
            }
        }
    }

    /** 应用可建同名临时表，但不能借旧快照遮蔽真实public表，让安全定义守卫把非法分类看成合法。 */
    @Test
    void temporarySchemaCannotShadowPublicRoleFacts() throws Exception {
        Fixture fixture = fixture(true);
        assertRejectedUnchanged(fixture, "23514", connection -> {
            // 不伪造超级用户或修改触发器；这些 TEMP 表及搜索路径都是普通应用连接本来具备的能力。
            for (String table : new String[]{"dev_type", "dev_device", "dev_topo"}) {
                execute(connection, "CREATE TEMP TABLE " + table + " AS SELECT * FROM public." + table + " WHERE project_id = ?",
                        fixture.projectId());
            }
            textValue(connection, "SELECT set_config('search_path', 'pg_temp, public', true)");
            assertThat(textValue(connection, "SELECT device_kind FROM pg_temp.dev_type WHERE id = ?", fixture.subTypeId()))
                    .isEqualTo("SUB_DEVICE");
            execute(connection, """
                    UPDATE public.dev_type SET device_kind = 'DIRECT', access_protocol = 'STANDARD'
                     WHERE project_id = ? AND id = ?
                    """, fixture.projectId(), fixture.subTypeId());
        });
    }

    /** 非键分类变更或设备换型先写时，新绑定不能依据旧快照提交；55P03须完整回滚，提交后重试仍按新角色拒绝。 */
    @ParameterizedTest
    @EnumSource(MutationTarget.class)
    void mutationFirstRejectsConcurrentBindingAndRevalidatesCommittedRole(MutationTarget target) throws Exception {
        Fixture fixture = fixture(false);
        try (var requests = Executors.newVirtualThreadPerTaskExecutor();
             Connection mutation = scopedConnection(fixture.projectId())) {
            try {
                mutateRole(mutation, fixture, target);
                requests.submit(() -> {
                    assertRejectedUnchanged(fixture, "55P03", connection -> insertBinding(connection, fixture));
                    return null;
                }).get(10, TimeUnit.SECONDS);
                mutation.commit();
            } finally {
                mutation.rollback();
            }
        }
        assertRejectedUnchanged(fixture, "23514", connection -> insertBinding(connection, fixture));
        try (Connection connection = scopedConnection(fixture.projectId())) {
            assertThat(intValue(connection, "SELECT count(*) FROM dev_topo WHERE sub_device_id = ?", fixture.subId())).isZero();
            assertThat(textValue(connection, "SELECT gateway_id::text FROM dev_device WHERE id = ?", fixture.subId())).isNull();
        }
    }

    /** 绑定先完成其受保护写入时，另一事务分类修改真实等待，提交后必须基于新关系拒绝而非产生写偏差。 */
    @ParameterizedTest
    @EnumSource(MutationTarget.class)
    void bindingFirstBlocksMutationAndRejectsItAfterBindingCommit(MutationTarget target) throws Exception {
        Fixture fixture = fixture(false);
        try (var requests = Executors.newVirtualThreadPerTaskExecutor();
             Connection binding = scopedConnection(fixture.projectId());
             Connection mutation = scopedConnection(fixture.projectId())) {
            try {
                insertBinding(binding, fixture);
                int bindingPid = intValue(binding, "SELECT pg_backend_pid()");
                int mutationPid = intValue(mutation, "SELECT pg_backend_pid()");
                var attempt = requests.submit(() -> {
                    assertSqlState("23514", () -> {
                        mutateRole(mutation, fixture, target);
                        mutation.commit();
                    });
                    mutation.rollback();
                    return null;
                });
                try {
                    assertBlocking(mutationPid, bindingPid);
                } finally {
                    binding.commit();
                }
                attempt.get(10, TimeUnit.SECONDS);
            } finally {
                binding.rollback();
                mutation.rollback();
            }
        }
        try (Connection connection = scopedConnection(fixture.projectId())) {
            assertThat(intValue(connection, "SELECT count(*) FROM dev_topo WHERE id = ? AND unbound_at IS NULL",
                    fixture.topologyId())).isEqualTo(1);
            assertThat(textValue(connection, "SELECT device_kind FROM dev_type WHERE id = ?", fixture.subTypeId())).isEqualTo("SUB_DEVICE");
            assertThat(textValue(connection, "SELECT device_type_id::text FROM dev_device WHERE id = ?", fixture.subId()))
                    .isEqualTo(fixture.subTypeId().toString());
        }
    }

    /** RR 固定旧快照看不到另一事务的新绑定，类型写须明确拒绝0A000，不能在过时视图上放行分类漂移。 */
    @Test
    void repeatableReadStaleSnapshotCannotChangeTypeAfterNewBindingCommitted() throws Exception {
        Fixture fixture = fixture(false);
        try (Connection stale = scopedConnection(fixture.projectId(), Connection.TRANSACTION_REPEATABLE_READ)) {
            try {
                assertThat(textValue(stale, "SHOW transaction_isolation")).isEqualTo("repeatable read");
                assertThat(intValue(stale, "SELECT count(*) FROM dev_topo WHERE sub_device_id = ?", fixture.subId())).isZero();
                try (Connection writer = scopedConnection(fixture.projectId())) {
                    insertBinding(writer, fixture);
                    writer.commit();
                }
                // 此断言确认旧事务确实固定了没有绑定的快照，而非只设置了一个未生效的 isolation 参数。
                assertThat(intValue(stale, "SELECT count(*) FROM dev_topo WHERE sub_device_id = ?", fixture.subId())).isZero();
                String committed = snapshot(fixture.projectId());
                assertSqlState("0A000", () -> {
                    mutateRole(stale, fixture, MutationTarget.TYPE);
                    stale.commit();
                });
                stale.rollback();
                assertThat(snapshot(fixture.projectId())).isEqualTo(committed);
            } finally {
                stale.rollback();
            }
        }
    }

    /** 对方持网关KEY SHARE时，本事务的状态NO KEY UPDATE及自身SHARE检查兼容，合法绑定应提交。 */
    @Test
    void compatibleGatewayKeyShareAllowsStateWriteAndBinding() throws Exception {
        Fixture fixture = fixture(false);
        try (var requests = Executors.newVirtualThreadPerTaskExecutor();
             Connection reader = scopedConnection(fixture.projectId())) {
            try {
                assertThat(textValue(reader, "SELECT id::text FROM dev_device WHERE id = ? FOR KEY SHARE", fixture.gatewayId()))
                        .isEqualTo(fixture.gatewayId().toString());
                requests.submit(() -> {
                    try (Connection writer = scopedConnection(fixture.projectId())) {
                        try {
                            execute(writer, "UPDATE dev_device SET status = 'ONLINE', last_online_at = now() WHERE id = ?",
                                    fixture.gatewayId());
                            insertBinding(writer, fixture);
                            writer.commit();
                        } finally {
                            writer.rollback();
                        }
                    }
                    return null;
                }).get(10, TimeUnit.SECONDS);
            } finally {
                reader.rollback();
            }
        }
        try (Connection connection = scopedConnection(fixture.projectId())) {
            assertThat(textValue(connection, "SELECT status FROM dev_device WHERE id = ?", fixture.gatewayId())).isEqualTo("ONLINE");
            assertThat(textValue(connection, "SELECT last_online_at::text FROM dev_device WHERE id = ?", fixture.gatewayId())).isNotNull();
            assertThat(intValue(connection, "SELECT count(*) FROM dev_topo WHERE id = ? AND unbound_at IS NULL",
                    fixture.topologyId())).isEqualTo(1);
            assertThat(textValue(connection, "SELECT gateway_id::text FROM dev_device WHERE id = ?", fixture.subId()))
                    .isEqualTo(fixture.gatewayId().toString());
        }
    }

    /**
     * 纯SQL反向锁形状：绑定方持网关KS和子设备FU，对方持网关NOKU并已等待子设备，绑定方SHARE必须NOWAIT拒绝。
     * 以实际pg_blocking_pids建立前提；这验证库内锁合同，不冒充完整EMQX调用链。
     */
    @Test
    void reverseDeviceLockOrderRejectsGatewayShareCheckWithoutWaiting() throws Exception {
        Fixture fixture = fixture(false);
        String before = snapshot(fixture.projectId());
        CompletableFuture<Integer> waitingPid = new CompletableFuture<>();
        try (var requests = Executors.newVirtualThreadPerTaskExecutor();
             Connection binder = scopedConnection(fixture.projectId())) {
            assertThat(textValue(binder, "SELECT id::text FROM dev_device WHERE id = ? FOR KEY SHARE", fixture.gatewayId()))
                    .isEqualTo(fixture.gatewayId().toString());
            assertThat(textValue(binder, "SELECT id::text FROM dev_device WHERE id = ? FOR UPDATE", fixture.subId()))
                    .isEqualTo(fixture.subId().toString());
            int binderPid = intValue(binder, "SELECT pg_backend_pid()");
            var waitingForChild = requests.submit(() -> {
                // 同一连接先取得网关非键写锁，再真实等待子设备；其状态写最终必须回滚。
                try (Connection other = scopedConnection(fixture.projectId())) {
                    try {
                        execute(other, "UPDATE dev_device SET status = 'ONLINE', last_online_at = now() WHERE id = ?",
                                fixture.gatewayId());
                        waitingPid.complete(intValue(other, "SELECT pg_backend_pid()"));
                        assertThat(textValue(other, "SELECT id::text FROM dev_device WHERE id = ? FOR UPDATE", fixture.subId()))
                                .isEqualTo(fixture.subId().toString());
                    } finally {
                        other.rollback();
                    }
                }
                return null;
            });
            try {
                assertBlocking(waitingPid.get(10, TimeUnit.SECONDS), binderPid);
                assertSqlState("55P03", () -> insertBinding(binder, fixture));
            } finally {
                // 必须先释放child，另一线程才能结束；不能先关闭executor等待它而把锁留在本线程。
                binder.rollback();
            }
            waitingForChild.get(10, TimeUnit.SECONDS);
        }
        assertThat(snapshot(fixture.projectId())).isEqualTo(before);
        try (Connection retry = scopedConnection(fixture.projectId())) {
            insertBinding(retry, fixture);
            retry.commit();
        }
        try (Connection connection = scopedConnection(fixture.projectId())) {
            assertThat(intValue(connection, "SELECT count(*) FROM dev_topo WHERE id = ? AND unbound_at IS NULL",
                    fixture.topologyId())).isEqualTo(1);
            assertThat(textValue(connection, "SELECT gateway_id::text FROM dev_device WHERE id = ?", fixture.subId()))
                    .isEqualTo(fixture.gatewayId().toString());
        }
    }

    /**
     * 每例独立租户/项目，平台身份由迁移 owner 准备；实际类型、设备与关系使用 APP_ROLE 和原有约束创建。
     * 不删除其他用例数据，整个 JVM 结束时由 Testcontainers 回收。
     */
    private Fixture fixture(boolean bound) throws SQLException {
        Fixture fixture = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        try (Connection owner = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, '拓扑角色数据库测试')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, project_key) VALUES (?, ?, '拓扑角色数据库项目', ?)",
                    fixture.projectId(), fixture.tenantId(), "dbrole_" + fixture.projectId().toString().replace("-", ""));
        }
        try (Connection connection = scopedConnection(fixture.projectId())) {
            insertType(connection, fixture, fixture.gatewayTypeId(), "gateway_type", "GATEWAY", "STANDARD_GATEWAY", "ETHERNET");
            insertType(connection, fixture, fixture.subTypeId(), "sub_type", "SUB_DEVICE", "STANDARD", "ZIGBEE");
            insertType(connection, fixture, fixture.directTypeId(), "direct_type", "DIRECT", "STANDARD", "WIFI");
            insertDevice(connection, fixture, fixture.gatewayId(), fixture.gatewayTypeId(), "gateway");
            insertDevice(connection, fixture, fixture.subId(), fixture.subTypeId(), "sub_device");
            insertDevice(connection, fixture, fixture.directId(), fixture.directTypeId(), "direct_device");
            if (bound) {
                insertBinding(connection, fixture);
            }
            connection.commit();
        }
        try (Connection connection = scopedConnection(fixture.projectId())) {
            assertThat(intValue(connection, "SELECT count(*) FROM dev_device")).isEqualTo(3);
            assertThat(intValue(connection, "SELECT count(*) FROM dev_type")).isEqualTo(3);
            assertThat(intValue(connection, "SELECT count(*) FROM dev_topo WHERE unbound_at IS NULL")).isEqualTo(bound ? 1 : 0);
            assertThat(textValue(connection, "SELECT gateway_id::text FROM dev_device WHERE id = ?", fixture.subId()))
                    .isEqualTo(bound ? fixture.gatewayId().toString() : null);
        }
        return fixture;
    }

    /** 原生应用角色创建合法草稿类型，协议矩阵不能先于被测角色规则拒绝夹具。 */
    private void insertType(Connection connection, Fixture fixture, UUID id, String key, String kind,
                             String protocol, String network) throws SQLException {
        execute(connection, """
                INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, fixture.tenantId(), fixture.projectId(), key, key, kind, protocol, network);
    }

    /** 设备起始未绑定且从未在线，保证绑定失败后任何投影/状态副作用都能被快照发现。 */
    private void insertDevice(Connection connection, Fixture fixture, UUID id, UUID typeId, String key) throws SQLException {
        execute(connection, """
                INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, fixture.tenantId(), fixture.projectId(), typeId, key, key);
    }

    /** 权威事实和投影在同事务双写，旧投影约束保持开启，角色合法时应能提交。 */
    private void insertBinding(Connection connection, Fixture fixture) throws SQLException {
        execute(connection, """
                INSERT INTO dev_topo (id, tenant_id, project_id, gateway_device_id, sub_device_id, bind_source)
                VALUES (?, ?, ?, ?, ?, 'CONTROL_PLANE')
                """, fixture.topologyId(), fixture.tenantId(), fixture.projectId(), fixture.gatewayId(), fixture.subId());
        execute(connection, "UPDATE dev_device SET gateway_id = ? WHERE id = ?", fixture.gatewayId(), fixture.subId());
    }

    /** 明确解绑先关闭权威关系并清投影，允许后续角色变更而保留原始历史记录。 */
    private void closeBinding(Connection connection, Fixture fixture) throws SQLException {
        execute(connection, "UPDATE dev_topo SET unbound_at = now() WHERE id = ?", fixture.topologyId());
        execute(connection, "UPDATE dev_device SET gateway_id = NULL WHERE id = ?", fixture.subId());
    }

    /** 非键类型编辑与设备外键编辑是两个独立并发来源，均不修改网关投影以避开旧投影约束。 */
    private void mutateRole(Connection connection, Fixture fixture, MutationTarget target) throws SQLException {
        if (target == MutationTarget.TYPE) {
            execute(connection, "UPDATE dev_type SET device_kind = 'DIRECT', access_protocol = 'STANDARD' WHERE id = ?",
                    fixture.subTypeId());
        } else {
            execute(connection, "UPDATE dev_device SET device_type_id = ? WHERE id = ?", fixture.directTypeId(), fixture.subId());
        }
    }

    /** 任一阶段或提交时拒绝都必须回滚整笔事务，不以事务已失败之后的二次报错充当预期错误。 */
    private void assertRejectedUnchanged(Fixture fixture, String sqlState, SqlAction action) throws Exception {
        String before = snapshot(fixture.projectId());
        try (Connection connection = scopedConnection(fixture.projectId())) {
            try {
                assertSqlState(sqlState, () -> {
                    action.run(connection);
                    connection.commit();
                });
            } finally {
                connection.rollback();
            }
        }
        assertThat(snapshot(fixture.projectId())).isEqualTo(before);
    }

    /** 同为23514的身份/投影守卫不能使角色测试假绿，必须核对数据库返回的角色constraint并回滚先写投影。 */
    private void assertRoleRejectedUnchanged(Fixture fixture, SqlAction action) throws Exception {
        String before = snapshot(fixture.projectId());
        try (Connection connection = scopedConnection(fixture.projectId())) {
            try {
                Throwable failure = catchThrowable(() -> {
                    action.run(connection);
                    connection.commit();
                });
                assertThat(failure).isInstanceOfSatisfying(PSQLException.class, error -> {
                    assertThat(error.getSQLState()).isEqualTo("23514");
                    assertThat(error.getServerErrorMessage()).isNotNull();
                    assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo("dev_topo_roles_guard");
                });
            } finally {
                connection.rollback();
            }
        }
        assertThat(snapshot(fixture.projectId())).isEqualTo(before);
    }

    /** 核对实际 SQLException 的 SQLSTATE，锁超时、语法错误等不能冒充角色约束或 NOWAIT 拒绝。 */
    private void assertSqlState(String expected, SqlOperation action) {
        Throwable failure = catchThrowable(action::run);
        assertThat(failure).isInstanceOf(SQLException.class);
        assertThat(((SQLException) failure).getSQLState()).isEqualTo(expected);
    }

    /** 显式范围内的全表快照涵盖类型/设备/关系，包括删除标记和更新时间，不漏部分写入。 */
    private String snapshot(UUID projectId) throws SQLException {
        try (Connection connection = scopedConnection(projectId)) {
            return textValue(connection, """
                    SELECT jsonb_build_object(
                        'types', (SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM dev_type t),
                        'devices', (SELECT jsonb_agg(to_jsonb(d) ORDER BY id) FROM dev_device d),
                        'topology', (SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM dev_topo t))::text
                    """);
        }
    }

    /** 真实 APP_ROLE 连接，无项目范围时不应读到任何项目资源。 */
    private Connection appConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD);
        try {
            assertThat(textValue(connection, "SELECT current_user")).isEqualTo(APP_ROLE);
        } catch (SQLException | RuntimeException | Error failure) {
            connection.close();
            throw failure;
        }
        return connection;
    }

    /** 正常写入使用显式 READ COMMITTED，作用域与等待上限通过 LOCAL 参数随事务结束清除。 */
    private Connection scopedConnection(UUID projectId) throws SQLException {
        return scopedConnection(projectId, Connection.TRANSACTION_READ_COMMITTED);
    }

    /** 隔离级别在事务启动前设置，RR 用例才能证明持有真实固定快照而不是仅改一个测试标志。 */
    private Connection scopedConnection(UUID projectId, int isolation) throws SQLException {
        Connection connection = appConnection();
        try {
            connection.setTransactionIsolation(isolation);
            connection.setAutoCommit(false);
            textValue(connection, "SELECT set_config('app.project_id', ?, true)", projectId.toString());
            textValue(connection, "SELECT set_config('lock_timeout', '12s', true)");
            textValue(connection, "SELECT set_config('statement_timeout', '15s', true)");
        } catch (SQLException | RuntimeException | Error failure) {
            connection.close();
            throw failure;
        }
        return connection;
    }

    /** 独立应用观察连接验证实际阻塞者 PID，不依赖 sleep，也不消耗业务 CONTROL 连接池。 */
    private void assertBlocking(int waitingPid, int blockingPid) throws SQLException {
        assertThat(waitingPid).isNotEqualTo(blockingPid);
        try (Connection observer = appConnection(); PreparedStatement query = observer.prepareStatement(
                "SELECT pg_backend_pid(), ? = ANY(pg_blocking_pids(?))")) {
            query.setInt(1, blockingPid);
            query.setInt(2, waitingPid);
            query.setQueryTimeout(3);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < deadline) {
                try (ResultSet row = query.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getInt(1)).isNotEqualTo(waitingPid).isNotEqualTo(blockingPid);
                    if (row.getBoolean(2)) {
                        return;
                    }
                }
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("未观察到事务 " + waitingPid + " 被 " + blockingPid + " 的真实数据库锁阻塞");
    }

    /** 参数化直接写入保留 SQL NULL 和 UUID 类型，资源在每次语句完成后释放。 */
    private int execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            parameters(statement, arguments);
            return statement.executeUpdate();
        }
    }

    /** 单值字符串查询保留数据库 NULL，避免将未绑定投影误读成空字符串。 */
    private String textValue(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            parameters(query, arguments);
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    /** 计数和 PID 都必须真正返回非空值，不能把 SQL NULL 的 JDBC 零值当作证据。 */
    private int intValue(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            parameters(query, arguments);
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                int value = row.getInt(1);
                assertThat(row.wasNull()).isFalse();
                return value;
            }
        }
    }

    /** 所有可变值均通过 JDBC 绑定，只有固定受控 SQL 才进入数据库。 */
    private void parameters(PreparedStatement statement, Object[] arguments) throws SQLException {
        for (int index = 0; index < arguments.length; index++) {
            statement.setObject(index + 1, arguments[index]);
        }
    }

    /** 单连接 SQL 工作单元，可把两步投影和权威写入作为同一失败原子性验证。 */
    @FunctionalInterface
    private interface SqlAction {
        /** @param connection 已建立项目范围的真实应用事务连接 */
        void run(Connection connection) throws SQLException;
    }

    /** 可在执行语句或 commit 时抛 SQLException 的受检操作。 */
    @FunctionalInterface
    private interface SqlOperation {
        /** 执行原始数据库操作，错误由调用者检查 SQLSTATE。 */
        void run() throws SQLException;
    }

    /**
     * 独占项目内的一条合法关系与可用于非法换型的 DIRECT 身份。
     * @param tenantId 独占租户
     * @param projectId 独占项目及 RLS 轴
     * @param gatewayTypeId 网关草稿类型
     * @param subTypeId 子设备草稿类型
     * @param directTypeId 无关系的 DIRECT 类型
     * @param gatewayId 网关设备
     * @param subId 子设备
     * @param directId 无关系的 DIRECT 设备
     * @param topologyId 待建立或已存在关系的唯一主键
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID gatewayTypeId, UUID subTypeId, UUID directTypeId,
                           UUID gatewayId, UUID subId, UUID directId, UUID topologyId) { }
}
