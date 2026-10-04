package com.things.link.rule.api.dto.response;

import com.things.link.rule.domain.RuleExecutionSummary;

import java.time.Instant;
import java.util.UUID;

/**
 * 上行规则执行日志的执行级聚合摘要响应。
 *
 * @param messageId 原上行消息 ID
 * @param ruleId 规则定义 ID
 * @param ruleName 查询时刻的规则当前名称
 * @param ruleVersionId 执行绑定的不可变版本 ID
 * @param attemptCount 该逻辑执行已产生的 attempt 次数
 * @param status 最新 attempt 的封闭终态
 * @param resultCode 最新 attempt 的结果码
 * @param cumulativeDurationMillis 全部 attempt 累计耗时毫秒
 * @param firstAttemptAt 首次尝试完成 UTC 时刻
 * @param lastAttemptAt 最后尝试完成 UTC 时刻
 */
public record RuleExecutionSummaryResponse(
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

    /** @return 领域摘要到 API 响应 */
    public static RuleExecutionSummaryResponse from(RuleExecutionSummary summary) {
        return new RuleExecutionSummaryResponse(
                summary.messageId(), summary.ruleId(), summary.ruleName(), summary.ruleVersionId(),
                summary.attemptCount(), summary.status(), summary.resultCode(),
                summary.cumulativeDurationMillis(), summary.firstAttemptAt(), summary.lastAttemptAt());
    }
}
