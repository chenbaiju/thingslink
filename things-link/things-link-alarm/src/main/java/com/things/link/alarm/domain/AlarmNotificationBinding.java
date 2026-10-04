package com.things.link.alarm.domain;

import java.time.Instant;
import java.util.UUID;

/** 规则、通知组、渠道和模板的显式路由配置。 */
public record AlarmNotificationBinding(
        UUID id,
        UUID tenantId,
        UUID projectId,
        UUID ruleId,
        UUID groupId,
        UUID templateId,
        NotificationChannel channel,
        boolean enabled,
        int version,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt) {}
