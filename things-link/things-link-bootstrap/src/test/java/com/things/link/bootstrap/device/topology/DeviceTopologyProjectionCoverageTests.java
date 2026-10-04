package com.things.link.bootstrap.device.topology;

import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.util.PSQLException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * D-115/ADR0060真实验收：五项拓扑身份在插入后固定，设备INSERT也须满足ADR0033的延期投影一致性。
 * 身份变化即时拒绝，同值、在线CAS、关旧建新及物理清理继续遵循既有合法事务边界。
 * 使用独占项目和新APP物理连接，保持0340/0200/0400约束开启，不以停用守卫准备夹具。
 */
@DisplayName("D-115 拓扑投影旧端点与设备插入覆盖")
class DeviceTopologyProjectionCoverageTests extends AbstractIntegrationTest {

    /** ADR0060身份拒绝必须与相邻角色/投影守卫区分，避免23514错误归因。 */
    private static final String IDENTITY_GUARD = "dev_topo_identity_guard";
    /** 新设备延期投影拒绝复用0400的专属约束标识。 */
    private static final String PROJECTION_GUARD = "dev_topo_projection_guard";

    /** 每项均为固定受控SQL列名，值另行参数绑定。 */
    private enum IdentityField {
        /** 关系自身稳定身份。 */ ID("id"),
        /** 原始租户归属。 */ TENANT("tenant_id"),
        /** 原始项目归属。 */ PROJECT("project_id"),
        /** 原始网关身份。 */ GATEWAY("gateway_device_id"),
        /** 原始子设备身份。 */ SUB_DEVICE("sub_device_id");

        /** 只来自本枚举常量的列名，不接受调用方输入。 */
        private final String column;

        /** @param column 固定的受控身份列名 */
        IdentityField(String column) {
            this.column = column;
        }
    }

    /** 原G→A换成G→B的反例必须在首个身份UPDATE立即拒绝，不能等到只同步B或COMMIT才发现。 */
    @Test
    void movingTopologyToAnotherSubDeviceCannotLeaveOldProjectionBehind() throws Exception {
        Fixture fixture = fixture();
        String before = snapshot(fixture);
        Throwable failure;
        // 保留原攻击步骤并记录实际执行时点，证明新合同确实在第一条身份UPDATE拒绝。
        String[] phase = {"更新权威关系端点"};
        try (Connection application = scopedConnection(fixture.projectId())) {
            try {
                failure = catchThrowable(() -> {
                    assertThat(execute(application, "UPDATE public.dev_topo SET sub_device_id = ? WHERE project_id = ? AND id = ?",
                            fixture.subB(), fixture.projectId(), fixture.topologyId())).isEqualTo(1);
                    phase[0] = "只同步新子设备投影";
                    assertThat(execute(application, "UPDATE public.dev_device SET gateway_id = ? WHERE project_id = ? AND id = ?",
                            fixture.gatewayId(), fixture.projectId(), fixture.subB())).isEqualTo(1);
                    phase[0] = "提交端点变更及新投影";
                    application.commit();
                });
            } finally {
                application.rollback();
            }
        }
        assertRejectedUnchanged(fixture, before, failure, phase[0], IDENTITY_GUARD);
        assertThat(phase[0]).as("身份守卫必须在第一条UPDATE即时拒绝").isEqualTo("更新权威关系端点");
    }

    /** 新设备的合法类型和gateway外键均满足时，孤立非空投影仍必须在COMMIT触发23514并整笔回滚。 */
    @Test
    void insertingProjectedDeviceWithoutTopologyMustBeRejectedAtCommit() throws Exception {
        Fixture fixture = fixture();
        UUID insertedDevice = UUID.randomUUID();
        String before = snapshot(fixture);
        Throwable commitFailure;
        try (Connection application = scopedConnection(fixture.projectId())) {
            try {
                assertThat(insertProjectedDevice(application, fixture, insertedDevice))
                        .as("INSERT应先通过旧CHECK/FK，不能把无效夹具当作延期投影保护")
                        .isEqualTo(1);
                assertThat(text(application, "SELECT gateway_id::text FROM public.dev_device WHERE id = ?", insertedDevice))
                        .isEqualTo(fixture.gatewayId().toString());
                assertThat(integer(application, "SELECT count(*) FROM public.dev_topo WHERE sub_device_id = ? AND unbound_at IS NULL",
                        insertedDevice)).isZero();
                commitFailure = catchThrowable(application::commit);
            } finally {
                application.rollback();
            }
        }
        assertRejectedUnchanged(fixture, before, commitFailure, "提交带孤立投影的新设备", PROJECTION_GUARD);
    }

