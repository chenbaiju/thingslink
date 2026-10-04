package com.things.link.rule.domain;

import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;

/** ADR0079：十张已实现规则表按静默窗口、指针环及子行顺序分批收束。 */
public interface RuleProjectCleanupRepository {

    /** @param claim 原事务锁定的项目完整身份 @return 实际删除、等待或已证明空域 */
    ProjectCleanupBatchResult clean(ProjectCleanupClaim claim);
}
