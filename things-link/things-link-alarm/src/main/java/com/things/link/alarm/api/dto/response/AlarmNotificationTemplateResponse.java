package com.things.link.alarm.api.dto.response;

import com.things.link.alarm.domain.AlarmNotificationTemplate;
import com.things.link.alarm.domain.NotificationChannel;

import java.time.Instant;
import java.util.UUID;

/** 模板响应。 */
public record AlarmNotificationTemplateResponse(
        UUID id,
        String name,
        NotificationChannel channel,
        String subjectTemplate,
        String bodyTemplate,
        boolean enabled,
        int version,
        Instant createdAt,
        Instant updatedAt) {
    public static AlarmNotificationTemplateResponse from(AlarmNotificationTemplate v) {
        return new AlarmNotificationTemplateResponse(
                v.id(),
                v.name(),
                v.channel(),
                v.subjectTemplate(),
                v.bodyTemplate(),
                v.enabled(),
                v.version(),
                v.createdAt(),
                v.updatedAt());
    }
}
