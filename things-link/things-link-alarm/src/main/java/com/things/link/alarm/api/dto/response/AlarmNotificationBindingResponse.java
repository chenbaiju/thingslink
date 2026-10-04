package com.things.link.alarm.api.dto.response;

import com.things.link.alarm.domain.AlarmNotificationBinding;
import com.things.link.alarm.domain.NotificationChannel;

import java.time.Instant;
import java.util.UUID;

/** 规则路由绑定响应。 */
public record AlarmNotificationBindingResponse(
        UUID id,
        UUID ruleId,
        UUID groupId,
        UUID templateId,
        NotificationChannel channel,
        boolean enabled,
        int version,
        Instant createdAt,
        Instant updatedAt) {
    public static AlarmNotificationBindingResponse from(AlarmNotificationBinding v) {
        return new AlarmNotificationBindingResponse(
                v.id(),
                v.ruleId(),
                v.groupId(),
                v.templateId(),
                v.channel(),
                v.enabled(),
                v.version(),
                v.createdAt(),
                v.updatedAt());
    }
}
