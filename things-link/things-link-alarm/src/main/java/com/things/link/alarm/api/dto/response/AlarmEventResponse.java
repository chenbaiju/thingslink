package com.things.link.alarm.api.dto.response;

import com.things.link.alarm.domain.AlarmEvent;

import java.time.Instant;
import java.util.UUID;

/** 告警不可变事件 REST 响应。 */
public record AlarmEventResponse(UUID id, AlarmEvent.EventType eventType, UUID sourceMessageId, String traceId,
                                 Double value, Instant occurredAt, Instant receivedAt, UUID actorId,
                                 String conditionState, String ackState, String clearReason) {
    /** @param value 领域事件 @return HTTP 投影 */
    public static AlarmEventResponse from(AlarmEvent value) { return new AlarmEventResponse(value.id(), value.eventType(),
            value.sourceMessageId(), value.traceId(), value.value(), value.occurredAt(), value.receivedAt(), value.actorId(),
            value.conditionState().name(), value.ackState().name(), value.clearReason() == null ? null : value.clearReason().name()); }
}
