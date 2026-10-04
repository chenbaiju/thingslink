package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.ModbusPointMapping;
import com.things.link.device.domain.ModbusPointMappingRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 使用显式 SQL 持久化 Modbus 点位，所有查询同时限定项目与网关设备。 */
@Repository
public class JdbcModbusPointMappingRepository implements ModbusPointMappingRepository {
    /** JDBC 执行入口。 */ private final JdbcTemplate jdbcTemplate;
    /** @param jdbcTemplate 已接入 RLS 上下文的数据访问模板 */
    public JdbcModbusPointMappingRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    /** {@inheritDoc} */
    @Override public void create(ModbusPointMapping point) {
        jdbcTemplate.update("""
                INSERT INTO dev_modbus_point_mapping
                    (id, tenant_id, project_id, device_id, sub_device_id, property_key, slave_address,
                     function_code, register_address, data_type, byte_order, scale, "offset",
                     polling_interval_ms, version, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                """, point.id(), point.tenantId(), point.projectId(), point.deviceId(), point.subDeviceId(),
                point.propertyKey(), point.slaveAddress(), point.functionCode().name(), point.registerAddress(),
                point.dataType().name(), point.byteOrder().name(), point.scale(), point.offset(),
                point.pollingIntervalMs(), point.version(), point.status().name());
    }

    /** {@inheritDoc} */
    @Override public List<ModbusPointMapping> findByDevice(UUID projectId, UUID deviceId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_id, sub_device_id, property_key, slave_address,
                       function_code, register_address, data_type, byte_order, scale, "offset",
                       polling_interval_ms, version, status, created_at, updated_at
                  FROM dev_modbus_point_mapping
                 WHERE project_id = ? AND device_id = ?
                 ORDER BY slave_address, function_code, register_address, id
                """, this::map, projectId, deviceId);
    }

    /** {@inheritDoc} */
    @Override public List<ModbusPointMapping> findPublished(UUID projectId, UUID deviceId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_id, sub_device_id, property_key, slave_address,
                       function_code, register_address, data_type, byte_order, scale, "offset",
                       polling_interval_ms, version, status, created_at, updated_at
                  FROM dev_modbus_point_mapping
                 WHERE project_id = ? AND device_id = ? AND status = 'PUBLISHED'
                   AND version = (SELECT MAX(m.version) FROM dev_modbus_point_mapping m
                                   WHERE m.project_id = ? AND m.device_id = ? AND m.status = 'PUBLISHED')
                 ORDER BY slave_address, function_code, register_address, id
                """, this::map, projectId, deviceId, projectId, deviceId);
    }

    /** {@inheritDoc} */
    @Override public Optional<ModbusPointMapping> findById(UUID projectId, UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_id, sub_device_id, property_key, slave_address,
                       function_code, register_address, data_type, byte_order, scale, "offset",
                       polling_interval_ms, version, status, created_at, updated_at
                  FROM dev_modbus_point_mapping
                 WHERE project_id = ? AND id = ?
                """, this::map, projectId, id).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override public boolean update(ModbusPointMapping point) {
        return jdbcTemplate.update("""
                UPDATE dev_modbus_point_mapping
                   SET sub_device_id = ?, property_key = ?, slave_address = ?, function_code = ?,
                       register_address = ?, data_type = ?, byte_order = ?, scale = ?, "offset" = ?,
                       polling_interval_ms = ?, updated_at = now()
                 WHERE project_id = ? AND id = ? AND status = 'DRAFT'
                """, point.subDeviceId(), point.propertyKey(), point.slaveAddress(), point.functionCode().name(),
                point.registerAddress(), point.dataType().name(), point.byteOrder().name(), point.scale(),
                point.offset(), point.pollingIntervalMs(), point.projectId(), point.id()) == 1;
    }

    /** {@inheritDoc} */
    @Override public boolean delete(UUID projectId, UUID id) {
        return jdbcTemplate.update("""
                DELETE FROM dev_modbus_point_mapping
                 WHERE project_id = ? AND id = ? AND status = 'DRAFT'
                """, projectId, id) == 1;
    }

    /** {@inheritDoc} */
    @Override public boolean publish(UUID projectId, UUID deviceId) {
        return jdbcTemplate.update("""
                UPDATE dev_modbus_point_mapping
                   SET status = 'PUBLISHED',
                       version = (SELECT COALESCE(MAX(m.version), 0) + 1
                                    FROM dev_modbus_point_mapping m
                                   WHERE m.project_id = ? AND m.device_id = ? AND m.status = 'PUBLISHED'),
                       updated_at = now()
                 WHERE project_id = ? AND device_id = ? AND status = 'DRAFT'
                """, projectId, deviceId, projectId, deviceId) >= 1;
    }

    /** @param rs 查询结果 @param rowNum 行号 @return 领域对象 */
    private ModbusPointMapping map(ResultSet rs, int rowNum) throws SQLException {
        return new ModbusPointMapping(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("device_id", UUID.class),
                rs.getObject("sub_device_id", UUID.class), rs.getString("property_key"),
                rs.getInt("slave_address"), ModbusPointMapping.FunctionCode.valueOf(rs.getString("function_code")),
                rs.getInt("register_address"), ModbusPointMapping.DataType.valueOf(rs.getString("data_type")),
                ModbusPointMapping.ByteOrder.valueOf(rs.getString("byte_order")), rs.getBigDecimal("scale"),
                rs.getBigDecimal("offset"), rs.getInt("polling_interval_ms"), rs.getInt("version"),
                ModbusPointMapping.Status.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
