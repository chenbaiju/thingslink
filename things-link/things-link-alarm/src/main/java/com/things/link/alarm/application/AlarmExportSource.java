package com.things.link.alarm.application;

import java.time.Instant;
import java.util.UUID;

/** ADR0075 告警实例及不可变事件的流式导出端口。 */
public interface AlarmExportSource {

    /**
     * 流出项目全部告警实例历史。
     * @param tenantId 项目真实归属租户
     * @param projectId 项目 ID
     * @param sink 单行接收器
     * @return 输出行数
     */
    long streamInstances(UUID tenantId, UUID projectId, InstanceSink sink);

    /**
     * 流出项目全部告警迁移事件。
     * @param tenantId 项目真实归属租户
     * @param projectId 项目 ID
     * @param sink 单行接收器
     * @return 输出行数
     */
    long streamEvents(UUID tenantId, UUID projectId, EventSink sink);

    /**
     * {@code alarm-instances.jsonl} 白名单事实。
     * @param id 实例 ID
     * @param ruleId 规则 ID
     * @param originatorType 来源类型
     * @param originatorId 来源 ID
     * @param alarmType 告警类型
     * @param severity 严重度
     * @param conditionState 条件状态
     * @param ackState 确认状态
     * @param clearReason 清除原因
     * @param firstConditionAt 首次满足条件时刻
     * @param recoveryConditionAt 恢复条件起点
     * @param activatedAt 激活时刻
     * @param clearedAt 清除时刻
     * @param acknowledgedAt 确认时刻
     * @param acknowledgedBy 确认账号
     * @param lastReceivedAt 最近接收时刻
     * @param lastOccurredAt 最近设备发生时刻
     * @param lastValue 最近值
     * @param version 状态版本
     * @param createdAt 创建时刻
     * @param updatedAt 修改时刻
     */
    record AlarmInstanceExportRow(UUID id, UUID ruleId, String originatorType, UUID originatorId,
                                  String alarmType, String severity, String conditionState, String ackState,
                                  String clearReason, Instant firstConditionAt, Instant recoveryConditionAt,
                                  Instant activatedAt, Instant clearedAt, Instant acknowledgedAt,
                                  UUID acknowledgedBy, Instant lastReceivedAt, Instant lastOccurredAt,
                                  double lastValue, int version, Instant createdAt, Instant updatedAt) {
    }

    /**
     * {@code alarm-events.jsonl} 白名单事实。
     * @param id 事件 ID
     * @param instanceId 实例 ID
     * @param eventType 迁移类型
     * @param sourceMessageId 自动迁移消息 ID
     * @param traceId 链路 ID
     * @param value 参与判断的值
     * @param occurredAt 设备发生时刻
     * @param receivedAt 平台接收时刻
     * @param actorId 人工操作账号
     * @param conditionState 事件后条件状态
     * @param ackState 事件后确认状态
     * @param clearReason 事件后清除原因
     */
    record AlarmEventExportRow(UUID id, UUID instanceId, String eventType, UUID sourceMessageId,
                               String traceId, Double value, Instant occurredAt, Instant receivedAt,
                               UUID actorId, String conditionState, String ackState, String clearReason) {
    }

    /** 单行告警实例回调。 */
    @FunctionalInterface
    interface InstanceSink {
        /** @param instance 当前告警实例 */
        void accept(AlarmInstanceExportRow instance);
    }

    /** 单行告警事件回调。 */
    @FunctionalInterface
    interface EventSink {
        /** @param event 当前告警事件 */
        void accept(AlarmEventExportRow event);
    }
}
