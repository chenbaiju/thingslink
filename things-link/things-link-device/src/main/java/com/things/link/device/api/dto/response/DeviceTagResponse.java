package com.things.link.device.api.dto.response;

import com.things.link.device.domain.DeviceTag;

import java.util.UUID;

/** 设备键值标签响应。 */
public record DeviceTagResponse(UUID id, UUID deviceId, String key, String value) {
    /** @param tag 领域标签 @return API 响应 */
    public static DeviceTagResponse from(DeviceTag tag) {
        return new DeviceTagResponse(tag.id(), tag.deviceId(), tag.key(), tag.value());
    }
}
