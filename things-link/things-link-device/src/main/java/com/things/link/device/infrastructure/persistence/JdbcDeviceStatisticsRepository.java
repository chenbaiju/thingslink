package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.DeviceStatistics;
import com.things.link.device.domain.DeviceStatisticsRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** 使用 PostgreSQL 条件聚合读取项目设备事实。 */
@Repository
public class JdbcDeviceStatisticsRepository implements DeviceStatisticsRepository {
    /** JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate JDBC 访问器 */
    public JdbcDeviceStatisticsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public DeviceStatistics summarize(UUID projectId, Instant since) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE status = 'ONLINE') AS online,
                       count(*) FILTER (
                           WHERE status = 'ONLINE' OR last_online_at >= ?
                       ) AS active_since
                  FROM dev_device
                 WHERE project_id = ? AND deleted_at IS NULL
                """, (resultSet, rowNumber) -> new DeviceStatistics(
                        resultSet.getLong("total"), resultSet.getLong("online"),
                        resultSet.getLong("active_since")), Timestamp.from(since), projectId);
    }
    @Override
    public java.util.Set<UUID> existingIds(UUID projectId, java.util.List<UUID> ids) {
        if (ids.isEmpty()) return java.util.Set.of();
        var arguments = new java.util.ArrayList<Object>();
        arguments.add(projectId);
        arguments.addAll(ids);
        return java.util.Set.copyOf(jdbcTemplate.query(
                "SELECT id FROM dev_device WHERE project_id = ? AND deleted_at IS NULL AND id IN ("
                        + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ")",
                (rs, row) -> rs.getObject("id", UUID.class), arguments.toArray()));
    }

}
