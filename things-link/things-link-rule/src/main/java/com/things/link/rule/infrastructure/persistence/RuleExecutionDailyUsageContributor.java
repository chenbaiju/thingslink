package com.things.link.rule.infrastructure.persistence;

import com.things.link.project.application.DailyUsageContributor;
import com.things.link.project.application.DailyUsageScope;
import com.things.link.project.application.DailyUsageValue;
import com.things.link.project.application.QuotaMetric;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 从生产规则执行日志重算脚本执行次数与保守 CPU 毫秒日用量。
 *
 * <p>每个 attempt 是一份真实沙箱资源消耗，因此 retry 也计费，重复完成通知则由日志唯一键吸收。当前 Worker 协议只返回
 * 有界调用耗时而没有独立 guest CPU 数值；在协议扩展前，{@code duration_millis} 作为不低估资源占用的保守计量口径，
 * 不能被解释为操作系统采样的精确 CPU 时间。</p>
 */
@Component
public class RuleExecutionDailyUsageContributor implements DailyUsageContributor {

    /** 当前对账事务已绑定 tenant/project RLS 的 JDBC 入口。 */
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate 事务感知 JDBC 入口 */
    public RuleExecutionDailyUsageContributor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<DailyUsageValue> calculate(DailyUsageScope scope, LocalDate usageDate) {
        UsageRow usage = jdbcTemplate.queryForObject("""
                SELECT count(*) AS execution_count,
                       coalesce(sum(duration_millis), 0) AS conservative_cpu_millis
                  FROM rule_execution_log
                 WHERE tenant_id = ? AND project_id = ?
                   AND created_at >= (?::date::timestamp AT TIME ZONE 'UTC')
                   AND created_at < ((?::date + interval '1 day') AT TIME ZONE 'UTC')
                """, (resultSet, rowNumber) -> new UsageRow(
                        resultSet.getLong("execution_count"),
                        resultSet.getLong("conservative_cpu_millis")),
                scope.tenantId(), scope.projectId(), usageDate, usageDate);
        if (usage == null) {
            throw new IllegalStateException("规则执行日用量查询未返回聚合行");
        }
        return List.of(
                new DailyUsageValue(QuotaMetric.SCRIPT_EXECUTION, usage.executionCount()),
                new DailyUsageValue(QuotaMetric.SCRIPT_CPU_MILLIS, usage.conservativeCpuMillis()));
    }

    /** @param executionCount 当日生产执行 attempt 数 @param conservativeCpuMillis 有界调用耗时之和 */
    private record UsageRow(long executionCount, long conservativeCpuMillis) {
    }
}
