package com.things.link.device.api.dto.response;

import com.things.link.device.domain.DeviceShadow;

import java.time.Instant;
import java.util.UUID;

/** 设备影子响应，desired/reported 以原始 JSON 字符串返回。 */
public record DeviceShadowResponse(UUID deviceId, String desired, String reported, int version, Instant updatedAt) {
    public static DeviceShadowResponse from(DeviceShadow s) {
        return new DeviceShadowResponse(s.deviceId(), s.desired(), s.reported(), s.version(), s.updatedAt());
    }
}
