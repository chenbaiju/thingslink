package com.things.link.enduser.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 已规范化的告警筛选；授权范围不来自这些可选条件。
 * @param deviceId 可选设备
 * @param severity 可选等级
 * @param conditionState 可选条件状态
 * @param from 首次异常时间下界，包含
 * @param to 首次异常时间上界，不包含
 */
public record AppAlarmQuery(UUID deviceId, String severity, String conditionState, Instant from, Instant to) { }
