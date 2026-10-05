package com.things.link.task.application;

import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.project.application.ProjectCleanupContributor;
import com.things.link.project.application.ProjectCleanupStage;
import com.things.link.task.domain.TaskProjectCleanupRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** ADR0076/0077：任务事实在不可恢复项目围栏内有界清理，命令事实仍由telemetry拥有。 */
@Component
public class TaskProjectCleanupContributor implements ProjectCleanupContributor {

    /** 任务表的唯一清理SQL入口。 */
    private final TaskProjectCleanupRepository repository;

    /** @param repository 任务域有界清理仓储 */
    public TaskProjectCleanupContributor(TaskProjectCleanupRepository repository) {
        this.repository = repository;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public ProjectCleanupStage stage() {
        return ProjectCleanupStage.TASK;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) {
        return repository.clean(claim);
    }
}
