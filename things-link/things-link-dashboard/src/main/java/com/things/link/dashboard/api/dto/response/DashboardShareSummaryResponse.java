package com.things.link.dashboard.api.dto.response;

import com.things.link.dashboard.api.dto.request.CreateDashboardShareRequest.HostCompatibility;
import com.things.link.dashboard.domain.DashboardShareSummary;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * 分享管理的封闭九字段摘要；版本号保持字符串，撤销时刻显式nullable。
 * @param shareId 分享ID
 * @param dashboardVersionId 精确不可变版本
 * @param dashboardVersionNumber 版本号的十进制字符串
 * @param status 数据库时间派生的状态
 * @param refererPolicy 来源附加检查策略
 * @param hostCompatibility 有限宿主版本范围
 * @param expiresAt 到期时刻
 * @param createdAt 创建时刻
 * @param revokedAt 首次撤销时刻，未撤销为null
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DashboardShareSummaryResponse(UUID shareId, UUID dashboardVersionId, String dashboardVersionNumber,
        @Schema(allowableValues = {"ACTIVE", "EXPIRED", "REVOKED"}) String status,
        @Schema(allowableValues = {"NONE", "HOST_ORIGIN"}) String refererPolicy,
        HostCompatibility hostCompatibility, Instant expiresAt, Instant createdAt,
        @Schema(types = {"string", "null"}, format = "date-time") Instant revokedAt) {
    /** 从受授权的轻量领域投影映射，不序列化含hash或creator的持久对象。 */
    public static DashboardShareSummaryResponse from(DashboardShareSummary summary) {
        return new DashboardShareSummaryResponse(summary.shareId(), summary.dashboardVersionId(),
                Long.toString(summary.dashboardVersionNumber()), summary.status(), summary.refererPolicy(),
                new HostCompatibility(summary.hostCompatibility().get("minInclusive").stringValue(),
                        summary.hostCompatibility().get("maxExclusive").stringValue()),
                summary.expiresAt(), summary.createdAt(), summary.revokedAt());
    }
}
