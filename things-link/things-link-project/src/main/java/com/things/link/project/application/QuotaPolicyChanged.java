package com.things.link.project.application;

import java.util.Objects;
import java.util.UUID;

/**
 * 租户配额绑定或策略模板更新后的 Redis 失效事件。
 *
 * <p>事件只标记本机条目过期，不携带完整策略也不删除 最近一次有效值；Redis Pub/Sub 不可重放，TTL
 * 负责覆盖丢失事件。
 *
 * @param tenantId 被影响的租户 ID
 * @param assignmentVersion 租户策略绑定版本
 * @param policyVersion 策略模板版本
 */
public record QuotaPolicyChanged(UUID tenantId, long assignmentVersion, long policyVersion) {

    /**
     * 校验版本均来自 PostgreSQL 的正数约束，防止损坏消息污染缓存顺序判断。
     */
    public QuotaPolicyChanged {
        Objects.requireNonNull(tenantId, "租户 ID 不能为空");
        if (assignmentVersion <= 0 || policyVersion <= 0) {
            throw new IllegalArgumentException("配额策略失效事件版本必须为正数");
        }
    }
}
