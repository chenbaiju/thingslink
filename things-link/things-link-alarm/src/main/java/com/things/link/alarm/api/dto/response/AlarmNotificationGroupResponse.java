package com.things.link.alarm.api.dto.response;

import com.things.link.alarm.domain.AlarmNotificationGroup;

import java.time.Instant;
import java.util.UUID;

/** 通知组响应。 */
public record AlarmNotificationGroupResponse(
        UUID id, String name, boolean enabled, int version, Instant createdAt, Instant updatedAt) {
    public static AlarmNotificationGroupResponse from(AlarmNotificationGroup v) {
        return new AlarmNotificationGroupResponse(
                v.id(), v.name(), v.enabled(), v.version(), v.createdAt(), v.updatedAt());
    }
}