    /** 新设备INSERT后改主键不能让延期队列只查消失的旧ID，从而漏掉新ID仍携带的孤立投影。 */
    @Test
    void renamingInsertedOrphanDeviceCannotHideItsProjectionAtCommit() throws Exception {
        Fixture fixture = fixture();
        UUID insertedDevice = UUID.randomUUID();
        UUID renamedDevice = UUID.randomUUID();
        String before = snapshot(fixture);
        Throwable commitFailure;
        try (Connection application = scopedConnection(fixture.projectId())) {
            try {
                assertThat(insertProjectedDevice(application, fixture, insertedDevice)).isEqualTo(1);
                assertThat(execute(application, "UPDATE public.dev_device SET id = ? WHERE project_id = ? AND id = ?",
                        renamedDevice, fixture.projectId(), insertedDevice))
                        .as("INSERT和无历史设备的ID修改均应先通过，不能把即时其他约束错误当作延期投影覆盖")
                        .isEqualTo(1);
                assertThat(integer(application, "SELECT count(*) FROM public.dev_device WHERE id = ?", insertedDevice)).isZero();
                assertThat(text(application, "SELECT gateway_id::text FROM public.dev_device WHERE id = ?", renamedDevice))
                        .isEqualTo(fixture.gatewayId().toString());
                assertThat(integer(application, "SELECT count(*) FROM public.dev_topo WHERE sub_device_id = ? AND unbound_at IS NULL",
                        renamedDevice)).isZero();
                commitFailure = catchThrowable(application::commit);
            } finally {
                application.rollback();
            }
        }
        assertRejectedUnchanged(fixture, before, commitFailure, "提交INSERT后主键改名的孤立投影", PROJECTION_GUARD);
    }

    /** 设备ID未被本片冻结：新设备没有网关投影和关系时，INSERT后改ID的同写序应正常提交。 */
    @Test
    void renamingInsertedDeviceWithNullGatewayProjectionCommitsNormally() throws Exception {
        Fixture fixture = fixture();
        UUID insertedDevice = UUID.randomUUID();
        UUID renamedDevice = UUID.randomUUID();
        try (Connection application = scopedConnection(fixture.projectId())) {
            try {
                assertThat(execute(application, """
                        INSERT INTO public.dev_device (id, tenant_id, project_id, device_type_id, gateway_id, device_key, name)
                        VALUES (?, ?, ?, ?, NULL, 'rename_unbound', '无投影设备主键修改对照')
                        """, insertedDevice, fixture.tenantId(), fixture.projectId(), fixture.subTypeId())).isEqualTo(1);
                assertThat(execute(application, "UPDATE public.dev_device SET id = ? WHERE project_id = ? AND id = ?",
                        renamedDevice, fixture.projectId(), insertedDevice)).isEqualTo(1);
                application.commit();
            } finally {
                application.rollback();
            }
        }
        try (Connection verification = scopedConnection(fixture.projectId())) {
            assertThat(integer(verification, "SELECT count(*) FROM public.dev_device WHERE id = ?", insertedDevice)).isZero();
            assertThat(text(verification, "SELECT gateway_id::text FROM public.dev_device WHERE id = ?", renamedDevice)).isNull();
            assertThat(text(verification, "SELECT device_type_id::text FROM public.dev_device WHERE id = ?", renamedDevice))
                    .isEqualTo(fixture.subTypeId().toString());
            assertThat(integer(verification, "SELECT count(*) FROM public.dev_topo WHERE sub_device_id = ?", renamedDevice)).isZero();
            assertThat(integer(verification, "SELECT count(*) FROM public.dev_device_model_binding_history WHERE device_id = ?", renamedDevice))
                    .isZero();
            assertThat(integer(verification, "SELECT count(*) FROM public.dev_device")).isEqualTo(4);
            assertThat(integer(verification, "SELECT count(*) FROM public.dev_topo WHERE unbound_at IS NULL")).isEqualTo(1);
            assertThat(text(verification, "SELECT gateway_id::text FROM public.dev_device WHERE id = ?", fixture.subA()))
                    .isEqualTo(fixture.gatewayId().toString());
        }
    }

