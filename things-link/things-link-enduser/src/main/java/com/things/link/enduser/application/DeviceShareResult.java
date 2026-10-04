package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppUserDevice;

import java.time.Instant;
import java.util.UUID;

/**
 * SHARE 消费成功后的共享关系投影。
 *
 * @param bindingId 共享关系 ID
 * @param deviceId 目标设备
 * @param relationRole MEMBER 或 READ_ONLY
 * @param createdAt 关系建立时刻
 */
public record DeviceShareResult(
        UUID bindingId,
        UUID deviceId,
        AppUserDevice.RelationRole relationRole,
        Instant createdAt) {

    /**
     * 从共享关系建立响应投影。
     *
     * @param binding 权威共享关系
     * @return 共享结果
     */
    public static DeviceShareResult from(AppUserDevice binding) {
        return new DeviceShareResult(binding.id(), binding.deviceId(),
                binding.relationRole(), binding.createdAt());
    }
}
