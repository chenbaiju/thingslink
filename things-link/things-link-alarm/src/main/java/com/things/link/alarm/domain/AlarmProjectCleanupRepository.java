package com.things.link.alarm.domain;

import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;

/** ADR0080：八张已实现告警表按静默窗口和显式子引用顺序分批收束。 */
public interface AlarmProjectCleanupRepository {

    /** @param claim 原事务锁定的项目完整身份 @return 实际删除、等待或已证明空域 */
    ProjectCleanupBatchResult clean(ProjectCleanupClaim claim);
}
