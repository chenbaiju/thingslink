package com.things.link.rule.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次逻辑规则执行的聚合摘要：把同一 {@code (messageId, ruleId, ruleVersionId)} 的多次 attempt 压成一行。
 *
 * <p>状态取自 attempt 序号最大的那行（SUCCESS=成功、DEAD_LETTER=永久失败、RETRY_SCHEDULED=等待重试），
 * 结果码与耗时取该行与全部 attempt 的累计值；不携带 payload、源码或异常正文。</p>
 *
 * @param messageId 原上行消息 ID
 * @param ruleId 规则定义 ID
 * @param ruleName 查询时刻的规则当前名称，仅用于展示，非执行时快照
 * @param ruleVersionId 执行绑定且重试不变的不可变版本 ID
 * @param attemptCount 该逻辑执行已产生的 attempt 次数
 * @param status 最新 attempt 的封闭终态
 * @param resultCode 最新 attempt 的结果码（SUCCESS 或封闭失败分类）
 * @param cumulativeDurationMillis 全部 attempt 累计耗时毫秒
 * @param firstAttemptAt 首次尝试完成 UTC 时刻
 * @param lastAttemptAt 最后尝试完成 UTC 时刻，兼作列表排序键
 */
public record RuleExecutionSummary(
        UUID messageId,
        UUID ruleId,
        String ruleName,
        UUID ruleVersionId,
        int attemptCount,
        String status,
        String resultCode,
        long cumulativeDurationMillis,
        Instant firstAttemptAt,
        Instant lastAttemptAt) {
}
