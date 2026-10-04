package com.things.link.rule.domain;

import java.time.Instant;

/**
 * 一次逻辑执行内单次 attempt 的时间线节点，按 attempt 序号升序展示。
 *
 * <p>与 {@link RuleExecutionSummary} 互补：列表给聚合摘要，详情抽屉给本时间线；都不含载荷、源码或异常正文。</p>
 *
 * @param attempt 尝试序号，从 1 递增最多到 3
 * @param status 该次 attempt 的封闭终态
 * @param resultCode 该次 attempt 的结果码
 * @param durationMillis 该次执行与结果发布总耗时毫秒
 * @param inputBytes 进入规则前 payload 的 UTF-8 字节数
 * @param outputBytes 成功变换后 payload 的 UTF-8 字节数，失败为零
 * @param completedAt 该次 attempt 完成 UTC 时刻
 */
public record RuleExecutionAttempt(
        int attempt,
        String status,
        String resultCode,
        long durationMillis,
        int inputBytes,
        int outputBytes,
        Instant completedAt) {
}
