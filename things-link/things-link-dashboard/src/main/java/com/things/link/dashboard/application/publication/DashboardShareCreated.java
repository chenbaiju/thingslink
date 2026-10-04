package com.things.link.dashboard.application.publication;

import java.time.Instant;
import java.util.UUID;

/** @param shareId 独立分享标识 @param secret 仅首次响应可见的一次性凭据 @param expiresAt 数据库权威到期时刻 */
public record DashboardShareCreated(UUID shareId, String secret, Instant expiresAt) {
    /** 默认诊断字符串不能把一次性secret带入应用日志。 */
    @Override public String toString() { return "DashboardShareCreated[shareId=" + shareId + ",secret=<redacted>,expiresAt=" + expiresAt + "]"; }
}
