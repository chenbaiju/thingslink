package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.DeviceRuntimeRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 以普通项目RLS和单语句请求表读取看板运行设备事实。 */
@Repository
public class JdbcDeviceRuntimeRepository implements DeviceRuntimeRepository {

    /** 当前事务内的数据访问模板。 */
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate 服从当前事务和RLS范围的JDBC模板 */
    public JdbcDeviceRuntimeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override public int lockDevices(UUID projectId,List<UUID> ids){
        return jdbcTemplate.query("SELECT id FROM dev_device WHERE project_id=? AND id=ANY(?::uuid[]) AND deleted_at IS NULL ORDER BY id FOR SHARE",(r,n)->r.getObject(1,UUID.class),projectId,"{"+ids.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(","))+"}").size();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<DeviceFact> inspect(UUID projectId, List<DeviceRequest> requests) {
        SqlRequest sql = deviceRequestTable(requests);
        return jdbcTemplate.query("""
                WITH requested(device_id, expected_model_version_id, position) AS (VALUES %s)
                SELECT r.device_id, r.position, d.id IS NOT NULL AS visible,
                       d.thing_model_version_id, d.name, d.status, d.last_online_at
                  FROM requested r
                  LEFT JOIN dev_device d
                    ON d.project_id = ? AND d.id = r.device_id AND d.deleted_at IS NULL
                 ORDER BY r.position
                """.formatted(sql.placeholders()), this::deviceFact, arguments(sql.arguments(), projectId));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<DeviceModelFact> snapshot(UUID projectId, List<DeviceRequest> requests) {
        SqlRequest sql = deviceRequestTable(requests);
        return jdbcTemplate.query("""
                WITH requested(device_id, expected_model_version_id, position) AS (VALUES %s)
                SELECT r.device_id, r.position, d.id IS NOT NULL AS visible,
                       d.thing_model_version_id, d.name, d.status, d.last_online_at,
                       v.model_snapshot::text AS model_snapshot, v.digest_algorithm,
                       v.schema_digest AS stored_digest,
                       encode(digest(convert_to(v.model_snapshot::text, 'UTF8'), 'sha256'), 'hex') AS calculated_digest,
                       v.schema_profile
                  FROM requested r
                  LEFT JOIN dev_device d
                    ON d.project_id = ? AND d.id = r.device_id AND d.deleted_at IS NULL
                  LEFT JOIN dev_thing_model_version v
                    ON v.project_id = d.project_id AND v.id = d.thing_model_version_id
                   AND v.id = r.expected_model_version_id
                 ORDER BY r.position
                """.formatted(sql.placeholders()), (result, row) -> new DeviceModelFact(
                deviceFact(result, row), result.getString("model_snapshot"), result.getString("digest_algorithm"),
                result.getString("stored_digest"), result.getString("calculated_digest"),
                result.getString("schema_profile")), arguments(sql.arguments(), projectId));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<CurrentValueFact> currentValues(UUID projectId, List<PropertyRequest> requests) {
        SqlRequest sql = propertyRequestTable(requests);
        return jdbcTemplate.query("""
                WITH requested(device_id, expected_model_version_id, property_key,
                               device_position, property_position) AS (VALUES %s)
                SELECT r.device_id, r.device_position AS position, r.property_key, r.property_position,
                       d.id IS NOT NULL AS visible, d.thing_model_version_id, d.name, d.status, d.last_online_at,
                       v.model_snapshot::text AS model_snapshot, v.digest_algorithm,
                       v.schema_digest AS stored_digest,
                       encode(digest(convert_to(v.model_snapshot::text, 'UTF8'), 'sha256'), 'hex') AS calculated_digest,
                       v.schema_profile,
                       COALESCE(jsonb_exists(s.reported, r.property_key), false) AS value_present,
                       (s.reported -> r.property_key)::text AS value_json,
                       s.reported_at ->> r.property_key AS occurred_at_text,
                       s.reported_model_version ->> r.property_key AS reported_model_version_text
                  FROM requested r
                  LEFT JOIN dev_device d
                    ON d.project_id = ? AND d.id = r.device_id AND d.deleted_at IS NULL
                  LEFT JOIN dev_thing_model_version v
                    ON v.project_id = d.project_id AND v.id = d.thing_model_version_id
                   AND v.id = r.expected_model_version_id
                  LEFT JOIN dev_shadow s ON s.project_id = d.project_id AND s.device_id = d.id
                 ORDER BY r.device_position, r.property_position
                """.formatted(sql.placeholders()), (result, row) -> new CurrentValueFact(
                deviceFact(result, row), result.getString("property_key"), result.getInt("property_position"),
                result.getString("model_snapshot"), result.getString("digest_algorithm"),
                result.getString("stored_digest"), result.getString("calculated_digest"),
                result.getString("schema_profile"), result.getBoolean("value_present"),
                result.getString("value_json"), result.getString("occurred_at_text"),
                result.getString("reported_model_version_text")), arguments(sql.arguments(), projectId));
    }

    /** 将查询位置和可见设备字段映射为不含模型正文的事实。 */
    private DeviceFact deviceFact(ResultSet result, int row) throws SQLException {
        return new DeviceFact(result.getObject("device_id", UUID.class), result.getInt("position"),
                result.getBoolean("visible"), result.getObject("thing_model_version_id", UUID.class),
                result.getString("name"), result.getString("status"),
                result.getTimestamp("last_online_at") == null ? null
                        : result.getTimestamp("last_online_at").toInstant());
    }

    /** 生成有界设备VALUES表；SQL结构只随已验证数量变化，值仍全部走预编译参数。 */
    private static SqlRequest deviceRequestTable(List<DeviceRequest> requests) {
        List<String> rows = new ArrayList<>();
        List<Object> arguments = new ArrayList<>();
        for (DeviceRequest request : requests) {
            rows.add("(?::uuid, ?::uuid, ?::integer)");
            arguments.add(request.deviceId());
            arguments.add(request.expectedModelVersionId());
            arguments.add(request.position());
        }
        return new SqlRequest(String.join(",", rows), arguments);
    }

    /** 生成精确(device,key)VALUES表，禁止退化为设备×属性笛卡尔积。 */
    private static SqlRequest propertyRequestTable(List<PropertyRequest> requests) {
        List<String> rows = new ArrayList<>();
        List<Object> arguments = new ArrayList<>();
        for (PropertyRequest request : requests) {
            rows.add("(?::uuid, ?::uuid, ?::varchar, ?::integer, ?::integer)");
            arguments.add(request.deviceId());
            arguments.add(request.expectedModelVersionId());
            arguments.add(request.propertyKey());
            arguments.add(request.devicePosition());
            arguments.add(request.propertyPosition());
        }
        return new SqlRequest(String.join(",", rows), arguments);
    }

    /** 把项目条件追加到请求表参数末尾，保持占位顺序与SQL一致。 */
    private static Object[] arguments(List<Object> requestArguments, UUID projectId) {
        List<Object> result = new ArrayList<>(requestArguments);
        result.add(projectId);
        return result.toArray();
    }

    /** @param placeholders VALUES占位行 @param arguments 预编译参数 */
    private record SqlRequest(String placeholders, List<Object> arguments) { }
}
