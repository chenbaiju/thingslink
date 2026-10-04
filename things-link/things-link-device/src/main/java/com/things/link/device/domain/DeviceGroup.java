package com.things.link.device.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 项目内设备组；动态组的规则只可在查询时解释，不能物化为成员。
 *
 * @param id UUIDv7 主键
 * @param tenantId 租户隔离快照
 * @param projectId 项目隔离轴
 * @param name 项目内唯一名称
 * @param description 可选说明
 * @param type 静态或动态类型
 * @param rule 动态规则；静态组为空
 * @param createdAt 创建时间
 */
public record DeviceGroup(UUID id, UUID tenantId, UUID projectId, String name, String description,
                          Type type, DeviceGroupRule rule, Instant createdAt) {
    /** 设备组成员来源。 */
    public enum Type {
        /** 显式维护成员。 */
        STATIC,
        /** 受控条件实时筛选。 */
        DYNAMIC
    }
}
