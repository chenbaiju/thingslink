package com.things.link.alarm.domain;

import java.time.Instant;
import java.util.UUID;

/** 通知组中的一个渠道目标；target 是敏感业务地址，出 API 必须脱敏。 */
public record AlarmNotificationRecipient(
        UUID id,
        UUID tenantId,
        UUID projectId,
        UUID groupId,
        NotificationChannel channel,
        String target,
        boolean enabled,
        int version,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt) {}
