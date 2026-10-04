package com.things.link.ota.application;

import com.things.link.ota.domain.OtaProjectCleanupRepository;
import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.project.application.ProjectCleanupContributor;
import com.things.link.project.application.ProjectCleanupStage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR0114：OTA域在DASHBOARD收束后、TELEMETRY清理模型父事实前收束。
 * MANDATORY事务保证删除、清理进度和项目租约复核由同一物理事务共同提交或回滚。
 */
@Component
public class OtaProjectCleanupContributor implements ProjectCleanupContributor {

    /** 固定OTA受限数据库入口，不允许贡献器跨域删除enduser或device事实。 */
    private final OtaProjectCleanupRepository repository;

    /**
     * 创建OTA域清理贡献器。
     *
     * @param repository 本域有界清理仓储
     */
    public OtaProjectCleanupContributor(OtaProjectCleanupRepository repository) {
        this.repository = repository;
    }

    /** {@inheritDoc} */
    @Override
    public ProjectCleanupStage stage() {
        return ProjectCleanupStage.OTA;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) {
        return repository.clean(claim);
    }
}
