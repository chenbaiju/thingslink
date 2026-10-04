package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.AutomationQuotaRepository;
import com.things.link.project.application.DailyUsageContributor;
import com.things.link.project.application.DailyUsageScope;
import com.things.link.project.application.DailyUsageValue;
import com.things.link.project.application.QuotaMetric;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 受限预留函数与幂等事实重算；不读取rule内部执行表。 */
@Repository
public class JdbcAutomationQuotaRepository implements AutomationQuotaRepository, DailyUsageContributor {
    private final JdbcTemplate jdbc;
    public JdbcAutomationQuotaRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public boolean enabled(UUID tenantId, UUID projectId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT public.automation_quota_enabled(?,?)", Boolean.class, tenantId, projectId));
    }
    @Override public String reserve(UUID tenantId, UUID projectId, UUID executionId) {
        return jdbc.queryForObject("SELECT public.reserve_automation_execution(?,?,?)", String.class,
                tenantId, projectId, executionId);
    }
    @Override public List<DailyUsageValue> calculate(DailyUsageScope scope, LocalDate date) {
        Long used = jdbc.queryForObject("""
                SELECT count(*) FROM public.sys_automation_quota_reservation
                 WHERE tenant_id=? AND project_id=? AND usage_date=?
                """, Long.class, scope.tenantId(), scope.projectId(), date);
        return List.of(new DailyUsageValue(QuotaMetric.AUTOMATION_EXECUTION, used));
    }
}
