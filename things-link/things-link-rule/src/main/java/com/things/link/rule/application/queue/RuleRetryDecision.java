package com.things.link.rule.application.queue;

import java.time.Duration;
import java.util.Objects;

/**
 * 对一次规则失败作出的有限恢复决定。
 *
 * <p>进入 DLQ 时没有延迟；进入 retry Topic 时延迟必须来自 {@link RuleRetryPolicy} 的固定档位，调用方不能以任意
 * Duration 绕过已冻结的最大尝试次数。</p>
 *
 * @param disposition 重试或死信
 * @param delay 下次尝试前的固定等待；死信时为零
 */
public record RuleRetryDecision(Disposition disposition, Duration delay) {

    /** 两种终结去向；不允许不受控的“无限重试”第三状态。 */
    public enum Disposition {
        /** 发布到有限退避 retry Topic。 */
        RETRY,
        /** 发布到规则 DLQ。 */
        DEAD_LETTER
    }

    /** 约束决定与延迟的组合语义。 */
    public RuleRetryDecision {
        Objects.requireNonNull(disposition, "disposition 不能为空");
        Objects.requireNonNull(delay, "delay 不能为空");
        if (delay.isNegative() || (disposition == Disposition.DEAD_LETTER && !delay.isZero())
                || (disposition == Disposition.RETRY && delay.isZero())) {
            throw new IllegalArgumentException("恢复决定与延迟不匹配");
        }
    }

    /** @return 是否应发布到 retry Topic */
    public boolean retry() {
        return disposition == Disposition.RETRY;
    }
}
