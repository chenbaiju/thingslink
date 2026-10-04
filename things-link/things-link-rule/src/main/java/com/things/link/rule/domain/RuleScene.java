package com.things.link.rule.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 手动场景控制面聚合；条件与动作正文独立保存在只追加的不可变版本事实中（见 ADR 0030）。
 *
 * @param id 场景 ID
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
public record RuleScene(
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

    /** 场景定义与活动版本指针的封闭生命周期；触发器固定 MANUAL，暂停保留活动版本。 */
    public enum Status {
        /** 尚未发布任何版本，不可一键执行。 */
        DRAFT,
        /** 已发布活动版本，可一键执行。 */
        ACTIVE,
        /** 暂停新执行，保留活动版本和既有执行事实。 */
        PAUSED
    }
}
