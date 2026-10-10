package com.things.link.enduser.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 当前设备授权下可见的事故代及公共设备信息，不携带操作人、规则或通知资料。
 * @param id 事故标识
 * @param deviceId 设备标识
 * @param deviceName 设备名称
 * @param deviceKey 设备键
 * @param alarmType 告警类型
 * @param severity 严重程度
 * @param conditionState 当前条件状态
 * @param ackState 当前确认状态
 * @param firstConditionAt 首次异常时间
 * @param activatedAt 实际触发时间，可空
 * @param clearedAt 解除时间，可空
 * @param acknowledgedAt 确认时间，可空
 * @param lastReceivedAt 最近数据接收时间
 */
public record AppAlarm(UUID id, UUID deviceId, String deviceName, String deviceKey,
        String alarmType, String severity, String conditionState, String ackState,
        Instant firstConditionAt, Instant activatedAt, Instant clearedAt,
        Instant acknowledgedAt, Instant lastReceivedAt) { }
