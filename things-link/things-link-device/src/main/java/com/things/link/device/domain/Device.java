package com.things.link.device.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备实例聚合根。
 *
 * <p>device_key 是项目内唯一的稳定标识符，对应 MQTT Topic 中的 {@code {deviceKey}} 段，
 * 创建后不可变更。凭据独立管理于 {@code dev_credential} 表。</p>
 *
 * @param id 设备 ID
 * @param tenantId 租户 ID
 * @param projectId 项目 ID
 * @param deviceTypeId 设备类型 ID（可空）
 * @param gatewayId 网关设备 ID（子设备指向网关）
 * @param deviceKey 项目内唯一标识符
 * @param name 显示名称
 * @param description 说明
 * @param status 连接状态
 * @param location 位置
 * @param lastOnlineAt 最后上线时刻
 * @param createdAt 创建时间
 */
public record Device(UUID id, UUID tenantId, UUID projectId, UUID deviceTypeId, UUID gatewayId,
                     String deviceKey, String name, String description, Status status,
                     String location, Instant lastOnlineAt, Instant createdAt) {

    /** ADR0033：新关系UNKNOWN或解绑不能沿用旧网关的ONLINE，也不能丢弃曾在线的历史。 */
    public Status statusWithoutGatewayReachability() {
        return lastOnlineAt == null ? Status.INACTIVE : Status.OFFLINE;
    }

    /** 设备连接状态。 */
    public enum Status { /** 从未连接。 */ INACTIVE, /** 当前在线。 */ ONLINE, /** 曾经在线现已断开。 */ OFFLINE }
}
