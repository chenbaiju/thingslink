package com.things.link.alarm.api.dto.response;

import com.things.link.alarm.domain.AlarmInstance;

import java.time.Instant;
import java.util.UUID;

/** 告警实例 REST 响应；两个状态字段显式分离，前端不得把 ACK 当成条件恢复。 */
public record AlarmInstanceResponse(UUID id, UUID ruleId, UUID deviceId, String alarmType, String severity,
                                    AlarmInstance.ConditionState conditionState, AlarmInstance.AckState ackState,
                                    AlarmInstance.ClearReason clearReason, Instant firstConditionAt, Instant activatedAt,
                                    Instant clearedAt, Instant acknowledgedAt, UUID acknowledgedBy, double lastValue,
                                    Instant lastReceivedAt, int version, Instant updatedAt) {
    /** @param value 领域实例 @return HTTP 投影 */
    public static AlarmInstanceResponse from(AlarmInstance value) { return new AlarmInstanceResponse(value.id(), value.ruleId(),
            value.originatorId(), value.alarmType(), value.severity().name(), value.conditionState(), value.ackState(),
            value.clearReason(), value.firstConditionAt(), value.activatedAt(), value.clearedAt(), value.acknowledgedAt(),
            value.acknowledgedBy(), value.lastValue(), value.lastReceivedAt(), value.version(), value.updatedAt()); }
}
