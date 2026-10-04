package com.things.link.bootstrap.ingestion.modbus;

import com.things.link.device.infrastructure.persistence.JdbcModbusPollRepository;
import com.things.link.shared.message.DeviceConfigPush;
import com.things.link.shared.message.ModbusRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * S12-P0-4a / D-119：真实 APP pushConfig 必须原子写入配置 Outbox 和全部平台轮询行。
 * 仅验配置物化与事务；不执行调度扫描，也不把 Outbox 记录宣称为实际设备接收。
 */
@DisplayName("Modbus 配置物化与回滚边界")
class ModbusScheduleMaterializationTests extends AbstractModbusPollIntegrationTest {

    /** 仅在回滚反例中于真实 SQL 全部执行后抛错；成功路径仍调用实际仓储。 */
    @MockitoSpyBean private JdbcModbusPollRepository pollRepository;

    /** D-119 的字段位置绑定及 Outbox 同事务契约同时通过独立连接核验。 */
    @Test
    void pushConfigMaterializesEveryPublishedFieldAndCommitsWithItsOutbox() throws Exception {
        List<PointSpec> points = points(2);
        Fixture fixture = fixture(points);
        scopedApplicationCall(fixture, () -> {
            configService.pushConfig(fixture.projectId(), fixture.gatewayId());
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM dev_modbus_poll WHERE project_id = ?",
                    Integer.class, fixture.projectId())).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id = ?",
                    Integer.class, fixture.projectId())).isEqualTo(1);
            // APP 看见两类未提交写入，另一条真实连接两类都不可见，不能由测试返回值冒充原子提交。
            try (Connection owner = ownerConnection()) {
                assertThat(number(owner, "SELECT count(*) FROM dev_modbus_poll WHERE project_id = ?", fixture.projectId())).isZero();
                assertThat(number(owner, "SELECT count(*) FROM sys_outbox_event WHERE project_id = ?", fixture.projectId())).isZero();
            } catch (SQLException failure) {
                throw new IllegalStateException("独立连接核验未提交物化失败", failure);
            }
            return null;
        });
        assertSchedule(fixture, points);
        assertConfigurationOutbox(fixture, 1, points.size());
    }

    /** 每次重推重建当前点位集合，不能把旧运行时行和新行累积在同一网关下。 */
    @Test
    void repeatedPushReplacesOldScheduleWithoutAccumulatingRows() throws Exception {
        List<PointSpec> points = points(2);
        Fixture fixture = fixture(points);
        pushConfig(fixture);
        List<UUID> oldIds = scheduleIds(fixture);
        pushConfig(fixture);
        assertSchedule(fixture, points);
        assertThat(scheduleIds(fixture)).hasSize(2).doesNotContainAnyElementsOf(oldIds);
        assertConfigurationOutbox(fixture, 2, points.size());
    }

    /** 后段真实写入完成再抛错，必须同时回滚新 Outbox、删除旧行和重建新行这三个动作。 */
    @Test
    void failureAfterRealScheduleReplacementPreservesOriginalScheduleAndOutbox() throws Exception {
        List<PointSpec> points = points(2);
        Fixture fixture = fixture(points);
        pushConfig(fixture);
        List<String> oldSchedule = scheduleSnapshot(fixture);
        List<String> oldOutbox = outboxSnapshot(fixture);
        List<UUID> oldIds = scheduleIds(fixture);
        AtomicBoolean realReplacementObserved = new AtomicBoolean();
        JdbcModbusPollRepository target = AopTestUtils.getUltimateTargetObject(pollRepository);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            // 此处已执行真实 DELETE + 全部 INSERT，且 pushConfig 已在同一事务追加第二个 Outbox。
            assertThat(jdbcTemplate.queryForList("SELECT id FROM dev_modbus_poll WHERE project_id = ?",
                    UUID.class, fixture.projectId())).hasSize(2).doesNotContainAnyElementsOf(oldIds);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id = ?",
                    Integer.class, fixture.projectId())).isEqualTo(2);
            realReplacementObserved.set(true);
            throw new MaterializationFailure();
        }).when(target).syncSchedule(eq(fixture.projectId()), eq(fixture.gatewayId()),
                eq(fixture.projectKey()), eq("poll_gateway"), anyList());

        assertThatThrownBy(() -> pushConfig(fixture)).isInstanceOf(MaterializationFailure.class);
        assertThat(realReplacementObserved).isTrue();
        // 精确比较旧记录（包括 ID、请求状态、时间及 Outbox payload），不能仅凭数量相同判回滚成功。
        assertThat(scheduleSnapshot(fixture)).containsExactlyElementsOf(oldSchedule);
        assertThat(outboxSnapshot(fixture)).containsExactlyElementsOf(oldOutbox);
        assertSchedule(fixture, points);
        assertConfigurationOutbox(fixture, 1, points.size());
    }

    /** 全列旁路校验可以捕获占位符漏绑，以及类型兼容却绑定到错误列的静默错误。 */
    private void assertSchedule(Fixture fixture, List<PointSpec> points) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement statement = owner.prepareStatement("""
                SELECT * FROM dev_modbus_poll WHERE project_id = ? ORDER BY property_key
                """)) {
            statement.setObject(1, fixture.projectId());
            try (ResultSet rows = statement.executeQuery()) {
                for (PointSpec point : points) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject("id", UUID.class)).isNotNull();
                    assertThat(rows.getObject("tenant_id", UUID.class)).isEqualTo(fixture.tenantId());
                    assertThat(rows.getObject("project_id", UUID.class)).isEqualTo(fixture.projectId());
                    assertThat(rows.getObject("device_id", UUID.class)).isEqualTo(fixture.gatewayId());
                    assertThat(rows.getObject("sub_device_id", UUID.class)).isEqualTo(fixture.subDeviceId());
                    assertThat(rows.getString("project_key")).isEqualTo(fixture.projectKey());
                    assertThat(rows.getString("gateway_key")).isEqualTo("poll_gateway");
                    assertThat(rows.getString("property_key")).isEqualTo(point.propertyKey());
                    assertThat(rows.getInt("slave_address")).isEqualTo(point.slaveAddress());
                    assertThat(rows.getString("function_code")).isEqualTo(point.functionCode());
                    assertThat(rows.getInt("register_address")).isEqualTo(point.registerAddress());
                    assertThat(rows.getString("data_type")).isEqualTo(point.dataType());
                    assertThat(rows.getString("byte_order")).isEqualTo(point.byteOrder());
                    assertThat(rows.getBigDecimal("scale")).isEqualByComparingTo(point.scale());
                    assertThat(rows.getBigDecimal("offset")).isEqualByComparingTo(point.offset());
                    assertThat(rows.getInt("polling_interval_ms")).isEqualTo(point.pollingIntervalMs());
                    assertThat(rows.getString("status")).isEqualTo("IDLE");
                    assertThat(rows.getInt("attempt")).isZero();
                    assertThat(rows.getObject("lease_until")).isNull();
                    assertThat(rows.getObject("request_id")).isNull();
                    assertThat(rows.getTimestamp("next_poll_at")).isEqualTo(rows.getTimestamp("created_at"));
                    assertThat(rows.getTimestamp("created_at")).isNotNull().isEqualTo(rows.getTimestamp("updated_at"));
                }
                assertThat(rows.next()).isFalse();
            }
        }
    }

    /** 配置意图的身份及点位数量完整，且尚未产生 ModbusRequest 轮询发送意图。 */
    private void assertConfigurationOutbox(Fixture fixture, int expected, int points) throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(number(owner, "SELECT count(*) FROM sys_outbox_event WHERE project_id = ?", fixture.projectId())).isEqualTo(expected);
            assertThat(number(owner, """
                    SELECT count(*) FROM sys_outbox_event
                     WHERE project_id = ? AND tenant_id = ? AND aggregate_id = ? AND event_type = ?
                       AND payload::jsonb ->> 'gatewayId' = ? AND payload::jsonb ->> 'projectId' = ?
                       AND jsonb_array_length(payload::jsonb -> 'points') = ?
                    """, fixture.projectId(), fixture.tenantId(), fixture.gatewayId(), DeviceConfigPush.EVENT_TYPE,
                    fixture.gatewayId().toString(), fixture.projectId().toString(), points)).isEqualTo(expected);
            assertThat(number(owner, "SELECT count(*) FROM sys_outbox_event WHERE project_id = ? AND event_type = ?",
                    fixture.projectId(), ModbusRequest.EVENT_TYPE)).isZero();
        }
    }

    /** 比较实际行身份，确保重推确实替换而不是跳过物化或重复追加。 */
    private List<UUID> scheduleIds(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement statement = owner.prepareStatement(
                "SELECT id FROM dev_modbus_poll WHERE project_id = ? ORDER BY id")) {
            statement.setObject(1, fixture.projectId());
            try (ResultSet rows = statement.executeQuery()) {
                List<UUID> ids = new ArrayList<>();
                while (rows.next()) ids.add(rows.getObject(1, UUID.class));
                return ids;
            }
        }
    }

    /** 固定 SQL 输出整行，回滚验证包括新旧主键和全部运行时字段。 */
    private List<String> scheduleSnapshot(Fixture fixture) throws SQLException {
        return snapshot(fixture, "SELECT row_to_json(p)::text FROM dev_modbus_poll p WHERE project_id = ? ORDER BY id");
    }

    /** 原始 Outbox payload、事件身份及时间必须在失败后原样保留。 */
    private List<String> outboxSnapshot(Fixture fixture) throws SQLException {
        return snapshot(fixture, "SELECT row_to_json(e)::text FROM sys_outbox_event e WHERE project_id = ? ORDER BY id");
    }

    /** 仅接收本类固定查询并绑定夹具项目，不通过动态表名扩大旁路范围。 */
    private List<String> snapshot(Fixture fixture, String sql) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement statement = owner.prepareStatement(sql)) {
            statement.setObject(1, fixture.projectId());
            try (ResultSet rows = statement.executeQuery()) {
                List<String> result = new ArrayList<>();
                while (rows.next()) result.add(rows.getString(1));
                return result;
            }
        }
    }

    /** 精确故障类型确保测试没有把其他 SQL 错误误判为预定的后段回滚。 */
    private static final class MaterializationFailure extends RuntimeException {
        /** 固定诊断说明这是成功执行真实物化之后的可控故障。 */
        private MaterializationFailure() { super("真实调度物化后的事务回滚探针"); }
    }
}
