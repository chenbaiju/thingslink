package com.things.link.assistant.application;
import com.things.link.assistant.domain.AssistantProjectCleanupRepository;
import com.things.link.project.application.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
/** 凭据物理删除与项目清理进度同事务。 */
@Component
public class AssistantProjectCleanupContributor implements ProjectCleanupContributor {
    private final AssistantProjectCleanupRepository repository;
    public AssistantProjectCleanupContributor(AssistantProjectCleanupRepository repository) { this.repository=repository; }
    @Override public ProjectCleanupStage stage() { return ProjectCleanupStage.ASSISTANT; }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) { return repository.clean(claim); }
}
