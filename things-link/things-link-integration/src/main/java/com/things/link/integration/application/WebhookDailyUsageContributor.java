package com.things.link.integration.application;

import com.things.link.project.application.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.List;

/** 原始投递意图仅计数一次，不按物理尝试或恢复轮次重复计数。 */
@Component
public class WebhookDailyUsageContributor implements DailyUsageContributor {
    private final JdbcTemplate jdbc;
    public WebhookDailyUsageContributor(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public List<DailyUsageValue> calculate(DailyUsageScope scope,LocalDate date){
        long count=jdbc.queryForObject("SELECT count(*) FROM integ_webhook_delivery WHERE tenant_id=? AND project_id=? AND created_at >= (?::date::timestamp AT TIME ZONE 'UTC') AND created_at < ((?::date+interval '1 day') AT TIME ZONE 'UTC')",Long.class,scope.tenantId(),scope.projectId(),date,date);
        return List.of(new DailyUsageValue(QuotaMetric.NOTIFICATION_DELIVERY,count));
    }
}
