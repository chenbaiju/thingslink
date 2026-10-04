package com.things.link.dashboard.domain;

import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;

/**
 * ADR0096/0100：看板域在ENDUSER授权关系之后有界清理应用发布事实。
 * 端口只接收project域已经锁定并复核租约的可信声明，避免本域重新构造清理身份。
 */
public interface DashboardProjectCleanupRepository {

    /**
     * 在调用方已经建立的项目清理事务中执行一个有界批次。
     *
     * @param claim 原事务锁定的项目、代次与租约完整身份
     * @return 实际删除、稳定等待原因或已证明空域的结果
     */
    ProjectCleanupBatchResult clean(ProjectCleanupClaim claim);
}
