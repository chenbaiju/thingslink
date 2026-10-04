package com.things.link.export.domain;

import java.util.UUID;

/**
 * 单次项目导出领取身份。
 * @param jobId 任务 ID
 * @param tenantId 项目owner租户
 * @param projectId 项目 ID
 * @param projectGeneration 生命周期代次
 * @param requesterAccountId 原请求账号
 * @param attemptCount 当前尝试序号
 * @param leaseToken 仅本次尝试可用的租约令牌
 */
public record ProjectExportClaim(UUID jobId, UUID tenantId, UUID projectId, long projectGeneration,
                                 UUID requesterAccountId, int attemptCount, UUID leaseToken) {
    /** 拒绝不完整领取事实进入worker。 */
    public ProjectExportClaim {
        if (jobId == null || tenantId == null || projectId == null || projectGeneration < 0
                || requesterAccountId == null || attemptCount < 1 || attemptCount > 3 || leaseToken == null) {
            throw new IllegalArgumentException("项目导出领取身份不完整");
        }
    }
}
