package com.things.link.telemetry.domain;

import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;

/** ADR0084：历史子域及命令日志Inbox在原事务内顺序收束。 */
public interface TelemetryProjectCleanupRepository {

    /** @param claim 原事务锁定的项目完整身份 @return 实际删除、等待或已证明空域 */
    ProjectCleanupBatchResult clean(ProjectCleanupClaim claim);
}
