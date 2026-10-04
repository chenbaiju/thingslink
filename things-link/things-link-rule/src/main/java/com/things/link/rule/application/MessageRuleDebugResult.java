package com.things.link.rule.application;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 无副作用调试结果；完整输出只存在于当前调用返回值，数据库只保存脱敏截断摘要。
 *
 * @param eventId 可追溯调试事件 ID
 * @param status 沙箱封闭结果
 * @param outputJson 成功时沙箱已限界的完整输出
 * @param resultCode 失败时固定结果码
 * @param duration 完整 Worker 调用耗时
 * @param occurredAt 调试事实发生 UTC 时刻
 */
public record MessageRuleDebugResult(
        UUID eventId, ScriptExecutionStatus status, String outputJson,
        String resultCode, Duration duration, Instant occurredAt) {
}
