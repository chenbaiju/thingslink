package com.things.link.project.application;

import com.things.link.support.cleanup.SupportProjectCleanupResult;
import com.things.link.support.cleanup.SupportProjectCleanupService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** ADR0090：project拥有的薄适配器只映射技术结果，不直查support表或反转依赖。 */
@Component
public class SupportProjectCleanupContributor implements ProjectCleanupContributor {
    /** 固定技术清理入口，所有删除顺序及保留判断仍由support负责。 */
    private final SupportProjectCleanupService cleanup;

    /** @param cleanup 同事务技术清理 */
    public SupportProjectCleanupContributor(SupportProjectCleanupService cleanup) {
        this.cleanup = cleanup;
    }

    /** {@inheritDoc} */
    @Override
    public ProjectCleanupStage stage() {
        return ProjectCleanupStage.SUPPORT;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) {
        SupportProjectCleanupResult result = cleanup.clean(claim.tenantId(), claim.projectId(),
                claim.generation(), claim.leaseToken());
        return new ProjectCleanupBatchResult(result.deletedRows(), result.complete(), result.blockedReason());
    }
}
