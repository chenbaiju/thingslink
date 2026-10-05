package com.things.link.device.application;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** 已授权的中性证据；不携带设备凭据、地址、名称或内部领域对象。 */
public record ConsoleDeviceEvidence(UUID deviceId, UUID modelVersionId, String status,
        Instant lastOnlineAt, Instant readAt, List<Property> properties) {
    public ConsoleDeviceEvidence { properties = List.copyOf(properties); }

    /** 未知来源或旧模型的值保持不可用，时间和版本仅用于说明缺口。 */
    @io.swagger.v3.oas.annotations.media.Schema(name = "AssistantEvidenceProperty", requiredProperties = {"key", "value", "occurredAt", "reportedRevision", "sourceModelVersionId", "readAt", "availability"})
    public record Property(String key,
            @io.swagger.v3.oas.annotations.media.Schema(implementation = Object.class, types = {"number", "string", "boolean", "object", "array", "null"},
                    requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
            JsonNode value,
            @Schema(types = {"string", "null"}, format = "date-time") Instant occurredAt,
            @Schema(types = {"string", "null"}, pattern = "0|[1-9][0-9]{0,18}") String reportedRevision,
            @Schema(types = {"string", "null"}, format = "uuid") UUID sourceModelVersionId,
            Instant readAt, Availability availability) { }
    public enum Availability { PRESENT, MISSING, SOURCE_UNKNOWN, MODEL_MISMATCH }
}
