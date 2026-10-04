package com.things.link.alarm.application;

import java.util.UUID;

/**
 * 规则告警动作的低敏执行结果；调用方可落投递事实，但不得复制告警正文或通知目标。
 *
 * @param alarmInstanceId 命中的告警实例；不存在活动代时允许为空
 * @param changed 本次调用是否真正完成状态迁移并追加不可变事件
 */
public record RuleAlarmActionResult(UUID alarmInstanceId, boolean changed) {
}
