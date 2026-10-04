package com.things.link.alarm.api.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 通知组写入契约。 */
public record SaveAlarmNotificationGroupRequest(
        @NotBlank @Size(max = 128) String name,
        @NotNull Boolean enabled,
        @Min(0) Integer version) {}
