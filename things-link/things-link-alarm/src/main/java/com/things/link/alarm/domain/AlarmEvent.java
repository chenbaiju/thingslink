package com.things.link.alarm.domain;

import java.time.Instant;
import java.util.UUID;

/** 告警迁移的不可变审计事实；绝不对既有事件 UPDATE。 */
public record AlarmEvent(UUID id, UUID tenantId, UUID projectId, UUID instanceId, EventType eventType,
                         UUID sourceMessageId, String traceId, Double value, Instant occurredAt,
                         Instant receivedAt, UUID actorId, AlarmInstance.ConditionState conditionState,
                         AlarmInstance.AckState ackState, AlarmInstance.ClearReason clearReason) {
    /** 与 ADR 0022 冻结的状态迁移一一对应。 */
    public enum EventType { PENDING, ACTIVATED, ACKNOWLEDGED, CLEARED }
}
