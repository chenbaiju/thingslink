package com.things.link.integration.application;

import com.things.link.project.application.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.List;

/** Count original delivery intentions once, never physical attempts or recovery rounds. */
@Component
public class WebhookDailyUsageContributor implements DailyUsageContributor {
    private final JdbcTemplate jdbc;
    public WebhookDailyUsageContributor(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public List<DailyUsageValue> calculate(DailyUsageScope scope,LocalDate date){
        long count=jdbc.queryForObject("SELECT count(*) FROM integ_webhook_delivery WHERE tenant_id=? AND project_id=? AND created_at >= (?::date::timestamp AT TIME ZONE 'UTC') AND created_at < ((?::date+interval '1 day') AT TIME ZONE 'UTC')",Long.class,scope.tenantId(),scope.projectId(),date,date);
        return List.of(new DailyUsageValue(QuotaMetric.NOTIFICATION_DELIVERY,count));
    }
}
