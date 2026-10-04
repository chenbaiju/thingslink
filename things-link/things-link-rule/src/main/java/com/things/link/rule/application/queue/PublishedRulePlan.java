package com.things.link.rule.application.queue;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** normalized 入口一次性冻结的有序活动规则计划。 */
public record PublishedRulePlan(List<PublishedRuleStep> steps) {

    /** 防御性复制并拒绝重复规则；顺序由 catalog 冻结，Worker 只能串行消费。 */
    public PublishedRulePlan {
        Objects.requireNonNull(steps, "steps 不能为空");
        steps = List.copyOf(steps);
        HashSet<Object> ruleIds = new HashSet<>();
        for (PublishedRuleStep step : steps) {
            Objects.requireNonNull(step, "规则步骤不能为空");
            if (!ruleIds.add(step.ruleId())) {
                throw new IllegalArgumentException("同一执行计划不得重复规则");
            }
        }
    }

    /** @return 是否没有已发布规则 */
    public boolean isEmpty() {
        return steps.isEmpty();
    }
}
