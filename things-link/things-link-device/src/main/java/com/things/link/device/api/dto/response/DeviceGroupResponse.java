package com.things.link.device.api.dto.response;

import com.things.link.device.domain.DeviceGroup;
import com.things.link.device.domain.DeviceGroupRule;

import java.time.Instant;
import java.util.UUID;

/** 设备组响应；动态规则保持结构化，控制台不得解析 SQL 或自由文本。 */
public record DeviceGroupResponse(
        UUID id,
        String name,
        String description,
        DeviceGroup.Type type,
        DeviceGroupRule rule,
        Instant createdAt) {

    /** @param value 领域设备组 @return API 响应 */
    public static DeviceGroupResponse from(DeviceGroup value) {
        return new DeviceGroupResponse(value.id(), value.name(), value.description(), value.type(),
                value.rule(), value.createdAt());
    }
}
