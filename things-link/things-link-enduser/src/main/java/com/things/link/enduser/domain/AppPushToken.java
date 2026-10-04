package com.things.link.enduser.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * App 安装实例的 PUSH 身份；敏感厂商 token 不进入领域对象（ADR 0051）。
 *
 * @param id 安装实例事实 ID
 * @param tenantId 归属租户
 * @param appUserId 所属终端用户
 * @param installationId 客户端稳定安装实例 ID
 * @param provider 厂商通道
 * @param status 当前状态
 * @param createdAt 首次登记时刻
 * @param updatedAt 最近变更时刻
 * @param revokedAt 吊销时刻
 */
public record AppPushToken(UUID id, UUID tenantId, UUID appUserId, UUID installationId,
                           Provider provider, Status status, Instant createdAt,
                           Instant updatedAt, Instant revokedAt) {

    /** 原生厂商通道；MOCK 只服务确定性后端合同测试。 */
    public enum Provider { HUAWEI, XIAOMI, OPPO, VIVO, APNS, MOCK }

    /** 安装实例生命周期；吊销保留历史以阻断旧投递意图。 */
    public enum Status { ACTIVE, REVOKED }
}
