package com.things.link.telemetry.infrastructure.persistence;

import com.things.link.telemetry.domain.OverviewTrendRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PostgreSQL 项目统计仓储；显式归属与 RLS 共同限制读取范围。 */
@Repository
public class JdbcOverviewTrendRepository implements OverviewTrendRepository {
    /** 项目范围 JDBC 连接。 */
    private final JdbcTemplate jdbc;
    /** @param jdbc 项目范围 JDBC 连接 */
    public JdbcOverviewTrendRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** {@inheritDoc} */
    @Override
    public String source(UUID tenantId, UUID projectId, Instant from, Instant to) {
        var sources = jdbc.queryForList("""
            SELECT source FROM ts_project_overview_sample
             WHERE tenant_id = ? AND project_id = ? AND sampled_at >= ? AND sampled_at < ?
             GROUP BY source ORDER BY source
            """, String.class, tenantId, projectId, Timestamp.from(from), Timestamp.from(to));
        return sources.contains("OBSERVED") ? "OBSERVED" : sources.contains("SIMULATED") ? "SIMULATED" : "NONE";
    }
    /** {@inheritDoc} */
    @Override
    public List<Bucket> aggregate(UUID tenantId, UUID projectId, Instant from, Instant to, int stepHours, String source) {
        return jdbc.query("""
            SELECT metric, floor(extract(epoch FROM (sampled_at - ?::timestamptz)) / ?)::integer AS bucket,
                   CASE WHEN metric IN ('device.active', 'alarm.normal', 'alarm.active', 'alarm.pending')
                        THEN (array_agg(value ORDER BY sampled_at DESC))[1]
                        ELSE sum(value)::bigint END AS value,
                   count(*)::integer AS samples
              FROM ts_project_overview_sample
             WHERE tenant_id = ? AND project_id = ? AND source = ? AND sampled_at >= ? AND sampled_at < ?
             GROUP BY metric, bucket ORDER BY bucket, metric
            """, (rs, n) -> new Bucket(rs.getString("metric"), rs.getInt("bucket"), rs.getLong("value"), rs.getInt("samples")),
                Timestamp.from(from), stepHours * 3600, tenantId, projectId, source, Timestamp.from(from), Timestamp.from(to));
    }
}
