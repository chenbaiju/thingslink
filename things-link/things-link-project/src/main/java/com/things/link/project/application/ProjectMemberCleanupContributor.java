package com.things.link.project.application;

import com.things.link.project.domain.ProjectCleanupRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** ADR0091：所有业务域之后才清成员，账号及计量保留。 */
@Component
public class ProjectMemberCleanupContributor implements ProjectCleanupContributor {
    /** 仅访问project自有表的受限仓储。 */
    private final ProjectCleanupRepository repository;

    /** @param repository 固定末序成员入口 */
    public ProjectMemberCleanupContributor(ProjectCleanupRepository repository) {
        this.repository = repository;
    }

    /** {@inheritDoc} */
    @Override public ProjectCleanupStage stage() { return ProjectCleanupStage.PROJECT; }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) { return repository.cleanMembers(claim); }
}
