package com.things.link.bootstrap.ingestion.modbus;

import com.things.link.device.application.ModbusConfigService;
import com.things.link.device.application.ModbusPollScheduler;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.RlsScope;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S12-P0-4 的独占 Modbus 夹具：owner 只播种合法已发布映射，APP 控制面真实物化调度输入。
 * 本基类没有测试方法，也不共享静态夹具；后续运行时切片可复用身份，但须自行验证后台范围恢复。
 */
abstract class AbstractModbusPollIntegrationTest extends AbstractIntegrationTest {

    /**
     * 本测试族由测试方法显式驱动扫描；关闭500ms真实入口，避免它在断言间改写相同夹具。
     * 生产服务、仓储和事务仍为真实对象，因此并发与RLS合同不被替换。
     */
    @MockitoBean(enforceOverride = true) private ModbusPollScheduler pollScheduler;

    /** 保留真实配置下发事务，不以直接插入 poll 代替生产入口。 */
    @Autowired protected ModbusConfigService configService;
    /** APP 连接用于权限及事务内事实核验，owner 连接仅作独立旁观。 */
    @Autowired protected JdbcTemplate jdbcTemplate;
    /** 显式事务包装确保 RLS 在连接首次取出前已设置。 */
    @Autowired protected PlatformTransactionManager transactionManager;
    /** 每个测试实例独占清理名单；播种或断言中途失败也能回收运行时输入。 */
    private final List<Fixture> fixtures = new ArrayList<>();

