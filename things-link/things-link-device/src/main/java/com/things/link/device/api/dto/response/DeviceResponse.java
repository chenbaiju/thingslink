package com.things.link.device.api.dto.response;

import com.things.link.device.domain.Device;

import java.time.Instant;
import java.util.UUID;

/** 设备实例响应。 */
public record DeviceResponse(UUID id, UUID deviceTypeId, UUID gatewayId, String deviceKey,
                             String name, String description, Device.Status status,
                             String location, Instant lastOnlineAt, Instant createdAt) {
    /** @param device 领域对象 @return API 响应 */
    public static DeviceResponse from(Device device) {
        return new DeviceResponse(device.id(), device.deviceTypeId(), device.gatewayId(),
                device.deviceKey(), device.name(), device.description(), device.status(),
                device.location(), device.lastOnlineAt(), device.createdAt());
    }
}