    /** 同事务插入带gateway_id的新设备及匹配权威关系应可提交，延期一致性不能被改成错误的即时拒绝。 */
    @Test
    void insertingProjectedDeviceAndMatchingTopologyCommitsAtomically() throws Exception {
        Fixture fixture = fixture();
        UUID insertedDevice = UUID.randomUUID();
        UUID insertedTopology = UUID.randomUUID();
        try (Connection application = scopedConnection(fixture.projectId())) {
            try {
                assertThat(insertProjectedDevice(application, fixture, insertedDevice)).isEqualTo(1);
                assertThat(execute(application, """
                        INSERT INTO public.dev_topo (id, tenant_id, project_id, gateway_device_id, sub_device_id, bind_source)
                        VALUES (?, ?, ?, ?, ?, 'CONTROL_PLANE')
                        """, insertedTopology, fixture.tenantId(), fixture.projectId(), fixture.gatewayId(), insertedDevice))
                        .isEqualTo(1);
                application.commit();
            } finally {
                application.rollback();
            }
        }
        try (Connection verification = scopedConnection(fixture.projectId())) {
            assertThat(integer(verification, "SELECT count(*) FROM public.dev_device")).isEqualTo(4);
            assertThat(integer(verification, "SELECT count(*) FROM public.dev_topo WHERE unbound_at IS NULL")).isEqualTo(2);
            assertThat(text(verification, "SELECT gateway_id::text FROM public.dev_device WHERE id = ?", insertedDevice))
                    .isEqualTo(fixture.gatewayId().toString());
            assertThat(text(verification, "SELECT gateway_device_id::text FROM public.dev_topo WHERE id = ?", insertedTopology))
                    .isEqualTo(fixture.gatewayId().toString());
            assertThat(text(verification, "SELECT sub_device_id::text FROM public.dev_topo WHERE id = ?", insertedTopology))
                    .isEqualTo(insertedDevice.toString());
            assertThat(text(verification, "SELECT gateway_id::text FROM public.dev_device WHERE id = ?", fixture.subA()))
                    .isEqualTo(fixture.gatewayId().toString());
            assertThat(text(verification, "SELECT sub_device_id::text FROM public.dev_topo WHERE id = ?", fixture.topologyId()))
                    .isEqualTo(fixture.subA().toString());
            assertThat(text(verification, "SELECT gateway_id::text FROM public.dev_device WHERE id = ?", fixture.subB())).isNull();
        }
    }

    /** 五身份分别覆盖有效/已关闭关系，真实替换值存在时也必须在UPDATE语句内由身份守卫拒绝。 */
    @ParameterizedTest(name = "{0}，关系已关闭={1}")
    @MethodSource("identityChanges")
    void allFiveIdentityFieldsAreImmutableForActiveAndClosedRows(IdentityField field, boolean closed) throws Exception {
        Fixture fixture = fixture();
        if (closed) {
            try (Connection application = scopedConnection(fixture.projectId())) {
                closeBinding(application, fixture);
                application.commit();
            }
        }
        UUID replacement = replacementIdentity(fixture, field);
        String before = snapshot(fixture);
        Throwable failure;
        try (Connection application = scopedConnection(fixture.projectId())) {
            try {
                // 先写同事务元信息，后续即时身份拒绝须连同这一步整体撤销。
                assertThat(execute(application, "UPDATE public.dev_device SET name = '必须回滚的名称' WHERE id = ?", fixture.subA()))
                        .isEqualTo(1);
                failure = catchThrowable(() -> execute(application,
                        "UPDATE public.dev_topo SET " + field.column + " = ? WHERE id = ?", replacement, fixture.topologyId()));
            } finally {
                application.rollback();
            }
        }
        assertRejectedUnchanged(fixture, before, failure, "直接修改" + field.column, IDENTITY_GUARD);
    }

