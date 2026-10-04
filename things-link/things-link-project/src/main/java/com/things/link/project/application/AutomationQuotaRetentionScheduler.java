package com.things.link.project.application;

import com.things.link.support.tenant.DataPlaneDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** project拥有额度保留；固定函数跨域检查引用，不查询rule私有表。 */
@Component
@DataPlaneDatabase
@ConditionalOnProperty(name="things-link.automation.retention.enabled",havingValue="true",matchIfMissing=true)
public class AutomationQuotaRetentionScheduler {
    private final JdbcTemplate jdbc;
    public AutomationQuotaRetentionScheduler(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    @Scheduled(initialDelayString="${things-link.automation.retention.delay-millis:60000}",
            fixedDelayString="${things-link.automation.retention.delay-millis:60000}",scheduler="maintenanceScheduler")
    public void clean(){jdbc.queryForObject("SELECT automation_purge_quota_reservations()",Integer.class);}
}
