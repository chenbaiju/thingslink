package com.things.link.telemetry.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 事件原事实只读投影；数值保十进制，不补当前定义名称或私有诊断。 */
public record DeviceEventResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID messageId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceTypeId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String eventKey,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"INFO", "WARNING", "ERROR"}) String level,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID thingModelVersionId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String modelVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"CURRENT", "HISTORY_ONLY"}) String eligibility,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant occurredAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant receivedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant acceptedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "凭据整字段移除后的四种标量投影；数字按精确十进制JSON输出") Map<String, Object> params,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean paramsRedacted) {
    /** 固定公开投影不容许后续调用者替换参数。 */
    public DeviceEventResponse { params = Map.copyOf(params); }
}
