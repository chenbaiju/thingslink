package com.things.link.project.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/** 从 project 自有 append-only 表贡献 REST API 日用量绝对事实。 */
@Component
public class ProjectUsageFactContributor implements DailyUsageContributor {
    /** 当前对账事务已设置 tenant/project RLS 的 JDBC 入口。 */
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate 当前对账事务 JDBC 入口 */
    public ProjectUsageFactContributor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public List<DailyUsageValue> calculate(DailyUsageScope scope, LocalDate usageDate) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_usage_fact
                 WHERE tenant_id = ? AND project_id = ? AND usage_date = ?
                   AND metric = 'REST_API_CALL'
                """, Long.class, scope.tenantId(), scope.projectId(), usageDate);
        return List.of(new DailyUsageValue(QuotaMetric.REST_API_CALL, count == null ? 0L : count));
    }
}
