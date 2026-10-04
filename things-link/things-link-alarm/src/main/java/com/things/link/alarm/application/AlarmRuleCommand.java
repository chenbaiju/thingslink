package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmRule;

import java.util.UUID;

/** 新建或修改规则使用的应用命令；HTTP DTO 只负责映射，不能跨模块复用。 */
public record AlarmRuleCommand(String name, String alarmType, UUID deviceId, String propertyKey,
                               AlarmRule.ComparisonOperator triggerOperator, double triggerThreshold,
                               int triggerDurationSeconds, AlarmRule.ComparisonOperator clearOperator,
                               double clearThreshold, int clearDurationSeconds, AlarmRule.Severity severity,
                               boolean enabled, Integer version) { }
