package com.things.link.integration.application;
import com.things.link.integration.domain.IntegrationProjectCleanupRepository;
import com.things.link.project.application.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
/** ADR0169固定INTEGRATION阶段，删除与原项目进度同一事务。 */
@Component
public class IntegrationProjectCleanupContributor implements ProjectCleanupContributor {
    private final IntegrationProjectCleanupRepository repository;
    public IntegrationProjectCleanupContributor(IntegrationProjectCleanupRepository repository){this.repository=repository;}
    @Override public ProjectCleanupStage stage(){return ProjectCleanupStage.INTEGRATION;}
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim){return repository.clean(claim);}
}
