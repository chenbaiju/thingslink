package com.things.link.rule.application.engine;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 单节点执行上下文；不暴露 Spring Bean、Repository 或网络客户端，维持确定性边界。
 *
 * @param ruleId 规则定义 ID
 * @param ruleVersionId 不可变规则版本 ID
 * @param nodeId 当前节点 ID
 * @param attempt 当前队列尝试次数，首次为 1
 * @param startedAt 本次规则执行的固定开始时刻
 */
public record RuleExecutionContext(
        UUID ruleId,
        UUID ruleVersionId,
        UUID nodeId,
        int attempt,
        Instant startedAt) {

    /** 拒绝缺失身份与非法尝试次数，避免重试语义到 S8-2B 才变得含糊。 */
    public RuleExecutionContext {
        Objects.requireNonNull(ruleId, "ruleId 不能为空");
        Objects.requireNonNull(ruleVersionId, "ruleVersionId 不能为空");
        Objects.requireNonNull(nodeId, "nodeId 不能为空");
        Objects.requireNonNull(startedAt, "startedAt 不能为空");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt 必须大于等于 1");
        }
    }
}
