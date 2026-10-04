package com.things.link.alarm.api.dto.response;

import com.things.link.alarm.domain.AlarmNotificationRecipient;
import com.things.link.alarm.domain.NotificationChannel;

import java.time.Instant;
import java.util.UUID;

/** 收件人响应；地址只显示脱敏版本。 */
public record AlarmNotificationRecipientResponse(
        UUID id,
        UUID groupId,
        NotificationChannel channel,
        String target,
        boolean enabled,
        int version,
        Instant createdAt,
        Instant updatedAt) {
    public static AlarmNotificationRecipientResponse from(AlarmNotificationRecipient v) {
        return new AlarmNotificationRecipientResponse(
                v.id(),
                v.groupId(),
                v.channel(),
                mask(v.channel(), v.target()),
                v.enabled(),
                v.version(),
                v.createdAt(),
                v.updatedAt());
    }

    private static String mask(NotificationChannel c, String value) {
        if (c == NotificationChannel.EMAIL) {
            int at = value.indexOf('@');
            return at <= 1
                    ? "***" + value.substring(Math.max(at, 0))
                    : value.substring(0, 1) + "***" + value.substring(at);
        }
        try {
            java.net.URI u = java.net.URI.create(value);
            return u.getScheme() + "://" + u.getHost() + "/***";
        } catch (RuntimeException e) {
            return "***";
        }
    }
}
