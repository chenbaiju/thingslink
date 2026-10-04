package com.things.link.alarm.domain;

import java.time.Instant;
import java.util.UUID;

/** 项目内可被多条告警规则复用的通知收件人组。 */
public record AlarmNotificationGroup(
        UUID id,
        UUID tenantId,
        UUID projectId,
        String name,
        boolean enabled,
        int version,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt) {}