    /** 笛卡尔积明确提供五字段×两种关系状态，不以只测活跃关系推断历史身份也受到保护。 */
    private static Stream<Arguments> identityChanges() {
        return Arrays.stream(IdentityField.values())
                .flatMap(field -> Stream.of(Arguments.of(field, false), Arguments.of(field, true)));
    }

    /** 同值身份更新可与在线CAS一起提交；重新绑定须关闭旧关系后插入新ID，旧端点身份保持。 */
    @Test
    void equalIdentityOnlineCasAndCloseThenCreateNewRelationshipRemainLegal() throws Exception {
        Fixture fixture = fixture();
        try (Connection application = scopedConnection(fixture.projectId())) {
            try {
                assertThat(execute(application, """
                        UPDATE public.dev_topo SET id = id, tenant_id = tenant_id, project_id = project_id,
                               gateway_device_id = gateway_device_id, sub_device_id = sub_device_id,
                               online_status = 'ONLINE', last_online_at = now(), status_changed_at = now(), version = version + 1
                         WHERE id = ? AND version = 1
                        """, fixture.topologyId())).isEqualTo(1);
                assertThat(execute(application, "UPDATE public.dev_device SET status = 'ONLINE', last_online_at = now() WHERE id = ?",
                        fixture.subA())).isEqualTo(1);
                application.commit();
            } finally {
                application.rollback();
            }
        }
        UUID newTopologyId = UUID.randomUUID();
        try (Connection application = scopedConnection(fixture.projectId())) {
            try {
                assertThat(text(application, "SELECT online_status FROM public.dev_topo WHERE id = ?", fixture.topologyId())).isEqualTo("ONLINE");
                assertThat(integer(application, "SELECT version FROM public.dev_topo WHERE id = ?", fixture.topologyId())).isEqualTo(2);
                closeBinding(application, fixture);
                assertThat(execute(application, """
                        UPDATE public.dev_topo SET id = id, tenant_id = tenant_id, project_id = project_id,
                               gateway_device_id = gateway_device_id, sub_device_id = sub_device_id WHERE id = ?
                        """, fixture.topologyId())).isEqualTo(1);
                assertThat(execute(application, """
                        INSERT INTO public.dev_topo (id, tenant_id, project_id, gateway_device_id, sub_device_id, bind_source)
                        VALUES (?, ?, ?, ?, ?, 'CONTROL_PLANE')
                        """, newTopologyId, fixture.tenantId(), fixture.projectId(), fixture.gatewayId(), fixture.subB())).isEqualTo(1);
                assertThat(execute(application, "UPDATE public.dev_device SET gateway_id = ? WHERE id = ?", fixture.gatewayId(), fixture.subB()))
                        .isEqualTo(1);
                application.commit();
            } finally {
                application.rollback();
            }
        }
        try (Connection verification = scopedConnection(fixture.projectId())) {
            assertThat(integer(verification, "SELECT count(*) FROM public.dev_topo WHERE id = ? AND unbound_at IS NOT NULL", fixture.topologyId()))
                    .isEqualTo(1);
            assertThat(text(verification, "SELECT sub_device_id::text FROM public.dev_topo WHERE id = ?", fixture.topologyId()))
                    .isEqualTo(fixture.subA().toString());
            assertThat(text(verification, "SELECT sub_device_id::text FROM public.dev_topo WHERE id = ? AND unbound_at IS NULL", newTopologyId))
                    .isEqualTo(fixture.subB().toString());
            assertThat(text(verification, "SELECT gateway_id::text FROM public.dev_device WHERE id = ?", fixture.subA())).isNull();
            assertThat(text(verification, "SELECT gateway_id::text FROM public.dev_device WHERE id = ?", fixture.subB()))
                    .isEqualTo(fixture.gatewayId().toString());
        }
    }

