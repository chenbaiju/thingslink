package com.things.link.project.application;

import com.things.link.support.tenant.DataPlaneDatabase;
import com.things.link.project.domain.DailyUsageReconciliationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 以数据库短租约驱动多实例 UTC 日用量对账。
 */
@Component
@ConditionalOnProperty(
        prefix = "things-link.quota.daily-reconciliation",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@DataPlaneDatabase
public class DailyUsageReconciliationScanner {

    /** 日志只记录项目范围，不打印策略或业务载荷。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(DailyUsageReconciliationScanner.class);
    /** 受限范围领取端口。 */
    private final DailyUsageReconciliationRepository repository;
    /** 单项目独立事务用例。 */
    private final DailyUsageReconciliationService service;

    /**
     * @param repository 受限范围领取端口
     * @param service 单项目归并事务服务
     */
    public DailyUsageReconciliationScanner(DailyUsageReconciliationRepository repository,
                                           DailyUsageReconciliationService service) {
        this.repository = repository;
        this.service = service;
    }

    /** 每十秒领取最多一百个到期项目；项目本身成功后一分钟内不会重复扫描。 */
    @Scheduled(fixedDelayString = "${things-link.quota.daily-reconcile-scan-millis:10000}",
            scheduler = "maintenanceScheduler")
    public void scan() {
        for (com.things.link.project.domain.DailyUsageScope claimed : repository.claimDueScopes(100)) {
            DailyUsageScope scope = new DailyUsageScope(claimed.tenantId(), claimed.projectId());
            try {
                service.reconcile(scope);
            } catch (RuntimeException exception) {
                // 保留数据库租约等待自动接管，立即清租约会在持续故障时形成无界忙循环。
                LOGGER.error("UTC 日用量归并失败 tenantId={} projectId={}",
                        scope.tenantId(), scope.projectId(), exception);
            }
        }
    }
}