    /** 非默认转换和周期值可捕捉位置错绑，地址留出 32 位寄存器所需空间。 */
    protected List<PointSpec> points(int count) {
        List<PointSpec> points = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            points.add(new PointSpec("temperature_" + index, index + 2, 100 + index * 4,
                    "READ_HOLDING_REGISTERS", "FLOAT32", "LITTLE_ENDIAN",
                    new BigDecimal("1.25").add(BigDecimal.valueOf(index)),
                    new BigDecimal("-3.50").subtract(BigDecimal.valueOf(index)), 600_000 + index * 1_000));
        }
        return List.copyOf(points);
    }

    /**
     * 只播种当前夹具的拓扑、NUMBER/REPORT 属性和已发布点位；不冒充 create/publish API 的验收。
     * 状态 OFFLINE 是合法配置下发前置，不在本片证明在线或 MQTT 设备响应。
     */
    protected Fixture fixture(List<PointSpec> points) throws SQLException {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
        fixtures.add(fixture);
        UUID gatewayType = Uuid7.generate();
        UUID subType = Uuid7.generate();
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, '轮询独占租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, project_key) VALUES (?, ?, '轮询独占项目', ?)",
                    fixture.projectId(), fixture.tenantId(), fixture.projectKey());
            execute(owner, """
                    INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type, status)
                    VALUES (?, ?, ?, 'poll_gateway', '轮询网关类型', 'GATEWAY', 'MODBUS_RTU_CLOUD_GATEWAY', 'WIFI', 'DRAFT'),
                           (?, ?, ?, 'poll_sub', '轮询子设备类型', 'SUB_DEVICE', 'STANDARD', 'WIFI', 'DRAFT')
                    """, gatewayType, fixture.tenantId(), fixture.projectId(), subType, fixture.tenantId(), fixture.projectId());
            execute(owner, """
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                    VALUES (?, ?, ?, ?, 'poll_gateway', '离线轮询网关', 'OFFLINE'),
                           (?, ?, ?, ?, 'poll_sub', '轮询子设备', 'OFFLINE')
                    """, fixture.gatewayId(), fixture.tenantId(), fixture.projectId(), gatewayType,
                    fixture.subDeviceId(), fixture.tenantId(), fixture.projectId(), subType);
            execute(owner, """
                    INSERT INTO dev_topo (id, tenant_id, project_id, gateway_device_id, sub_device_id, bind_source, online_status)
                    VALUES (?, ?, ?, ?, ?, 'CONTROL_PLANE', 'OFFLINE')
                    """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.gatewayId(), fixture.subDeviceId());
            execute(owner, "UPDATE dev_device SET gateway_id = ? WHERE id = ?", fixture.gatewayId(), fixture.subDeviceId());
            for (PointSpec point : points) {
                execute(owner, """
                        INSERT INTO dev_property_definition
                            (id, tenant_id, project_id, device_type_id, property_key, name, access_type, data_type)
                        VALUES (?, ?, ?, ?, ?, '轮询数值属性', 'REPORT', 'NUMBER')
                        """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), subType, point.propertyKey());
                execute(owner, """
                        INSERT INTO dev_modbus_point_mapping
                            (id, tenant_id, project_id, device_id, sub_device_id, property_key, slave_address,
                             function_code, register_address, data_type, byte_order, scale, "offset", polling_interval_ms, version, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 'PUBLISHED')
                        """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.gatewayId(), fixture.subDeviceId(),
                        point.propertyKey(), point.slaveAddress(), point.functionCode(), point.registerAddress(),
                        point.dataType(), point.byteOrder(), point.scale(), point.offset(), point.pollingIntervalMs());
            }
            owner.commit();
        }
        return fixture;
    }

    /** 单独保留播种与下发动作，使失败测试能观测首次提交前后及回滚后的事实。 */
    protected void pushConfig(Fixture fixture) {
        scopedApplicationCall(fixture, () -> {
            configService.pushConfig(fixture.projectId(), fixture.gatewayId());
            return null;
        });
    }

    /** 控制面 APP 事务；不能用于伪装后台无上下文扫描的正确性。 */
    protected <T> T scopedApplicationCall(Fixture fixture, Supplier<T> action) {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        RlsScopeContext.set(new RlsScope(fixture.tenantId(), fixture.projectId()));
        try {
            return new TransactionTemplate(transactionManager).execute(status -> {
                jdbcTemplate.execute("SET LOCAL statement_timeout = '10s'");
                assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
                return action.get();
            });
        } finally {
            RlsScopeContext.clear();
        }
    }

    /**
     * 清理仅限本例 poll 和 Outbox，防止后续扫描领取；独占配置实体随测试容器销毁。
     * 不锁定、修改或删除其他测试的调度行；上下文即使失败也清除。
     */
    @AfterEach
    void clearModbusRuntimeFixtures() throws SQLException {
        TenantContext.clear();
        RlsScopeContext.clear();
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            for (Fixture fixture : fixtures) {
                execute(owner, "DELETE FROM dev_modbus_poll WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_outbox_event WHERE project_id = ?", fixture.projectId());
            }
            owner.commit();
        }
    }

    /** owner 是独立连接，便于检查 APP 提交前不可见与回滚后原事实仍在。 */
    protected Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** 所有夹具写入都参数化并绑定随机身份，避免 SQL 拼接和跨项目副作用。 */
    protected int execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            parameters(statement, arguments);
            return statement.executeUpdate();
        }
    }

    /** 查询必须有一行，不能把未查询到的事实静默当成零。 */
    protected long number(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            parameters(statement, arguments);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    /** 保留 UUID、整数及 decimal 的 JDBC 原生类型。 */
    protected void parameters(PreparedStatement statement, Object... arguments) throws SQLException {
        for (int index = 0; index < arguments.length; index++) {
            statement.setObject(index + 1, arguments[index]);
        }
    }

    /**
     * 独占租户、项目、网关和子设备的事实身份。
     * @param tenantId 租户 @param projectId 项目 @param gatewayId 网关 @param subDeviceId 子设备
     */
    protected record Fixture(UUID tenantId, UUID projectId, UUID gatewayId, UUID subDeviceId) {
        /** 项目键全局唯一；物化行须与控制面路由事实完全相同。 */
        protected String projectKey() { return "poll_" + projectId.toString().replace("-", ""); }
    }

    /**
     * 属性及 Modbus 地址转换事实；测试直接比较存储值，避免用仓储返回值验证自身。
     * @param propertyKey 属性 @param slaveAddress 从站 @param registerAddress 地址 @param functionCode 功能码
     * @param dataType 数据类型 @param byteOrder 字节序 @param scale 缩放 @param offset 偏移 @param pollingIntervalMs 周期
     */
    protected record PointSpec(String propertyKey, int slaveAddress, int registerAddress, String functionCode,
                               String dataType, String byteOrder, BigDecimal scale, BigDecimal offset,
                               int pollingIntervalMs) { }
}
