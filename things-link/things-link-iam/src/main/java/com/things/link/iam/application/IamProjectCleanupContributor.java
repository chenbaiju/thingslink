package com.things.link.iam.application;

import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.project.application.ProjectCleanupContributor;
import com.things.link.project.application.ProjectCleanupStage;
import com.things.link.iam.domain.IamProjectCleanupRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** ADR0089：IAM项目令牌有界清理，保留安全窗口、其他会话及原事务围栏。 */
@Component
public class IamProjectCleanupContributor implements ProjectCleanupContributor {

    /** 固定IAM受限数据库入口，不扩大到账号、租户成员或其他项目会话。 */
    private final IamProjectCleanupRepository repository;

    /** @param repository 本域有界清理仓储 */
    public IamProjectCleanupContributor(IamProjectCleanupRepository repository) {
        this.repository = repository;
    }

    /** {@inheritDoc} */
    @Override
    public ProjectCleanupStage stage() {
        return ProjectCleanupStage.IAM;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) {
        return repository.clean(claim);
    }
}
