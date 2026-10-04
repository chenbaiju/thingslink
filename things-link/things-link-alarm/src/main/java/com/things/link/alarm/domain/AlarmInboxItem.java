package com.things.link.alarm.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * ADR0093：ACTIVATED事件与当前实例的展示投影，read只代表当前账号的独立回执。
 * @param eventId 不可变事件身份
 * @param instanceId 当前事故身份
 * @param receivedAt 事件平台接收时间
 * @param severity 当前实例严重程度
 * @param alarmType 当前实例告警类型
 * @param read 当前账号是否已有显式回执
 */
public record AlarmInboxItem(UUID eventId, UUID instanceId, Instant receivedAt,
                             AlarmRule.Severity severity, String alarmType, boolean read) {
}
