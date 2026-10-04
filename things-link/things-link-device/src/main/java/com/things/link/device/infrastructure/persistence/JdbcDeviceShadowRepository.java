package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.DeviceShadow;
import com.things.link.device.domain.DeviceShadowRepository;
import com.things.link.device.domain.DeviceShadowSnapshot;
import com.things.link.device.domain.DeviceReportedRevisionSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** 以显式 SQL 持久化设备影子。 */
@Repository
public class JdbcDeviceShadowRepository implements DeviceShadowRepository {
    /** 单行写入沿用位置参数，保持摄入热路径 SQL 紧凑。 */
    private final JdbcTemplate jdbcTemplate;
    /** 批量回源使用命名集合参数，禁止按设备循环访问 PostgreSQL。 */
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    /** @param jdbcTemplate JDBC 模板 @param namedJdbcTemplate 支持集合参数的 JDBC 模板 */
    public JdbcDeviceShadowRepository(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = namedJdbcTemplate;
    }

    @Override public Optional<DeviceShadow> findByDevice(UUID projectId, UUID deviceId) {
        return jdbcTemplate.query("""
                SELECT device_id, tenant_id, project_id, desired, reported, version, updated_at
                  FROM dev_shadow WHERE project_id = ? AND device_id = ?
                """, this::map, projectId, deviceId).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override public List<DeviceShadowSnapshot> findSnapshots(UUID projectId, Collection<UUID> deviceIds) {
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return namedJdbcTemplate.query("""
                SELECT device_id, reported, reported_at, version, reported_revisions, reported_model_version
                  FROM dev_shadow
                 WHERE project_id = :projectId AND device_id IN (:deviceIds)
                """, new MapSqlParameterSource("projectId", projectId).addValue("deviceIds", deviceIds),
                (rs, rowNum) -> new DeviceShadowSnapshot(rs.getObject("device_id", UUID.class),
                        rs.getString("reported"), rs.getString("reported_at"), rs.getInt("version"),
                        rs.getString("reported_revisions"), rs.getString("reported_model_version")));
    }

    /** 只读取序号对象，普通连接继续受项目条件和RLS双重限定。 */
    @Override
    public List<DeviceReportedRevisionSnapshot> findReportedRevisions(UUID projectId, Collection<UUID> deviceIds) {
        if (deviceIds.isEmpty()) return List.of();
        return namedJdbcTemplate.query("""
                SELECT device_id, reported_revisions FROM dev_shadow
                 WHERE project_id = :projectId AND device_id IN (:deviceIds)
                """, new MapSqlParameterSource("projectId", projectId).addValue("deviceIds", deviceIds),
                (rs, rowNum) -> new DeviceReportedRevisionSnapshot(rs.getObject("device_id", UUID.class),
                        rs.getString("reported_revisions")));
    }

    @Override public boolean createIfAbsent(DeviceShadow shadow) {
        return jdbcTemplate.update("""
                INSERT INTO dev_shadow (device_id, tenant_id, project_id, desired, reported, version)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?)
                ON CONFLICT (device_id) DO NOTHING
                """, shadow.deviceId(), shadow.tenantId(), shadow.projectId(),
                shadow.desired(), shadow.reported(), shadow.version()) == 1;
    }

    @Override public boolean updateDesired(UUID projectId, UUID deviceId, String desired, int version) {
        return jdbcTemplate.update("""
                UPDATE dev_shadow SET desired = ?::jsonb, version = version + 1, updated_at = now()
                 WHERE project_id = ? AND device_id = ? AND version = ?
                """, desired, projectId, deviceId, version) == 1;
    }

    /** {@inheritDoc} */
    @Override public OptionalLong updateReportedPropertyIfNewer(UUID projectId, UUID deviceId, String propertyKey,
                                                           String jsonValue, Instant occurredAt,
                                                           UUID thingModelVersionId) {
        String jsonTimestamp = "\"" + occurredAt + "\"";
        String jsonVersion = "\"" + thingModelVersionId + "\"";
        List<Long> accepted = jdbcTemplate.query("""
                UPDATE dev_shadow
                   SET reported_sequence = reported_sequence + 1,
                       reported_revisions = jsonb_set(reported_revisions, ARRAY[?]::text[],
                               to_jsonb((reported_sequence + 1)::text), true),
                       reported = jsonb_set(COALESCE(reported, '{}'::jsonb), ARRAY[?]::text[], ?::jsonb, true),
                       reported_at = jsonb_set(COALESCE(reported_at, '{}'::jsonb), ARRAY[?]::text[], ?::jsonb, true),
                       reported_model_version = jsonb_set(COALESCE(reported_model_version, '{}'::jsonb),
                                                          ARRAY[?]::text[], ?::jsonb, true),
                       updated_at = now()
                 WHERE device_id = ? AND project_id = ?
                   AND ((reported_at ->> ?) IS NULL OR (reported_at ->> ?)::timestamptz <= ?)
                RETURNING reported_sequence
                """, (rs, rowNum) -> rs.getLong(1), propertyKey, propertyKey, jsonValue, propertyKey, jsonTimestamp, propertyKey, jsonVersion,
                deviceId, projectId,
                propertyKey, propertyKey, Timestamp.from(occurredAt));
        return accepted.isEmpty() ? OptionalLong.empty() : OptionalLong.of(accepted.getFirst());
    }

    private DeviceShadow map(ResultSet rs, int rowNum) throws SQLException {
        return new DeviceShadow(rs.getObject("device_id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("desired"), rs.getString("reported"), rs.getInt("version"),
                rs.getTimestamp("updated_at").toInstant());
    }
}
