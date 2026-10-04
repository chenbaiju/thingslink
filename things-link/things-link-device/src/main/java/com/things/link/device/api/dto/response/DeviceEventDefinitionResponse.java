package com.things.link.device.api.dto.response;

import com.things.link.device.domain.DeviceEventDefinition;
import com.things.link.device.domain.DevicePropertyDefinition;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 事件定义响应。 */
public record DeviceEventDefinitionResponse(UUID id, UUID deviceTypeId, String eventKey, String name,
                                            DeviceEventDefinition.Level level, String description, int sortOrder,
                                            List<ParameterResponse> parameters, Instant createdAt) {
    /** @param value 领域聚合 @return API 响应 */
    public static DeviceEventDefinitionResponse from(DeviceEventDefinition value) {
        return new DeviceEventDefinitionResponse(value.id(), value.deviceTypeId(), value.eventKey(), value.name(),
                value.level(), value.description(), value.sortOrder(), value.parameters().stream()
                .map(ParameterResponse::from).toList(), value.createdAt());
    }

    /** 事件参数响应。 */
    public record ParameterResponse(UUID id, String parameterKey, String name,
                                    DevicePropertyDefinition.DataType dataType, boolean required,
                                    List<String> enumOptions, int sortOrder) {
        /** @param value 参数领域对象 @return 参数响应 */
        public static ParameterResponse from(DeviceEventDefinition.Parameter value) {
            return new ParameterResponse(value.id(), value.parameterKey(), value.name(), value.dataType(),
                    value.required(), value.enumOptions(), value.sortOrder());
        }
    }
}
