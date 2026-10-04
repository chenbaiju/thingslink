package com.things.link.alarm.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 规则 Outbox 消费者调用告警状态机的可信后台输入。
 *
 * @param messageId 原始规则消息 ID，也是不可变事件的幂等来源
 * @param tenantId 项目 owner tenant，必须与告警规则归属一致
 * @param projectId 已确权项目
 * @param alarmRuleId S6 告警规则 ID，告警类型和严重程度只能由它解析
 * @param deviceId 已确权规则消息设备，必须与告警规则来源一致
 * @param occurredAt 原始设备事件时间，仅用于诊断
 * @param receivedAt 规则执行的固定平台接收时间，用于状态迁移排序
 * @param traceId 全链路追踪标识
 */
public record RuleAlarmActionInput(
        UUID messageId,
        UUID tenantId,
        UUID projectId,
        UUID alarmRuleId,
        UUID deviceId,
        Instant occurredAt,
        Instant receivedAt,
        String traceId) {

    /** 在进入状态机前拒绝缺失身份和超出数据库契约的 trace，避免产生半条告警事实。 */
    public RuleAlarmActionInput {
        Objects.requireNonNull(messageId, "messageId 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(projectId, "projectId 不能为空");
        Objects.requireNonNull(alarmRuleId, "alarmRuleId 不能为空");
        Objects.requireNonNull(deviceId, "deviceId 不能为空");
        Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
        Objects.requireNonNull(receivedAt, "receivedAt 不能为空");
        if (traceId == null || traceId.isBlank() || traceId.length() > 64) {
            throw new IllegalArgumentException("traceId 必须为长度 1 至 64 的非空文本");
        }
    }
}
