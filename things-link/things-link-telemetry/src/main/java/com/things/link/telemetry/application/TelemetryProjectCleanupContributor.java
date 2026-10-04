package com.things.link.telemetry.application;

import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.project.application.ProjectCleanupContributor;
import com.things.link.project.application.ProjectCleanupStage;
import com.things.link.telemetry.domain.TelemetryProjectCleanupRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** ADR0084：遥测域完整清理，保留重放静默、历史子域及项目原事务围栏。 */
@Component
public class TelemetryProjectCleanupContributor implements ProjectCleanupContributor {

    /** 固定TELEMETRY受限数据库入口，不扩大到device影子、support事实或其他项目。 */
    private final TelemetryProjectCleanupRepository repository;

    /** @param repository 本域有界清理仓储 */
    public TelemetryProjectCleanupContributor(TelemetryProjectCleanupRepository repository) {
        this.repository = repository;
    }

    /** {@inheritDoc} */
    @Override
    public ProjectCleanupStage stage() {
        return ProjectCleanupStage.TELEMETRY;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) {
        return repository.clean(claim);
    }
}
