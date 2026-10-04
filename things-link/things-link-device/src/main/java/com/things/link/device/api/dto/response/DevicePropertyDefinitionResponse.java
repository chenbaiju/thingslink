package com.things.link.device.api.dto.response;

import com.things.link.device.domain.DevicePropertyDefinition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 属性定义响应。 */
public record DevicePropertyDefinitionResponse(UUID id, UUID deviceTypeId, String propertyKey, String name,
                                               DevicePropertyDefinition.AccessType accessType,
                                               DevicePropertyDefinition.DataType dataType, String unit,
                                               Integer decimalPlaces, BigDecimal minimumValue, BigDecimal maximumValue,
                                               List<String> enumOptions, String onLabel, String offLabel, String schema, int sortOrder,
                                               Instant createdAt) {
    /** @param value 领域对象 @return API 响应 */
    public static DevicePropertyDefinitionResponse from(DevicePropertyDefinition value) {
        return new DevicePropertyDefinitionResponse(value.id(), value.deviceTypeId(), value.propertyKey(), value.name(),
                value.accessType(), value.dataType(), value.unit(), value.decimalPlaces(), value.minimumValue(),
                value.maximumValue(), value.enumOptions(), value.onLabel(), value.offLabel(), value.schema(), value.sortOrder(),
                value.createdAt());
    }
}
