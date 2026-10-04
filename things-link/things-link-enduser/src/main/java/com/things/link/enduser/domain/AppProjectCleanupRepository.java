package com.things.link.enduser.domain;

import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;

/** ADR0081：四张App项目表按令牌指针及关系顺序有界收束。 */
public interface AppProjectCleanupRepository {

    /** @param claim 原事务锁定的项目完整身份 @return 实际删除、等待或已证明空域 */
    ProjectCleanupBatchResult clean(ProjectCleanupClaim claim);
}
