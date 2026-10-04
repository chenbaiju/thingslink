package com.things.link.dashboard.api.dto.response;

import com.things.link.dashboard.application.publication.DashboardShareCreated;
import java.time.Instant;
import java.util.UUID;

/**
 * 分享首次201的唯一凭据回执；不得放入公共响应缓存或日志。
 * @param shareId 稳定分享身份
 * @param secret 仅首次返回的CSPRNG凭据
 * @param expiresAt 数据库确定的到期时刻
 */
public record DashboardShareCreatedResponse(UUID shareId, String secret, Instant expiresAt) {
    /** 仅在本次成功签发链路生成HTTP回执，不提供历史secret恢复。 */
    public static DashboardShareCreatedResponse from(DashboardShareCreated created) {
        return new DashboardShareCreatedResponse(created.shareId(), created.secret(), created.expiresAt());
    }

    /** 调试格式也不能泄露一次性凭据。 */
    @Override
    public String toString() {
        return "DashboardShareCreatedResponse[shareId=" + shareId + ",secret=<redacted>,expiresAt=" + expiresAt + "]";
    }
}
