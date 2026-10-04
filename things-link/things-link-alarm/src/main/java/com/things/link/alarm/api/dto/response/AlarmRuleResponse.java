package com.things.link.alarm.api.dto.response;

import com.things.link.alarm.domain.AlarmRule;

import java.time.Instant;
import java.util.UUID;

/** 告警规则 REST 响应。 */
public record AlarmRuleResponse(UUID id, String name, String alarmType, UUID deviceId, String propertyKey,
                                AlarmRule.ComparisonOperator triggerOperator, double triggerThreshold,
                                int triggerDurationSeconds, AlarmRule.ComparisonOperator clearOperator,
                                double clearThreshold, int clearDurationSeconds, AlarmRule.Severity severity,
                                boolean enabled, int version, Instant createdAt, Instant updatedAt) {
    /** @param value 领域规则 @return HTTP 投影 */
    public static AlarmRuleResponse from(AlarmRule value) { return new AlarmRuleResponse(value.id(), value.name(), value.alarmType(),
            value.originatorId(), value.propertyKey(), value.triggerOperator(), value.triggerThreshold(), value.triggerDurationSeconds(),
            value.clearOperator(), value.clearThreshold(), value.clearDurationSeconds(), value.severity(), value.enabled(),
            value.version(), value.createdAt(), value.updatedAt()); }
}
