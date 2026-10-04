package com.things.link.rule.application.queue;

import com.things.link.rule.application.engine.RuleMessage;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 进入规则执行队列后的不可变工作信封。
 *
 * <p>身份同时在 {@link RuleExecutionKey} 和 {@link RuleMessage} 中出现是刻意的交叉校验：消息只能来自已经确权的
 * 上行快照，而队列键决定回执与重放边界。两者不一致必须在入队前失败，不能由重试链路悄悄改写可信身份。</p>
 *
 * @param key 执行幂等身份
 * @param tenantId owner tenant，用于外层公平调度和共享配额
 * @param message 已确权且不可变的规则消息快照
 * @param attempt 已开始的尝试次数，首次为 1
 * @param enqueuedAt 本次进入队列的 UTC 时刻
 */
public record RuleExecutionEnvelope(
        RuleExecutionKey key,
        UUID tenantId,
        RuleMessage message,
        PublishedRulePlan plan,
        int attempt,
        Instant enqueuedAt) {

    /** 把队列信封绑定到同一份可信消息身份，禁止重试时替换 messageId 或规则版本。 */
    public RuleExecutionEnvelope {
        Objects.requireNonNull(key, "key 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(message, "message 不能为空");
        Objects.requireNonNull(plan, "plan 不能为空");
        Objects.requireNonNull(enqueuedAt, "enqueuedAt 不能为空");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt 必须大于等于 1");
        }
        if (!tenantId.equals(message.tenantId())
                || !key.projectId().equals(message.projectId())
                || !key.messageId().equals(message.messageId())) {
            throw new IllegalArgumentException("队列身份必须与已确权规则消息一致");
        }
        if (plan.isEmpty()) {
            throw new IllegalArgumentException("进入规则队列的执行计划不能为空");
        }
        PublishedRuleStep first = plan.steps().getFirst();
        if (!key.ruleId().equals(first.ruleId()) || !key.ruleVersionId().equals(first.ruleVersionId())) {
            throw new IllegalArgumentException("回执键必须绑定执行计划首个不可变版本");
        }
    }

    /** 兼容 S8-2B 队列契约测试；生产入口必须使用携带真实源码的完整构造器。 */
    public RuleExecutionEnvelope(RuleExecutionKey key, UUID tenantId, RuleMessage message,
                                 int attempt, Instant enqueuedAt) {
        this(key, tenantId, message, new PublishedRulePlan(List.of(new PublishedRuleStep(
                key.ruleId(), key.ruleVersionId(), 1L, "input => input"))), attempt, enqueuedAt);
    }

    /**
     * 在不改变任何幂等身份的前提下生成下一次尝试。
     *
     * @param nextEnqueuedAt 下一次进入执行队列的 UTC 时刻
     * @return 仅尝试次数与入队时刻变化的新信封
     */
    public RuleExecutionEnvelope nextAttempt(Instant nextEnqueuedAt) {
        return new RuleExecutionEnvelope(key, tenantId, message, plan, attempt + 1, nextEnqueuedAt);
    }
}
