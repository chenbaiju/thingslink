package com.things.link.enduser.api.dto.response;

import com.things.link.enduser.application.DeviceShareResult;

import java.time.Instant;
import java.util.UUID;

/**
 * SHARE 消费成功响应。
 *
 * @param bindingId 共享关系 ID
 * @param deviceId 目标设备 ID
 * @param relationRole MEMBER 或 READ_ONLY
 * @param createdAt 关系建立时刻
 */
public record DeviceShareResponse(
        UUID bindingId,
        UUID deviceId,
        String relationRole,
        Instant createdAt) {

    /**
     * 从应用结果创建响应。
     *
     * @param result 共享结果
     * @return API 响应
     */
    public static DeviceShareResponse from(DeviceShareResult result) {
        return new DeviceShareResponse(result.bindingId(), result.deviceId(),
                result.relationRole().name(), result.createdAt());
    }
}