    /** 同事务合法新设备/关系也须在另一孤立投影触发失败时回滚，不能留下部分已生效的新绑定。 */
    @Test
    void failedCommitRollsBackNewDeviceAndMatchingTopologyTogether() throws Exception {
        Fixture fixture = fixture();
        UUID validDevice = UUID.randomUUID();
        String before = snapshot(fixture);
        Throwable failure;
        try (Connection application = scopedConnection(fixture.projectId())) {
            try {
                assertThat(insertProjectedDevice(application, fixture, validDevice)).isEqualTo(1);
                assertThat(execute(application, """
                        INSERT INTO public.dev_topo (id, tenant_id, project_id, gateway_device_id, sub_device_id, bind_source)
                        VALUES (?, ?, ?, ?, ?, 'CONTROL_PLANE')
                        """, UUID.randomUUID(), fixture.tenantId(), fixture.projectId(), fixture.gatewayId(), validDevice)).isEqualTo(1);
                assertThat(insertProjectedDevice(application, fixture, UUID.randomUUID())).isEqualTo(1);
                failure = catchThrowable(application::commit);
            } finally {
                application.rollback();
            }
        }
        assertRejectedUnchanged(fixture, before, failure, "提交含合法新绑定与另一孤立投影的事务", PROJECTION_GUARD);
    }

    /** INSERT后同事务物理删除设备时，延期队列看到真实行已不存在应允许提交，不制造幽灵投影错误。 */
    @Test
    void insertingThenDeletingDeviceInSameTransactionLeavesNoProjectionFailure() throws Exception {
        Fixture fixture = fixture();
        UUID insertedDevice = UUID.randomUUID();
        String before = snapshot(fixture);
        try (Connection application = scopedConnection(fixture.projectId())) {
            try {
                assertThat(insertProjectedDevice(application, fixture, insertedDevice)).isEqualTo(1);
                assertThat(execute(application, "DELETE FROM public.dev_device WHERE id = ?", insertedDevice)).isEqualTo(1);
                application.commit();
            } finally {
                application.rollback();
            }
        }
        assertThat(snapshot(fixture)).isEqualTo(before);
    }

    /** 五身份守卫不拦DELETE，既有网关物理删除级联仍删除权威关系并由外键清空子设备投影。 */
    @Test
    void physicalGatewayDeletionStillCascadesTopologyAndClearsProjection() throws Exception {
        Fixture fixture = fixture();
        try (Connection application = scopedConnection(fixture.projectId())) {
            try {
                assertThat(execute(application, "DELETE FROM public.dev_device WHERE id = ?", fixture.gatewayId())).isEqualTo(1);
                application.commit();
            } finally {
                application.rollback();
            }
        }
        try (Connection verification = scopedConnection(fixture.projectId())) {
            assertThat(integer(verification, "SELECT count(*) FROM public.dev_device")).isEqualTo(2);
            assertThat(integer(verification, "SELECT count(*) FROM public.dev_topo")).isZero();
            assertThat(text(verification, "SELECT gateway_id::text FROM public.dev_device WHERE id = ?", fixture.subA())).isNull();
            assertThat(text(verification, "SELECT gateway_id::text FROM public.dev_device WHERE id = ?", fixture.subB())).isNull();
        }
    }

    /** 替换租户/项目已有真实身份，网关使用另一个合法GATEWAY，避免用无效FK或分类碰巧得到拒绝。 */
    private UUID replacementIdentity(Fixture fixture, IdentityField field) throws SQLException {
        return switch (field) {
            case ID -> UUID.randomUUID();
            case TENANT -> fixture().tenantId();
            case PROJECT -> fixture().projectId();
            case SUB_DEVICE -> fixture.subB();
            case GATEWAY -> {
                UUID alternateGateway = UUID.randomUUID();
                try (Connection application = scopedConnection(fixture.projectId())) {
                    try {
                        assertThat(execute(application, """
                                INSERT INTO public.dev_device (id, tenant_id, project_id, device_type_id, device_key, name)
                                SELECT ?, tenant_id, project_id, device_type_id, 'alternate_gateway', '另一合法网关'
                                  FROM public.dev_device WHERE id = ?
                                """, alternateGateway, fixture.gatewayId())).isEqualTo(1);
                        application.commit();
                    } finally {
                        application.rollback();
                    }
                }
                yield alternateGateway;
            }
        };
    }

    /** 关闭只更新生命周期/在线字段并清空投影，保留原关系ID和双方端点身份。 */
    private void closeBinding(Connection application, Fixture fixture) throws SQLException {
        assertThat(execute(application, """
                UPDATE public.dev_topo SET unbound_at = now(), online_status = 'OFFLINE', status_changed_at = now(), version = version + 1
                 WHERE id = ? AND unbound_at IS NULL
                """, fixture.topologyId())).isEqualTo(1);
        assertThat(execute(application, "UPDATE public.dev_device SET gateway_id = NULL, status = CASE WHEN last_online_at IS NULL THEN 'INACTIVE' ELSE 'OFFLINE' END WHERE id = ?",
                fixture.subA())).isEqualTo(1);
    }

