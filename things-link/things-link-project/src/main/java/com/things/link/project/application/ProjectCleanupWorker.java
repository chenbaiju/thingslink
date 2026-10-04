package com.things.link.project.application;

import com.things.link.support.tenant.DataPlaneDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** ADR0092：显式启用后的DATA维护入口，每轮一个项目一批，不把触发线程包成长事务。 */
@Component
@DataPlaneDatabase
@ConditionalOnProperty(prefix = "things-link.project.cleanup", name = "enabled", havingValue = "true")
public class ProjectCleanupWorker {
    /** 仅记录稳定错误类型，异常正文不进入持久失败码。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectCleanupWorker.class);
    /** 任一未分类执行异常保持阶段并按ADR0076退避。 */
    private static final String FAILURE_CODE = "PROJECT_CLEANUP_FAILED";
    /** 同一完整能力的领取与失败释放端口。 */
    private final ProjectCleanupAdmissionService admission;
    /** 独立五秒事务的领域执行和进度提交。 */
    private final ProjectCleanupBatchService batches;

    /** @param admission 持久能力端口 @param batches 全部阶段已装配的原子批次 */
    public ProjectCleanupWorker(ProjectCleanupAdmissionService admission,ProjectCleanupBatchService batches) {
        batches.requireCompleteConfiguration();
        this.admission = admission;
        this.batches = batches;
    }

    /** 单轮不循环、不提前释放其他实例租约；异常已回滚后才在另一短事务尝试退避。 */
    @Scheduled(fixedDelayString = "${things-link.project.cleanup.fixed-delay-millis:1000}",
            initialDelayString = "${things-link.project.cleanup.initial-delay-millis:60000}",
            scheduler = "maintenanceScheduler")
    public void cleanNextBatch() {
        Optional<ProjectCleanupClaim> next;
        try {
            next = admission.claimNext();
        } catch (RuntimeException failure) {
            LOGGER.error("项目清理领取失败，类型={}",failure.getClass().getSimpleName());
            return;
        }
        if (next.isEmpty()) return;
        ProjectCleanupClaim claim = next.orElseThrow();
        try {
            batches.execute(claim);
        } catch (RuntimeException failure) {
            LOGGER.error("项目清理批次失败，阶段={}，类型={}",claim.stage(),failure.getClass().getSimpleName());
            try {
                if (!admission.defer(claim,FAILURE_CODE)) {
                    LOGGER.info("项目清理退避已失权，保留当前持有者租约，阶段={}",claim.stage());
                }
            } catch (RuntimeException persistenceFailure) {
                LOGGER.error("项目清理退避写入失败，等待租约接管，类型={}",persistenceFailure.getClass().getSimpleName());
            }
        }
    }
}
