package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppUserDevice;

import java.time.Instant;
import java.util.UUID;

/**
 * CLAIM 消费成功后的设备主控关系投影。
 *
 * @param bindingId   关系事实 ID
 * @param deviceId    被认领设备
 * @param relationRole 关系角色，CLAIM 固定为 PRIMARY
 * @param createdAt   首次建立关系的时刻；幂等重放保持原值
 */
public record DeviceClaimResult(
        UUID bindingId,
        UUID deviceId,
        AppUserDevice.RelationRole relationRole,
        Instant createdAt) {

    /**
     * 从权威关系事实建立响应投影。
     *
     * @param binding 设备关系
     * @return CLAIM 结果
     */
    public static DeviceClaimResult from(AppUserDevice binding) {
        return new DeviceClaimResult(
                binding.id(), binding.deviceId(), binding.relationRole(), binding.createdAt());
    }
}
