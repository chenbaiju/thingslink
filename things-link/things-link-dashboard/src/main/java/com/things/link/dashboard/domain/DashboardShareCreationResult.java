package com.things.link.dashboard.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 原事务签发仲裁，仅保存摘要和结果身份，不能恢复secret。
 * @param tenantId 租户 @param projectId 项目 @param dashboardId 目标看板 @param accountId 操作者
 * @param idempotencyKeyDigest 幂等键摘要 @param requestDigest 规范请求摘要 @param shareId 分享结果
 * @param createdAt 与token一致的DB创建时刻
 */
public record DashboardShareCreationResult(UUID tenantId, UUID projectId, UUID dashboardId, UUID accountId,
        String idempotencyKeyDigest, String requestDigest, UUID shareId, Instant createdAt) {
}
