package com.things.link.alarm.api.dto.request;

import com.things.link.alarm.domain.NotificationChannel;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** 规则通知路由写入契约。 */
public record SaveAlarmNotificationBindingRequest(
        @NotNull UUID groupId,
        @NotNull UUID templateId,
        @NotNull NotificationChannel channel,
        @NotNull Boolean enabled,
        @Min(0) Integer version) {}
