package com.things.link.device.application;

import java.util.UUID;

/** 当前DIRECT通知路由，不包含设备凭据。
 * @param tenantId 权威租户
 * @param projectId 权威项目
 * @param deviceId 设备身份
 * @param credentialVersion 当前凭据代际
 * @param deviceKey 稳定设备路由段
 */
public record OtaDeviceNotificationRoute(UUID tenantId, UUID projectId, UUID deviceId,
        long credentialVersion, String deviceKey) { }
