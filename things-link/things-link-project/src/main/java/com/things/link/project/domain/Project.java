package com.things.link.project.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 项目。协作与数据归属的真实边界（ADR 0012）。
 *
 * @param id        项目 ID（UUIDv7）
 * @param tenantId  归属租户，即创建者的租户。用于计费聚合与归属追溯，
 *                  <b>不用于访问控制</b> —— 协作者来自其他租户是正常情况
 * @param name      项目名称。不要求唯一，重名由 id 区分
 * @param region    区域。<b>一经创建不可变更</b>（架构文档 7.1）：跨区域迁移是
 *                  导出-导入流程，而设备接入域名带区域标识，固件烧录后改不动
 * @param timezone  IANA 项目时区，只用于调度解释与展示；持久时间仍统一使用 UTC
 * @param projectKey 全局唯一短标识符，用于 MQTT Topic 中的 {projectKey} 段
 * @param status    项目状态
 * @param createdAt 创建时刻
 * @param description 项目描述，最多1000字符；未填写时为空字符串
 * @param lifecycleGeneration 项目删除时单调递增的能力撤销代次
 */
public record Project(
        UUID id,
        UUID tenantId,
        String name,
        String region,
        String timezone,
        String projectKey,
        Status status,
        Instant createdAt,
        long lifecycleGeneration,
        String description) {

    /**
     * 兼容代次迁移前的构造调用；既有ACTIVE/ARCHIVED项目从零代开始。
     *
     * @param id 项目ID
     * @param tenantId 项目归属租户
     * @param name 项目名称
     * @param region 项目区域
     * @param timezone 项目时区
     * @param projectKey MQTT稳定项目键
     * @param status 项目状态
     * @param createdAt 创建时刻
     */
    public Project(UUID id, UUID tenantId, String name, String region, String timezone,
                   String projectKey, Status status, Instant createdAt) {
        this(id, tenantId, name, region, timezone, projectKey, status, createdAt, 0L);
    }

    /** 兼容未填写描述的既有构造调用，生命周期代次保持原值。 */
    public Project(UUID id, UUID tenantId, String name, String region, String timezone,
                   String projectKey, Status status, Instant createdAt, long lifecycleGeneration) {
        this(id, tenantId, name, region, timezone, projectKey, status, createdAt, lifecycleGeneration, "");
    }

    /** 项目能力代次必须非负；负值只能用于不存在项目的应用层拒绝投影。 */
    public Project {
        description = description == null ? "" : description;
        if (lifecycleGeneration < 0) {
            throw new IllegalArgumentException("项目生命周期代次不能为负数");
        }
    }

    /** 项目状态。与迁移中的 CHECK 约束保持一致，改动要同时改迁移。 */
    public enum Status {
        /** 正常。 */
        ACTIVE,
        /** 已归档，只读。 */
        ARCHIVED,
        /** 注销中。 */
        DELETING,
        /** ADR0076：恢复窗口已结束且清理已原子受理，不可再恢复。 */
        PURGING,
        /** ADR0076：业务事实已清理，仅保留追溯与防重放墓碑。 */
        PURGED
    }

}
