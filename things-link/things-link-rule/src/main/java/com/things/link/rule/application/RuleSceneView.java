package com.things.link.rule.application;

import java.time.Instant;
import java.util.UUID;

/**
 * application 层场景定义投影，供 HTTP 层返回，不暴露领域枚举。
 *
 * @param id 场景 ID
 * @param name 项目内唯一名称
 * @param description 用途说明
 * @param status DRAFT 或 ACTIVE 字符串
 * @param activeVersionId 活动版本 ID；草稿时为空
 * @param version 定义乐观锁版本
 * @param createdAt 创建时刻
 * @param updatedAt 最近修改时刻
 */
public record RuleSceneView(
        UUID id,
        String name,
        String description,
        String status,
        UUID activeVersionId,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
