package com.things.link.alarm.api.dto.request;

import com.things.link.alarm.domain.AlarmRule;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** 告警规则 HTTP 写入契约；条件只接受枚举，禁止传递 SQL、脚本或 JSONPath。 */
public record SaveAlarmRuleRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 64) String alarmType,
        @NotNull UUID deviceId,
        @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_-]{0,63}") String propertyKey,
        @NotNull AlarmRule.ComparisonOperator triggerOperator,
        @NotNull Double triggerThreshold,
        @NotNull @Min(0) @Max(604800) Integer triggerDurationSeconds,
        @NotNull AlarmRule.ComparisonOperator clearOperator,
        @NotNull Double clearThreshold,
        @NotNull @Min(0) @Max(604800) Integer clearDurationSeconds,
        @NotNull AlarmRule.Severity severity,
        @NotNull Boolean enabled,
        @Min(0) Integer version) { }
