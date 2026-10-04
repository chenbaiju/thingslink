package com.things.link.telemetry.api.dto.response;

import com.things.link.telemetry.domain.DeviceCommandHistoryItem;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** 命令历史低敏摘要，不包含载荷、凭据、幂等键或发起者身份。 */
public record DeviceCommandHistoryResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String operationType,
        String commandKey,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int attemptCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int maxAttempts,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant acceptedAt,
        Instant completedAt) {
    public static DeviceCommandHistoryResponse from(DeviceCommandHistoryItem item) {
        return new DeviceCommandHistoryResponse(item.id(), item.deviceId(), item.operationType(), item.commandKey(),
                item.status(), item.attemptCount(), item.maxAttempts(), item.acceptedAt(), item.completedAt());
    }
}
