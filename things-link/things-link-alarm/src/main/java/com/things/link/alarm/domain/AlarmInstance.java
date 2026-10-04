package com.things.link.alarm.domain;

import java.time.Instant;
import java.util.UUID;

/** 当前或已清除的一代告警事故；condition 与 ack 正交语义来自 ADR 0022。 */
public record AlarmInstance(UUID id, UUID tenantId, UUID projectId, UUID ruleId,
                            AlarmRule.OriginatorType originatorType, UUID originatorId, String alarmType,
                            AlarmRule.Severity severity, ConditionState conditionState, AckState ackState,
                            ClearReason clearReason, Instant firstConditionAt, Instant recoveryConditionAt,
                            Instant activatedAt, Instant clearedAt, Instant acknowledgedAt, UUID acknowledgedBy,
                            Instant lastReceivedAt, Instant lastOccurredAt, double lastValue, int version,
                            Instant createdAt, Instant updatedAt) {
    /** 条件生命周期；不存在活动行才表示 NORMAL。 */
    public enum ConditionState { PENDING, ACTIVE, CLEARED }
    /** 人工确认维度，不得放进 condition 枚举。 */
    public enum AckState { UNACKNOWLEDGED, ACKNOWLEDGED }
    /** 终止一代事故的来源。 */
    public enum ClearReason { AUTO_RECOVERY, MANUAL }

    /** @return 当前实例是否仍参与活动唯一键 */
    public boolean active() { return conditionState != ConditionState.CLEARED; }
}
