package com.things.link.alarm.api.dto.request;

import com.things.link.alarm.domain.NotificationChannel;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 收件人写入契约；刻意没有 secret、token、header 或供应商账号字段。 */
public record SaveAlarmNotificationRecipientRequest(
        @NotNull NotificationChannel channel,
        @NotBlank @Size(max = 2048) String target,
        @NotNull Boolean enabled,
        @Min(0) Integer version) {}
