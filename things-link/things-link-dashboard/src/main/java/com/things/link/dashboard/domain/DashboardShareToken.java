package com.things.link.dashboard.domain;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * 分享不可变capability事实；secret仅存不可逆SHA-256，不得把hash写入日志。
 * @param id 分享ID @param tenantId 租户 @param projectId 项目 @param dashboardId 看板
 * @param dashboardVersionId 精确版本 @param projectGeneration 签发项目代次
 * @param secretHash secret小写SHA-256 @param hostCompatibility 受管宿主兼容范围
 * @param refererPolicy 来源附加限制 @param createdAt DB签发时刻 @param expiresAt DB到期时刻
 * @param creatorAccountId 真实签发账号 @param revokedAt 首次撤销时刻 @param revokedBy 首次撤销账号
 */
public record DashboardShareToken(UUID id, UUID tenantId, UUID projectId, UUID dashboardId, UUID dashboardVersionId,
        long projectGeneration, String secretHash, JsonNode hostCompatibility, String refererPolicy,
        Instant createdAt, Instant expiresAt, UUID creatorAccountId, Instant revokedAt, UUID revokedBy) {
    /** 日志误用toString也不暴露凭据摘要或scope正文。 */
    @Override public String toString() { return "DashboardShareToken[id=" + id + "]"; }

}
