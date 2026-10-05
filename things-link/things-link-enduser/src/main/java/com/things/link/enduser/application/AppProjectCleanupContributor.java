package com.things.link.enduser.application;

import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.project.application.ProjectCleanupContributor;
import com.things.link.project.application.ProjectCleanupStage;
import com.things.link.enduser.domain.AppProjectCleanupRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** ADR0081：App域拥有历史清理，保留共享身份及项目原事务围栏。 */
@Component
public class AppProjectCleanupContributor implements ProjectCleanupContributor {

    /** 固定ENDUSER受限数据库入口，不扩大到共享用户或其他项目。 */
    private final AppProjectCleanupRepository repository;

    /** @param repository 本域有界清理仓储 */
    public AppProjectCleanupContributor(AppProjectCleanupRepository repository) {
        this.repository = repository;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public ProjectCleanupStage stage() {
        return ProjectCleanupStage.ENDUSER;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) {
        return repository.clean(claim);
    }
}
