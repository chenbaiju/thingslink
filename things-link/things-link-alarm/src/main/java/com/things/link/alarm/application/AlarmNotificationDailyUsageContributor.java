package com.things.link.alarm.application;

import com.things.link.project.application.DailyUsageContributor;
import com.things.link.project.application.DailyUsageScope;
import com.things.link.project.application.DailyUsageValue;
import com.things.link.project.application.QuotaMetric;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/** 从唯一投递意图事实贡献通知日用量，Kafka/Outbox 重试不会重复计数。 */
@Component
public class AlarmNotificationDailyUsageContributor implements DailyUsageContributor {
    /** 当前对账事务已绑定 tenant/project RLS 的 JDBC 入口。 */
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate 事务感知 JDBC 入口 */
    public AlarmNotificationDailyUsageContributor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<DailyUsageValue> calculate(DailyUsageScope scope, LocalDate usageDate) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM alarm_notification_delivery
                 WHERE tenant_id = ? AND project_id = ?
                   AND created_at >= (?::date::timestamp AT TIME ZONE 'UTC')
                   AND created_at < ((?::date + interval '1 day') AT TIME ZONE 'UTC')
                """, Long.class, scope.tenantId(), scope.projectId(), usageDate, usageDate);
        return List.of(new DailyUsageValue(
                QuotaMetric.NOTIFICATION_DELIVERY, count == null ? 0L : count));
    }
}
