package com.things.link.alarm.domain;

import java.time.Instant;
import java.util.UUID;

/** 不执行脚本、仅允许固定变量占位符的通知正文模板。 */
public record AlarmNotificationTemplate(
        UUID id,
        UUID tenantId,
        UUID projectId,
        String name,
        NotificationChannel channel,
        String subjectTemplate,
        String bodyTemplate,
        boolean enabled,
        int version,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt) {}
