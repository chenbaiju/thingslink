package com.things.link.dashboard.application;

import com.things.link.dashboard.domain.DashboardProjectCleanupRepository;
import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.project.application.ProjectCleanupContributor;
import com.things.link.project.application.ProjectCleanupStage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR0096/0100：看板域在ENDUSER清除授权关系后、TELEMETRY清理模型父事实前收束。
 * MANDATORY事务保证删除、清理进度和项目租约复核由同一物理事务共同提交或回滚。
 */
@Component
public class DashboardProjectCleanupContributor implements ProjectCleanupContributor {

    /** 固定DASHBOARD受限数据库入口，不允许贡献器跨域删除enduser或device事实。 */
    private final DashboardProjectCleanupRepository repository;

    /**
     * 创建看板域清理贡献器。
     *
     * @param repository 本域有界清理仓储
     */
    public DashboardProjectCleanupContributor(DashboardProjectCleanupRepository repository) {
        this.repository = repository;
    }

    /** {@inheritDoc} */
    @Override
    public ProjectCleanupStage stage() {
        return ProjectCleanupStage.DASHBOARD;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) {
        return repository.clean(claim);
    }
}
