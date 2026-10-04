package com.things.link.enduser.api.dto.response;

import com.things.link.enduser.application.DeviceClaimResult;
import com.things.link.enduser.domain.AppUserDevice;

import java.time.Instant;
import java.util.UUID;

/**
 * App CLAIM 成功响应。
 *
 * @param bindingId   设备关系 ID
 * @param deviceId    已认领设备 ID
 * @param relationRole 关系角色，CLAIM 固定为 PRIMARY
 * @param createdAt   关系首次建立时刻
 */
public record DeviceClaimResponse(
        UUID bindingId,
        UUID deviceId,
        AppUserDevice.RelationRole relationRole,
        Instant createdAt) {

    /**
     * 从应用层结果建立响应。
     *
     * @param result CLAIM 结果
     * @return API 响应
     */
    public static DeviceClaimResponse from(DeviceClaimResult result) {
        return new DeviceClaimResponse(
                result.bindingId(), result.deviceId(), result.relationRole(), result.createdAt());
    }
}
