package com.things.link.device.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备影子——每设备一行的当前状态双面镜像。
 *
 * <p>desired 是云端期望状态（下行的终点），reported 是设备实际上报状态（上行的终点）。
 * version 只保护 desired 的并发更新——reported 的并发由 S3-6 的 reported_ts CAS 处理。</p>
 *
 * @param deviceId 设备 ID
 * @param tenantId 租户 ID
 * @param projectId 项目 ID
 * @param desired 云端期望属性（jsonb 字符串）
 * @param reported 设备上报属性（jsonb 字符串）
 * @param version desired 乐观锁版本
 * @param updatedAt 最后修改时间
 */
public record DeviceShadow(UUID deviceId, UUID tenantId, UUID projectId,
                           String desired, String reported, int version, Instant updatedAt) {
}