    /** 失败或误成功都读取提交后的完整快照，SoftAssert同时报告SQLSTATE、真实时点和持久化副作用。 */
    private void assertRejectedUnchanged(Fixture fixture, String before, Throwable failure, String phase, String expectedConstraint) throws SQLException {
        String after = snapshot(fixture);
        String sqlState = failure instanceof SQLException postgres ? postgres.getSQLState() : null;
        String constraint = failure instanceof PSQLException postgres && postgres.getServerErrorMessage() != null
                ? postgres.getServerErrorMessage().getConstraint() : null;
        String message = failure instanceof PSQLException postgres && postgres.getServerErrorMessage() != null
                ? postgres.getServerErrorMessage().getMessage() : null;
        assertSoftly(softly -> {
            softly.assertThat(failure).as("%s必须安全拒绝，误成功时异常为null", phase).isInstanceOf(SQLException.class);
            softly.assertThat(sqlState).as("不能把类型、权限或语法错误冒充投影覆盖保护").isEqualTo("23514");
            softly.assertThat(constraint).as("精确区分身份守卫和延期投影守卫").isEqualTo(expectedConstraint);
            if (IDENTITY_GUARD.equals(expectedConstraint)) {
                softly.assertThat(message).isEqualTo("D115_TOPOLOGY_IDENTITY_IMMUTABLE");
            }
            softly.assertThat(after).as("A/B及新设备、全部权威拓扑与投影都必须保持事务前事实").isEqualTo(before);
        });
    }

    /** 新设备采用已存在的SUB_DEVICE类型和同项目GATEWAY，确保只缺权威关系这一被测条件。 */
    private int insertProjectedDevice(Connection connection, Fixture fixture, UUID deviceId) throws SQLException {
        return execute(connection, """
                INSERT INTO public.dev_device (id, tenant_id, project_id, device_type_id, gateway_id, device_key, name)
                VALUES (?, ?, ?, ?, ?, ?, '新增子设备')
                """, deviceId, fixture.tenantId(), fixture.projectId(), fixture.subTypeId(), fixture.gatewayId(),
                "new_" + deviceId.toString().replace("-", ""));
    }

