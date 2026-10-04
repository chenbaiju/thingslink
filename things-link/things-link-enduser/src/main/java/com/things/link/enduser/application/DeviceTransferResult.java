package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppUserDevice;

import java.time.Instant;
import java.util.UUID;

/**
 * 主控转移成功后的新 PRIMARY 关系投影。
 *
 * @param bindingId 新主控关系事实 ID；既有共享关系升级时保持原 ID
 * @param deviceId 目标设备
 * @param relationRole 关系角色，转移成功固定为 PRIMARY
 * @param createdAt 关系首次建立时刻；原位升级不重写
 */
public record DeviceTransferResult(
        UUID bindingId,
        UUID deviceId,
        AppUserDevice.RelationRole relationRole,
        Instant createdAt) {

    /**
     * 从新主控权威关系建立响应投影。
     *
     * @param binding 新主控关系
     * @return 转移结果
     */
    public static DeviceTransferResult from(AppUserDevice binding) {
        return new DeviceTransferResult(binding.id(), binding.deviceId(),
                AppUserDevice.RelationRole.PRIMARY, binding.createdAt());
    }
}
