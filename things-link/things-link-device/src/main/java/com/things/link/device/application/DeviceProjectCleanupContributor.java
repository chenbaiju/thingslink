package com.things.link.device.application;

import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.project.application.ProjectCleanupContributor;
import com.things.link.project.application.ProjectCleanupStage;
import com.things.link.device.domain.DeviceProjectCleanupRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** ADR0088：设备域完整清理，子行先清并保留父引用复核及项目原事务围栏。 */
@Component
public class DeviceProjectCleanupContributor implements ProjectCleanupContributor {

    /** 固定DEVICE受限数据库入口，不扩大到support事实、修复审计或其他项目。 */
    private final DeviceProjectCleanupRepository repository;

    /** @param repository 本域有界清理仓储 */
    public DeviceProjectCleanupContributor(DeviceProjectCleanupRepository repository) {
        this.repository = repository;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public ProjectCleanupStage stage() {
        return ProjectCleanupStage.DEVICE;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) {
        return repository.clean(claim);
    }
}
