package com.things.link.device.domain;

import java.util.UUID;

/**
 * 直接绑定到设备的项目级键值标签；同一设备上的标签键唯一。
 *
 * @param id UUIDv7 主键
 * @param tenantId 租户隔离快照
 * @param projectId 项目隔离轴
 * @param deviceId 被标记设备
 * @param key 受限标签键
 * @param value 标签值
 */
public record DeviceTag(UUID id, UUID tenantId, UUID projectId, UUID deviceId, String key, String value) { }