    /**
     * 随机项目内准备合法GATEWAY、两个SUB_DEVICE及G→A关系，B从未绑定且投影为空。
     * owner只建平台身份；业务事实由APP_ROLE同事务双写并正常提交，不删除其他用例数据。
     */
    private Fixture fixture() throws SQLException {
        Fixture fixture = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        UUID gatewayType = UUID.randomUUID();
        try (Connection owner = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, '投影事件覆盖租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, project_key) VALUES (?, ?, '投影事件覆盖项目', ?)",
                    fixture.projectId(), fixture.tenantId(), "projection_events_" + fixture.projectId().toString().replace("-", ""));
        }
        try (Connection application = scopedConnection(fixture.projectId())) {
            try {
                execute(application, """
                        INSERT INTO public.dev_type
                            (id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type)
                        VALUES (?, ?, ?, 'gateway_type', '网关类型', 'GATEWAY', 'STANDARD_GATEWAY', 'ETHERNET'),
                               (?, ?, ?, 'sub_type', '子设备类型', 'SUB_DEVICE', 'STANDARD', 'ZIGBEE')
                        """, gatewayType, fixture.tenantId(), fixture.projectId(), fixture.subTypeId(), fixture.tenantId(), fixture.projectId());
                execute(application, """
                        INSERT INTO public.dev_device (id, tenant_id, project_id, device_type_id, device_key, name)
                        VALUES (?, ?, ?, ?, 'gateway', '网关G'), (?, ?, ?, ?, 'sub_a', '子设备A'), (?, ?, ?, ?, 'sub_b', '子设备B')
                        """, fixture.gatewayId(), fixture.tenantId(), fixture.projectId(), gatewayType,
                        fixture.subA(), fixture.tenantId(), fixture.projectId(), fixture.subTypeId(),
                        fixture.subB(), fixture.tenantId(), fixture.projectId(), fixture.subTypeId());
                execute(application, """
                        INSERT INTO public.dev_topo (id, tenant_id, project_id, gateway_device_id, sub_device_id, bind_source)
                        VALUES (?, ?, ?, ?, ?, 'CONTROL_PLANE')
                        """, fixture.topologyId(), fixture.tenantId(), fixture.projectId(), fixture.gatewayId(), fixture.subA());
                assertThat(execute(application, "UPDATE public.dev_device SET gateway_id = ? WHERE id = ?", fixture.gatewayId(), fixture.subA()))
                        .isEqualTo(1);
                application.commit();
            } finally {
                application.rollback();
            }
        }
        try (Connection verification = scopedConnection(fixture.projectId())) {
            assertThat(integer(verification, "SELECT count(*) FROM public.dev_device")).isEqualTo(3);
            assertThat(integer(verification, "SELECT count(*) FROM public.dev_topo WHERE unbound_at IS NULL")).isEqualTo(1);
            assertThat(text(verification, "SELECT sub_device_id::text FROM public.dev_topo WHERE id = ?", fixture.topologyId()))
                    .isEqualTo(fixture.subA().toString());
            assertThat(text(verification, "SELECT gateway_id::text FROM public.dev_device WHERE id = ?", fixture.subA()))
                    .isEqualTo(fixture.gatewayId().toString());
            assertThat(text(verification, "SELECT gateway_id::text FROM public.dev_device WHERE id = ?", fixture.subB())).isNull();
        }
        return fixture;
    }

    /** 全项目设备与拓扑快照包含A/B整行和新插入身份，避免只看更新端点漏掉旧投影残留。 */
    private String snapshot(Fixture fixture) throws SQLException {
        try (Connection connection = scopedConnection(fixture.projectId())) {
            return text(connection, """
                    SELECT jsonb_build_object(
                        'devices', (SELECT jsonb_agg(to_jsonb(d) ORDER BY d.id) FROM public.dev_device d),
                        'topology', (SELECT jsonb_agg(to_jsonb(t) ORDER BY t.id) FROM public.dev_topo t))::text
                    """);
        }
    }

    /** 每次DriverManager建立新APP物理连接，显式RC和项目范围保持现有角色守卫前置，超时仅作失败清理兜底。 */
    private Connection scopedConnection(UUID projectId) throws SQLException {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD);
        try {
            assertThat(text(connection, "SELECT current_user")).isEqualTo(APP_ROLE);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            assertThat(text(connection, "SELECT set_config('app.project_id', ?, true)", projectId.toString())).isEqualTo(projectId.toString());
            text(connection, "SELECT set_config('lock_timeout', '10s', true)");
            text(connection, "SELECT set_config('statement_timeout', '15s', true)");
            return connection;
        } catch (SQLException | RuntimeException | Error failure) {
            connection.close();
            throw failure;
        }
    }

    /** 固定SQL执行后立即关闭语句，可变身份只通过JDBC绑定。 */
    private int execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            parameters(statement, arguments);
            return statement.executeUpdate();
        }
    }

    /** 单值查询必须返回一行，保留SQL NULL用于识别未绑定设备投影。 */
    private String text(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            parameters(statement, arguments);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    /** 计数来自非空数据库结果，不能以JDBC空值默认0掩盖错误查询。 */
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

    /** 参数保持原始UUID/NULL类型，不拼接任何项目或设备身份。 */
    private void parameters(PreparedStatement statement, Object[] arguments) throws SQLException {
        for (int index = 0; index < arguments.length; index++) {
            statement.setObject(index + 1, arguments[index]);
        }
    }

    /**
     * 每例独占的合法G→A拓扑及未绑定B身份。
     * @param tenantId 平台归属租户
     * @param projectId RLS隔离项目
     * @param subTypeId A/B及新增设备共用的合法SUB_DEVICE类型
     * @param gatewayId 真实网关G
     * @param subA 当前绑定子设备A
     * @param subB 尚未绑定子设备B
     * @param topologyId 当前有效G→A关系
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID subTypeId, UUID gatewayId,
                           UUID subA, UUID subB, UUID topologyId) { }
}
