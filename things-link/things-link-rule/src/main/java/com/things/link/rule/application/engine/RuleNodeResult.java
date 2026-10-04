package com.things.link.rule.application.engine;

import java.util.List;
import java.util.Objects;

/**
 * 单节点确定性结果；关系、派生消息和副作用意图显式分离，供 S8-2B 编排而非节点自行调度。
 *
 * @param relation 下一条封闭关系
 * @param message 派生后的不可变消息
 * @param sideEffectIntents 仅描述、不执行的副作用意图
 */
public record RuleNodeResult(
        RuleRelation relation,
        RuleMessage message,
        List<RuleSideEffectIntent> sideEffectIntents) {

    /** 冻结结果集合，避免队列接手后内容继续变化。 */
    public RuleNodeResult {
        Objects.requireNonNull(relation, "relation 不能为空");
        Objects.requireNonNull(message, "message 不能为空");
        sideEffectIntents = sideEffectIntents == null ? List.of() : List.copyOf(sideEffectIntents);
    }

    /** 首批确定性节点均不产生副作用，用统一工厂明确这一约束。 */
    public static RuleNodeResult withoutSideEffect(RuleRelation relation, RuleMessage message) {
        return new RuleNodeResult(relation, message, List.of());
    }
}
