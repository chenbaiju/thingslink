package com.things.link.rule.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 无 UI 应用服务返回的规则定义投影，不暴露 domain 类型。
 *
 * @param id 规则 ID
 * @param name 名称
 * @param description 用途说明
 * @param status DRAFT/ACTIVE/PAUSED
 * @param activeVersionId 活动版本 ID
 * @param version 乐观锁版本
 * @param createdAt 创建时刻
 * @param updatedAt 最近修改时刻
 */
public record MessageRuleView(
        UUID id,
        String name,
        String description,
        String status,
        UUID activeVersionId,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
