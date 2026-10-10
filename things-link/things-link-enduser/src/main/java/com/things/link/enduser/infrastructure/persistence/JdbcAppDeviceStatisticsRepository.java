package com.things.link.enduser.infrastructure.persistence;

import com.things.link.enduser.domain.AppDeviceStatistics;
import com.things.link.enduser.domain.AppDeviceStatisticsRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** 授权集合与四统计在单条SQL完成；公开视图与底表RLS不代替显式可信范围。 */
@Repository
public class JdbcAppDeviceStatisticsRepository implements AppDeviceStatisticsRepository {
    private final JdbcTemplate jdbc;
    /** @param jdbc 当前事务数据库访问器 */
    public JdbcAppDeviceStatisticsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public AppDeviceStatistics read(UUID tenantId, UUID projectId, UUID appUserId) {
        return jdbc.queryForObject("""
                WITH snapshot AS (SELECT statement_timestamp() AS as_of), visible AS (
                    SELECT device.tenant_id, device.project_id, device.id, device.status, device.last_data_report_at
                      FROM app_user_device binding
                      JOIN dev_device_app_v1 device ON device.tenant_id = binding.tenant_id
                       AND device.project_id = binding.project_id AND device.id = binding.device_id
                     WHERE binding.tenant_id = ? AND binding.project_id = ? AND binding.app_user_id = ?
                       AND binding.status = 'ACTIVE'
                )
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE device.status = 'ONLINE') AS online,
                       count(*) FILTER (WHERE device.last_data_report_at > snapshot.as_of - interval '24 hours'
                                        AND device.last_data_report_at <= snapshot.as_of) AS active24h,
                       count(*) FILTER (WHERE EXISTS (
                           SELECT 1 FROM alarm_app_active_device_v1 alarm
                            WHERE alarm.tenant_id = device.tenant_id AND alarm.project_id = device.project_id
                              AND alarm.device_id = device.id
                       )) AS alarming,
                       (SELECT as_of FROM snapshot) AS as_of
                  FROM visible device CROSS JOIN snapshot
                """, (row, number) -> new AppDeviceStatistics(row.getLong("total"), row.getLong("online"),
                row.getLong("active24h"), row.getLong("alarming"), row.getTimestamp("as_of").toInstant()),
                tenantId, projectId, appUserId);
    }
}
