package com.things.link.alarm.api.dto.response;

import com.things.link.alarm.domain.AlarmNotificationDelivery;
import com.things.link.alarm.domain.NotificationChannel;

import java.time.Instant;
import java.util.UUID;

/** 投递意图查询响应，不返回正文、原始地址或任何凭据。 */
public record AlarmNotificationDeliveryResponse(
        UUID id,
        UUID instanceId,
        UUID alarmEventId,
        NotificationChannel channel,
        String target,
        AlarmNotificationDelivery.Status status,
        int attemptCount,
        Instant nextAttemptAt,
        Instant createdAt,
        Instant updatedAt) {
    public static AlarmNotificationDeliveryResponse from(AlarmNotificationDelivery v) {
        return new AlarmNotificationDeliveryResponse(
                v.id(),
                v.instanceId(),
                v.alarmEventId(),
                v.channel(),
                "***",
                v.status(),
                v.attemptCount(),
                v.nextAttemptAt(),
                v.createdAt(),
                v.updatedAt());
    }
}
