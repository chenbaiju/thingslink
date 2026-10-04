package com.things.link.rule.infrastructure.persistence;

import com.things.link.project.application.DailyUsageScope;
import com.things.link.project.application.QuotaMetric;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 规则日志贡献者固定返回生产执行次数与保守 CPU 毫秒两个权威指标。 */
class RuleExecutionDailyUsageContributorTests {

    /** 聚合必须使用 owner tenant、项目与 UTC 日，不接受调用方自由时间窗口。 */
    @Test
    @SuppressWarnings("unchecked")
    void contributesExecutionAttemptsAndConservativeCpuMillis() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DailyUsageScope scope = new DailyUsageScope(UUID.randomUUID(), UUID.randomUUID());
        LocalDate date = LocalDate.of(2026, 8, 13);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class),
                eq(scope.tenantId()), eq(scope.projectId()), eq(date), eq(date)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    var resultSet = mock(java.sql.ResultSet.class);
                    when(resultSet.getLong("execution_count")).thenReturn(3L);
                    when(resultSet.getLong("conservative_cpu_millis")).thenReturn(17L);
                    return mapper.mapRow(resultSet, 0);
                });

        assertThat(new RuleExecutionDailyUsageContributor(jdbc).calculate(scope, date))
                .extracting(value -> value.metric(), value -> value.usedValue())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(QuotaMetric.SCRIPT_EXECUTION, 3L),
                        org.assertj.core.groups.Tuple.tuple(QuotaMetric.SCRIPT_CPU_MILLIS, 17L));
    }
}
