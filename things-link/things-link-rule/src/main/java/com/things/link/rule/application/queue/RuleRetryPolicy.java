package com.things.link.rule.application.queue;

import java.time.Duration;
import java.util.Objects;

/**
 * S8-2B 冻结的规则执行有限退避策略。
 *
 * <p>首次执行加两次重试，故最大尝试次数固定为三次；第一次失败等待一分钟，第二次失败等待五分钟。固定档位使部署 Topic、
 * 监控标签和恢复演练均可预测，也避免无限重试阻塞混合租户分区。</p>
 */
public final class RuleRetryPolicy {

    /** 包含首次执行在内的最大尝试次数。 */
    public static final int MAX_ATTEMPTS = 3;
    /** 第一次失败后的退避档位。 */
    public static final Duration FIRST_RETRY_DELAY = Duration.ofMinutes(1);
    /** 第二次失败后的退避档位。 */
    public static final Duration SECOND_RETRY_DELAY = Duration.ofMinutes(5);

    /**
     * 按失败分类和已执行次数决定下一跳。
     *
     * @param envelope 当前不可变执行信封
     * @param failure 封闭失败分类
     * @return 有限 retry Topic 或规则 DLQ 的决定
     */
    public RuleRetryDecision decide(RuleExecutionEnvelope envelope, RuleExecutionFailure failure) {
        Objects.requireNonNull(envelope, "envelope 不能为空");
        Objects.requireNonNull(failure, "failure 不能为空");
        if (!failure.retryable() || envelope.attempt() >= MAX_ATTEMPTS) {
            return new RuleRetryDecision(RuleRetryDecision.Disposition.DEAD_LETTER, Duration.ZERO);
        }
        Duration delay = envelope.attempt() == 1 ? FIRST_RETRY_DELAY : SECOND_RETRY_DELAY;
        return new RuleRetryDecision(RuleRetryDecision.Disposition.RETRY, delay);
    }
}
