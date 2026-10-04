package com.things.link.rule.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 消息规则定义聚合；脚本正文独立保存在不可变版本事实中。
 *
 * @param id 规则 ID
 * @param tenantId 项目所有者租户 ID
 * @param projectId 项目隔离 ID
 * @param name 项目内唯一名称
 * @param description 用途说明
 * @param status 生命周期状态
 * @param activeVersionId 当前活动版本；草稿时为空
 * @param version 定义乐观锁版本
 * @param createdBy 创建账号
 * @param createdAt 创建时刻
 * @param updatedAt 最近修改时刻
 * @param deletedAt 软删除时刻
 */
public record MessageRule(
        UUID id,
        UUID tenantId,
        UUID projectId,
        String name,
        String description,
        Status status,
        UUID activeVersionId,
        long version,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt) {

    /** 规则定义与活动版本指针的封闭生命周期。 */
    public enum Status {
        /** 尚未发布任何版本。 */
        DRAFT,
        /** 活动版本参与后续 S8-1C 消息预处理。 */
        ACTIVE,
        /** 保留活动版本指针但停止处理新消息。 */
        PAUSED
    }
}
