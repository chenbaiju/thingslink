package com.things.link.dashboard.domain;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * 管理列表轻量投影，明确排除secret、hash、creator与完整scope。
 * @param shareId 分享ID @param dashboardVersionId 精确看板版本 @param dashboardVersionNumber 版本号
 * @param status 按DB时间派生ACTIVE/EXPIRED/REVOKED @param refererPolicy 来源附加限制
 * @param hostCompatibility 宿主兼容范围 @param expiresAt 到期时刻 @param createdAt 创建时刻
 * @param revokedAt 首次撤销时刻
 */
public record DashboardShareSummary(UUID shareId, UUID dashboardVersionId, long dashboardVersionNumber, String status,
        String refererPolicy, JsonNode hostCompatibility, Instant expiresAt, Instant createdAt, Instant revokedAt) {
}
