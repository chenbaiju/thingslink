package com.things.link.alarm.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 数据运行合同§3.5的最小告警事实，不含规则、操作者、通知或原始属性值。
 * @param id 告警事故ID
 * @param deviceId 来源设备ID
 * @param alarmType 告警类型纯文本
 * @param severity 严重程度
 * @param conditionState 条件状态
 * @param ackState 确认状态
 * @param firstConditionAt 首次条件成立时间
 * @param activatedAt 激活时间，可空
 * @param clearedAt 清除时间，可空
 * @param acknowledgedAt 确认时间，可空
 * @param lastReceivedAt 最近接收时间
 * @param version 乐观锁版本
 */
public record AlarmDeviceQueryItem(UUID id, UUID deviceId, String alarmType, String severity,
        String conditionState, String ackState, Instant firstConditionAt, Instant activatedAt,
        Instant clearedAt, Instant acknowledgedAt, Instant lastReceivedAt, int version) {
}
