package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmProjectCleanupRepository;
import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.project.application.ProjectCleanupContributor;
import com.things.link.project.application.ProjectCleanupStage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** ADR0080：告警域拥有历史清理，保留原权限及项目原事务围栏。 */
@Component
public class AlarmProjectCleanupContributor implements ProjectCleanupContributor {

    /** 固定ALARM受限数据库入口，保持原有普通权限。 */
    private final AlarmProjectCleanupRepository repository;

    /** @param repository 本域有界清理仓储 */
    public AlarmProjectCleanupContributor(AlarmProjectCleanupRepository repository) {
        this.repository = repository;
    }

    /** {@inheritDoc} */
    @Override
    public ProjectCleanupStage stage() {
        return ProjectCleanupStage.ALARM;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) {
        return repository.clean(claim);
    }
}
