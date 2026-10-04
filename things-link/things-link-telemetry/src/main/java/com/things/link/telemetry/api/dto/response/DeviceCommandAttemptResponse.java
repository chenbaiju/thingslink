package com.things.link.telemetry.api.dto.response;

import com.things.link.telemetry.domain.DeviceCommandAttempt;

import java.time.Instant;
import java.util.UUID;

/** 单次命令派发尝试响应。 */
public record DeviceCommandAttemptResponse(UUID id, int attemptNo, String status, String topic,
                                           Instant deadlineAt, Instant createdAt) {
    /** @param value 领域尝试 @return API 响应 */
    public static DeviceCommandAttemptResponse from(DeviceCommandAttempt value) {
        return new DeviceCommandAttemptResponse(value.id(), value.attemptNo(), value.status().name(),
                value.topic(), value.deadlineAt(), value.createdAt());
    }
}
