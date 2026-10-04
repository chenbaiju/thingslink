package com.things.link.telemetry.domain;

import java.time.Instant;
import java.util.UUID;

/** 命令历史低敏摘要，不包含载荷、凭据、幂等键或发起者身份。 */
public record DeviceCommandHistoryItem(
        UUID id,
        UUID deviceId,
        String operationType,
        String commandKey,
        String status,
        int attemptCount,
        int maxAttempts,
        Instant acceptedAt,
        Instant completedAt) { }
