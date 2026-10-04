package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.DeviceEventDefinition;
import com.things.link.device.domain.DeviceEventDefinitionRepository;
import com.things.link.device.domain.DevicePropertyDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 以显式 SQL 持久化事件聚合，参数替换依赖应用服务事务保证原子性。 */
@Repository
public class JdbcDeviceEventDefinitionRepository implements DeviceEventDefinitionRepository {
    /** JDBC 访问器。 */ private final JdbcTemplate jdbcTemplate;
    /** @param jdbcTemplate JDBC 访问器 */
    public JdbcDeviceEventDefinitionRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    /** {@inheritDoc} */
    @Override public void create(DeviceEventDefinition value) {
        jdbcTemplate.update("""
                INSERT INTO dev_event_definition
                    (id, tenant_id, project_id, device_type_id, event_key, name, level, description, sort_order)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, value.id(), value.tenantId(), value.projectId(), value.deviceTypeId(), value.eventKey(),
                value.name(), value.level().name(), value.description(), value.sortOrder());
        insertParameters(value);
    }

    /** {@inheritDoc} */
    @Override public List<DeviceEventDefinition> findByDeviceType(UUID projectId, UUID deviceTypeId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_type_id, event_key, name, level, description,
                       sort_order, created_at
                  FROM dev_event_definition
                 WHERE project_id = ? AND device_type_id = ? AND deleted_at IS NULL
                 ORDER BY sort_order, created_at
                """, this::map, projectId, deviceTypeId);
    }

    /** {@inheritDoc} */
    @Override public Optional<DeviceEventDefinition> findById(UUID projectId, UUID deviceTypeId, UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_type_id, event_key, name, level, description,
                       sort_order, created_at
                  FROM dev_event_definition
                 WHERE project_id = ? AND device_type_id = ? AND id = ? AND deleted_at IS NULL
                """, this::map, projectId, deviceTypeId, id).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override public boolean update(DeviceEventDefinition value) {
        int affected = jdbcTemplate.update("""
                UPDATE dev_event_definition
                   SET event_key = ?, name = ?, level = ?, description = ?, sort_order = ?, updated_at = now()
                 WHERE project_id = ? AND device_type_id = ? AND id = ? AND deleted_at IS NULL
                """, value.eventKey(), value.name(), value.level().name(), value.description(), value.sortOrder(),
                value.projectId(), value.deviceTypeId(), value.id());
        if (affected != 1) return false;
        // 参数 ID 没有外部语义，整体替换比逐项差异更新更容易保持 Schema 与顺序一致。
        jdbcTemplate.update("DELETE FROM dev_event_parameter_definition WHERE project_id = ? AND event_id = ?",
                value.projectId(), value.id());
        insertParameters(value);
        return true;
    }

    /** {@inheritDoc} */
    @Override public boolean softDelete(UUID projectId, UUID deviceTypeId, UUID id) {
        return jdbcTemplate.update("""
                UPDATE dev_event_definition SET deleted_at = now(), updated_at = now()
                 WHERE project_id = ? AND device_type_id = ? AND id = ? AND deleted_at IS NULL
                """, projectId, deviceTypeId, id) == 1;
    }

    /** @param value 事件聚合 */
    private void insertParameters(DeviceEventDefinition value) {
        for (DeviceEventDefinition.Parameter parameter : value.parameters()) {
            jdbcTemplate.update("""
                    INSERT INTO dev_event_parameter_definition
                        (id, tenant_id, project_id, event_id, parameter_key, name, data_type,
                         required, enum_options, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, parameter.id(), value.tenantId(), value.projectId(), value.id(), parameter.parameterKey(),
                    parameter.name(), parameter.dataType().name(), parameter.required(), array(parameter.enumOptions()),
                    parameter.sortOrder());
        }
    }

    /** @param values 枚举值 @return JDBC 数组参数 */
    private static String[] array(List<String> values) { return values == null ? null : values.toArray(String[]::new); }

    /** @param resultSet 事件行 @param rowNum 行号 @return 带参数的聚合 */
    private DeviceEventDefinition map(ResultSet resultSet, int rowNum) throws SQLException {
        UUID eventId = resultSet.getObject("id", UUID.class);
        UUID projectId = resultSet.getObject("project_id", UUID.class);
        List<DeviceEventDefinition.Parameter> parameters = jdbcTemplate.query("""
                SELECT id, parameter_key, name, data_type, required, enum_options, sort_order
                  FROM dev_event_parameter_definition
                 WHERE project_id = ? AND event_id = ? ORDER BY sort_order, created_at
                """, this::mapParameter, projectId, eventId);
        return new DeviceEventDefinition(eventId, resultSet.getObject("tenant_id", UUID.class), projectId,
                resultSet.getObject("device_type_id", UUID.class), resultSet.getString("event_key"),
                resultSet.getString("name"), DeviceEventDefinition.Level.valueOf(resultSet.getString("level")),
                resultSet.getString("description"), resultSet.getInt("sort_order"), parameters,
                resultSet.getTimestamp("created_at").toInstant());
    }

    /** @param resultSet 参数行 @param rowNum 行号 @return 参数定义 */
    private DeviceEventDefinition.Parameter mapParameter(ResultSet resultSet, int rowNum) throws SQLException {
        Array options = resultSet.getArray("enum_options");
        List<String> values = options == null ? null : Arrays.asList((String[]) options.getArray());
        return new DeviceEventDefinition.Parameter(resultSet.getObject("id", UUID.class),
                resultSet.getString("parameter_key"), resultSet.getString("name"),
                DevicePropertyDefinition.DataType.valueOf(resultSet.getString("data_type")),
                resultSet.getBoolean("required"), values, resultSet.getInt("sort_order"));
    }
}
