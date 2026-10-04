package com.things.link.device.api.dto.response;

import com.things.link.device.application.DeviceRegistrationService;

import java.util.UUID;

/**
 * 动态注册成功响应；Access Token 只返回一次。
 *
 * @param deviceId 新建设备 ID
 * @param deviceKey 设备 MQTT 标识
 * @param accessToken 一次可见的一机一密明文
 */
public record DeviceRegistrationResponse(UUID deviceId, String deviceKey, String accessToken) {

    /** @param result 应用服务结果 @return HTTP 响应 */
    public static DeviceRegistrationResponse from(DeviceRegistrationService.RegistrationResult result) {
        return new DeviceRegistrationResponse(result.deviceId(), result.deviceKey(), result.accessToken());
    }
}
