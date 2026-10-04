package com.things.link.alarm.application;

import java.time.Instant;
import java.util.UUID;

/** telemetry 在物模型校验与 inbox 去重后传给告警域的可信数值属性输入。 */
public record AlarmEvaluationInput(UUID messageId, UUID tenantId, UUID projectId, UUID deviceId,
                                   String propertyKey, double value, Instant occurredAt, Instant receivedAt,
                                   String traceId) { }
