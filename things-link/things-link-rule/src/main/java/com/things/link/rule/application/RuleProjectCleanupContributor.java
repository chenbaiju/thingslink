package com.things.link.rule.application;

import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.project.application.ProjectCleanupContributor;
import com.things.link.project.application.ProjectCleanupStage;
import com.things.link.rule.domain.RuleProjectCleanupRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** ADR0079：规则域拥有历史清理，保留普通不可变权限及项目原事务围栏。 */
@Component
public class RuleProjectCleanupContributor implements ProjectCleanupContributor {

    /** 固定RULE受限数据库入口，不向普通请求放开DELETE。 */
    private final RuleProjectCleanupRepository repository;

    /** @param repository 本域有界清理仓储 */
    public RuleProjectCleanupContributor(RuleProjectCleanupRepository repository) {
        this.repository = repository;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public ProjectCleanupStage stage() {
        return ProjectCleanupStage.RULE;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) {
        return repository.clean(claim);
    }
}
