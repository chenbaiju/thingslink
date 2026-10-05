package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.DevicePropertyDefinition;
import com.things.link.device.domain.DevicePropertyDefinitionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 使用显式 SQL 持久化属性定义，避免 ORM 隐式查询遗漏项目隔离条件。 */
@Repository
public class JdbcDevicePropertyDefinitionRepository implements DevicePropertyDefinitionRepository {
    /** JDBC 访问器。 */ private final JdbcTemplate jdbcTemplate;
    /** @param jdbcTemplate JDBC 访问器 */
    public JdbcDevicePropertyDefinitionRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public void create(DevicePropertyDefinition value) {
        jdbcTemplate.update("""
                INSERT INTO dev_property_definition
                    (id, tenant_id, project_id, device_type_id, property_key, name, access_type, data_type,
                     unit, decimal_places, minimum_value, maximum_value, enum_options, on_label, off_label, schema, sort_order)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, value.id(), value.tenantId(), value.projectId(), value.deviceTypeId(), value.propertyKey(),
                value.name(), value.accessType().name(), value.dataType().name(), value.unit(), value.decimalPlaces(),
                value.minimumValue(), value.maximumValue(), array(value.enumOptions()), value.onLabel(), value.offLabel(),
                value.schema(), value.sortOrder());
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public List<DevicePropertyDefinition> findByDeviceType(UUID projectId, UUID deviceTypeId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_type_id, property_key, name, access_type, data_type,
                       unit, decimal_places, minimum_value, maximum_value, enum_options, on_label, off_label,
                       schema, sort_order, created_at
                  FROM dev_property_definition
                 WHERE project_id = ? AND device_type_id = ? AND deleted_at IS NULL
                 ORDER BY sort_order, created_at
                """, this::map, projectId, deviceTypeId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<DevicePropertyDefinition> findById(UUID projectId, UUID deviceTypeId, UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_type_id, property_key, name, access_type, data_type,
                       unit, decimal_places, minimum_value, maximum_value, enum_options, on_label, off_label,
                       schema, sort_order, created_at
                  FROM dev_property_definition
                 WHERE project_id = ? AND device_type_id = ? AND id = ? AND deleted_at IS NULL
                """, this::map, projectId, deviceTypeId, id).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<DevicePropertyDefinition> findByPropertyKey(
            UUID projectId, UUID deviceTypeId, String propertyKey) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_type_id, property_key, name, access_type, data_type,
                       unit, decimal_places, minimum_value, maximum_value, enum_options, on_label, off_label,
                       schema, sort_order, created_at
                  FROM dev_property_definition
                 WHERE project_id = ? AND device_type_id = ? AND property_key = ? AND deleted_at IS NULL
                """, this::map, projectId, deviceTypeId, propertyKey).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean update(DevicePropertyDefinition value) {
        return jdbcTemplate.update("""
                UPDATE dev_property_definition
                   SET property_key = ?, name = ?, access_type = ?, data_type = ?, unit = ?, decimal_places = ?,
                       minimum_value = ?, maximum_value = ?, enum_options = ?, on_label = ?, off_label = ?,
                       schema = ?::jsonb, sort_order = ?, updated_at = now()
                 WHERE project_id = ? AND device_type_id = ? AND id = ? AND deleted_at IS NULL
                """, value.propertyKey(), value.name(), value.accessType().name(), value.dataType().name(),
                value.unit(), value.decimalPlaces(), value.minimumValue(), value.maximumValue(),
                array(value.enumOptions()), value.onLabel(), value.offLabel(), value.schema(), value.sortOrder(), value.projectId(),
                value.deviceTypeId(), value.id()) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean softDelete(UUID projectId, UUID deviceTypeId, UUID id) {
        return jdbcTemplate.update("""
                UPDATE dev_property_definition SET deleted_at = now(), updated_at = now()
                 WHERE project_id = ? AND device_type_id = ? AND id = ? AND deleted_at IS NULL
                """, projectId, deviceTypeId, id) == 1;
    }

    /** 把领域列表交给 PostgreSQL JDBC 驱动构造成 varchar 数组。 @param values 枚举值 @return SQL 参数 */
    private static String[] array(List<String> values) {
        return values == null ? null : values.toArray(String[]::new);
    }

    /** @param resultSet 查询结果 @param rowNum 行号 @return 领域对象 */
    private DevicePropertyDefinition map(ResultSet resultSet, int rowNum) throws SQLException {
        Array options = resultSet.getArray("enum_options");
        List<String> optionList = options == null ? null : Arrays.asList((String[]) options.getArray());
        return new DevicePropertyDefinition(resultSet.getObject("id", UUID.class),
                resultSet.getObject("tenant_id", UUID.class), resultSet.getObject("project_id", UUID.class),
                resultSet.getObject("device_type_id", UUID.class), resultSet.getString("property_key"),
                resultSet.getString("name"), DevicePropertyDefinition.AccessType.valueOf(resultSet.getString("access_type")),
                DevicePropertyDefinition.DataType.valueOf(resultSet.getString("data_type")), resultSet.getString("unit"),
                resultSet.getObject("decimal_places", Integer.class), resultSet.getBigDecimal("minimum_value"),
                resultSet.getBigDecimal("maximum_value"), optionList, resultSet.getString("on_label"),
                resultSet.getString("off_label"), resultSet.getString("schema"), resultSet.getInt("sort_order"),
                resultSet.getTimestamp("created_at").toInstant());
    }
}
