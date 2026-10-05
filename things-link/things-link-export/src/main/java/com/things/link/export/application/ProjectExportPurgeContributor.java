package com.things.link.export.application;

import com.things.link.export.domain.ProjectExportPurgeRepository;
import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.project.application.ProjectCleanupContributor;
import com.things.link.project.application.ProjectCleanupStage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** ADR0076/0077：复用导出worker完成网络清理，本贡献器只确认和删除本域数据库事实。 */
@Component
public class ProjectExportPurgeContributor implements ProjectCleanupContributor {

    /** 导出域自己的持久化端口。 */
    private final ProjectExportPurgeRepository repository;

    /** @param repository 导出元数据有界清理仓储 */
    public ProjectExportPurgeContributor(ProjectExportPurgeRepository repository) {
        this.repository = repository;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public ProjectCleanupStage stage() {
        return ProjectCleanupStage.WAIT_EXPORT;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) {
        return repository.clean(claim);
    }
}
