package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.DeviceCommandDefinition;
import com.things.link.device.domain.DeviceCommandDefinitionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 以显式 SQL 持久化命令定义，JSON Schema 字段直接读写 jsonb 列。 */
@Repository
public class JdbcDeviceCommandDefinitionRepository implements DeviceCommandDefinitionRepository {
    /** JDBC 访问器。 */ private final JdbcTemplate jdbcTemplate;
    /** @param jdbcTemplate JDBC 访问器 */
    public JdbcDeviceCommandDefinitionRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public void create(DeviceCommandDefinition value) {
        jdbcTemplate.update("""
                INSERT INTO dev_command_definition
                    (id, tenant_id, project_id, device_type_id, command_key, name, description,
                     input_schema, output_schema, timeout_seconds, sort_order)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?,
                        ?::jsonb, ?::jsonb, ?, ?)
                """, value.id(), value.tenantId(), value.projectId(), value.deviceTypeId(),
                value.commandKey(), value.name(), value.description(),
                value.inputSchema(), value.outputSchema(), value.timeoutSeconds(), value.sortOrder());
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public List<DeviceCommandDefinition> findByDeviceType(UUID projectId, UUID deviceTypeId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_type_id, command_key, name, description,
                       input_schema, output_schema, timeout_seconds, sort_order, created_at
                  FROM dev_command_definition
                 WHERE project_id = ? AND device_type_id = ? AND deleted_at IS NULL
                 ORDER BY sort_order, created_at
                """, this::map, projectId, deviceTypeId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<DeviceCommandDefinition> findById(UUID projectId, UUID deviceTypeId, UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_type_id, command_key, name, description,
                       input_schema, output_schema, timeout_seconds, sort_order, created_at
                  FROM dev_command_definition
                 WHERE project_id = ? AND device_type_id = ? AND id = ? AND deleted_at IS NULL
                """, this::map, projectId, deviceTypeId, id).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<DeviceCommandDefinition> findByCommandKey(
            UUID projectId, UUID deviceTypeId, String commandKey) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_type_id, command_key, name, description,
                       input_schema, output_schema, timeout_seconds, sort_order, created_at
                  FROM dev_command_definition
                 WHERE project_id = ? AND device_type_id = ? AND command_key = ? AND deleted_at IS NULL
                """, this::map, projectId, deviceTypeId, commandKey).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean update(DeviceCommandDefinition value) {
        return jdbcTemplate.update("""
                UPDATE dev_command_definition
                   SET command_key = ?, name = ?, description = ?,
                       input_schema = ?::jsonb, output_schema = ?::jsonb,
                       timeout_seconds = ?, sort_order = ?, updated_at = now()
                 WHERE project_id = ? AND device_type_id = ? AND id = ? AND deleted_at IS NULL
                """, value.commandKey(), value.name(), value.description(),
                value.inputSchema(), value.outputSchema(), value.timeoutSeconds(), value.sortOrder(),
                value.projectId(), value.deviceTypeId(), value.id()) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean softDelete(UUID projectId, UUID deviceTypeId, UUID id) {
        return jdbcTemplate.update("""
                UPDATE dev_command_definition SET deleted_at = now(), updated_at = now()
                 WHERE project_id = ? AND device_type_id = ? AND id = ? AND deleted_at IS NULL
                """, projectId, deviceTypeId, id) == 1;
    }

    /** @param resultSet 命令行 @param rowNum 行号 @return 命令定义聚合 */
    private DeviceCommandDefinition map(ResultSet resultSet, int rowNum) throws SQLException {
        return new DeviceCommandDefinition(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("tenant_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getObject("device_type_id", UUID.class),
                resultSet.getString("command_key"),
                resultSet.getString("name"),
                resultSet.getString("description"),
                resultSet.getString("input_schema"),
                resultSet.getString("output_schema"),
                resultSet.getInt("timeout_seconds"),
                resultSet.getInt("sort_order"),
                resultSet.getTimestamp("created_at").toInstant());
    }
}
