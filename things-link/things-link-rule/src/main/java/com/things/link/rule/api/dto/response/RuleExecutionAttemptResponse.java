package com.things.link.rule.api.dto.response;

import com.things.link.rule.domain.RuleExecutionAttempt;

import java.time.Instant;

/**
 * 一次逻辑执行内单次 attempt 的时间线节点响应。
 *
 * @param attempt 尝试序号
 * @param status 该次 attempt 的封闭终态
 * @param resultCode 该次 attempt 的结果码
 * @param durationMillis 该次执行与结果发布总耗时毫秒
 * @param inputBytes 进入规则前 payload 的 UTF-8 字节数
 * @param outputBytes 成功变换后 payload 的 UTF-8 字节数，失败为零
 * @param completedAt 该次 attempt 完成 UTC 时刻
 */
public record RuleExecutionAttemptResponse(
        int attempt,
        String status,
        String resultCode,
        long durationMillis,
        int inputBytes,
        int outputBytes,
        Instant completedAt) {

    /** @return 领域 attempt 到 API 响应 */
    public static RuleExecutionAttemptResponse from(RuleExecutionAttempt attempt) {
        return new RuleExecutionAttemptResponse(
                attempt.attempt(), attempt.status(), attempt.resultCode(), attempt.durationMillis(),
                attempt.inputBytes(), attempt.outputBytes(), attempt.completedAt());
    }
}
