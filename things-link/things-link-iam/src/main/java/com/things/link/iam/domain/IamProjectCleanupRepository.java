package com.things.link.iam.domain;

import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;

/** ADR0089：IAM项目令牌在保留窗口后有界收束。 */
public interface IamProjectCleanupRepository {

    /** @param claim 原事务锁定的项目完整身份 @return 实际删除、等待或已证明空域 */
    ProjectCleanupBatchResult clean(ProjectCleanupClaim claim);
}
