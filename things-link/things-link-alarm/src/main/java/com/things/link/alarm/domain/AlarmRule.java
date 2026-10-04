package com.things.link.alarm.domain;

import java.time.Instant;
import java.util.UUID;

/** 告警规则不可变读模型；修改以新 version 覆盖同一规则事实。 */
public record AlarmRule(UUID id, UUID tenantId, UUID projectId, String name, String alarmType,
                        OriginatorType originatorType, UUID originatorId, String propertyKey,
                        ComparisonOperator triggerOperator, double triggerThreshold, int triggerDurationSeconds,
                        ComparisonOperator clearOperator, double clearThreshold, int clearDurationSeconds,
                        Severity severity, boolean enabled, int version, Instant createdAt, Instant updatedAt,
                        Instant deletedAt) {
    /** S6-1 只支持设备作为来源，未来扩展必须先更新 ADR 0022 与唯一键语义。 */
    public enum OriginatorType { DEVICE }
    /** 数据库与 API 共同使用的固定数值比较白名单，禁止解析任意表达式。 */
    public enum ComparisonOperator {
        /** 大于。 */ GT,
        /** 大于等于。 */ GTE,
        /** 小于。 */ LT,
        /** 小于等于。 */ LTE,
        /** 等于。 */ EQ,
        /** 不等于。 */ NE;

        /** @param value 当前数值 @param threshold 规则阈值 @return 比较是否成立 */
        public boolean matches(double value, double threshold) {
            return switch (this) {
                case GT -> value > threshold;
                case GTE -> value >= threshold;
                case LT -> value < threshold;
                case LTE -> value <= threshold;
                case EQ -> Double.compare(value, threshold) == 0;
                case NE -> Double.compare(value, threshold) != 0;
            };
        }
    }
    /** 规则与实例展示的固定严重程度白名单。 */
    public enum Severity { CRITICAL, MAJOR, MINOR, WARNING, INFO }
}
