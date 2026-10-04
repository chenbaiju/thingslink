package com.things.link.alarm.api.dto.request;

import com.things.link.alarm.domain.NotificationChannel;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 固定变量模板写入契约；变量白名单由应用服务验证。 */
public record SaveAlarmNotificationTemplateRequest(
        @NotBlank @Size(max = 128) String name,
        @NotNull NotificationChannel channel,
        @Size(max = 256) String subjectTemplate,
        @NotBlank @Size(max = 20000) String bodyTemplate,
        @NotNull Boolean enabled,
        @Min(0) Integer version) {}
