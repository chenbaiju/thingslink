package com.things.link.rule.application.queue;

import com.things.link.rule.application.engine.RuleMessage;
import com.things.link.rule.application.engine.RuleSideEffectIntent;

import java.util.List;
import java.util.Objects;

/**
 * 单条规则执行结果；派生消息与副作用意图显式分离，副作用交由 S9 Outbox 边界投递，处理器不自行执行。
 *
 * @param message 脚本派生后的不可变消息
 * @param sideEffectIntents 动作节点产生的、仅描述不执行的副作用意图
 */
public record RuleExecutionResult(
        RuleMessage message,
        List<RuleSideEffectIntent> sideEffectIntents) {

    /** 固化结果集合，避免队列接手后内容继续变化。 */
    public RuleExecutionResult {
        Objects.requireNonNull(message, "message 不能为空");
        sideEffectIntents = sideEffectIntents == null ? List.of() : List.copyOf(sideEffectIntents);
    }

    /** 无动作版本仍返回统一结果，副作用集合为空。 */
    public static RuleExecutionResult withoutSideEffects(RuleMessage message) {
        return new RuleExecutionResult(message, List.of());
    }
}
