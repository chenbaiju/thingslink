package com.things.link.enduser.api.dto.response;

import com.things.link.enduser.application.DeviceTransferResult;
import com.things.link.enduser.domain.AppUserDevice;

import java.time.Instant;
import java.util.UUID;

/**
 * App 主控转移成功响应。
 *
 * @param bindingId 新 PRIMARY 关系 ID
 * @param deviceId 设备 ID
 * @param relationRole 固定为 PRIMARY
 * @param createdAt 新主控关系首次建立时刻
 */
public record DeviceTransferResponse(
        UUID bindingId,
        UUID deviceId,
        AppUserDevice.RelationRole relationRole,
        Instant createdAt) {

    /**
     * 从应用层结果建立响应。
     *
     * @param result 转移结果
     * @return API 响应
     */
    public static DeviceTransferResponse from(DeviceTransferResult result) {
        return new DeviceTransferResponse(result.bindingId(), result.deviceId(),
                result.relationRole(), result.createdAt());
    }
}
