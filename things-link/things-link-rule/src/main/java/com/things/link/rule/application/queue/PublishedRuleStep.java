package com.things.link.rule.application.queue;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 已发布规则计划中的不可变脚本版本与动作步骤快照。 */
public record PublishedRuleStep(
        UUID ruleId,
        UUID ruleVersionId,
        long versionNumber,
        String source,
        UUID createdBy,
        List<PublishedRuleAction> actions) {

    /** 队列重试必须携带同一份源码与动作快照，不能重新读取已经发生切换的活动版本指针。 */
    public PublishedRuleStep {
        Objects.requireNonNull(ruleId, "ruleId 不能为空");
        Objects.requireNonNull(ruleVersionId, "ruleVersionId 不能为空");
        Objects.requireNonNull(source, "source 不能为空");
        Objects.requireNonNull(createdBy, "createdBy 不能为空");
        if (versionNumber < 1 || source.isBlank()) {
            throw new IllegalArgumentException("规则版本号和源码不合法");
        }
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    /** 无动作版本兼容构造，冻结空动作集合。 */
    public PublishedRuleStep(UUID ruleId, UUID ruleVersionId, long versionNumber, String source) {
        this(ruleId, ruleVersionId, versionNumber, source, ruleId, List.of());
    }

    /** S9-1 动作测试兼容构造；生产目录始终返回真实版本创建者。 */
    public PublishedRuleStep(UUID ruleId, UUID ruleVersionId, long versionNumber, String source,
                             List<PublishedRuleAction> actions) {
        this(ruleId, ruleVersionId, versionNumber, source, ruleId, actions);
    }
}
