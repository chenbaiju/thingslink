package com.things.link.device.api.dto.response;

import com.things.link.device.domain.DeviceCommandDefinition;

import java.time.Instant;
import java.util.UUID;

/** 命令定义响应。 */
public record DeviceCommandDefinitionResponse(UUID id, UUID deviceTypeId, String commandKey, String name,
                                            String description, String inputSchema, String outputSchema,
                                            int timeoutSeconds, int sortOrder, Instant createdAt) {
    /** @param value 领域聚合 @return API 响应 */
    public static DeviceCommandDefinitionResponse from(DeviceCommandDefinition value) {
        return new DeviceCommandDefinitionResponse(value.id(), value.deviceTypeId(), value.commandKey(),
                value.name(), value.description(), value.inputSchema(), value.outputSchema(),
                value.timeoutSeconds(), value.sortOrder(), value.createdAt());
    }
}
