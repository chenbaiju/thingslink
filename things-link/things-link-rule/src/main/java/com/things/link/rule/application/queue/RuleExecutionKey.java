package com.things.link.rule.application.queue;

import java.util.Objects;
import java.util.UUID;

/**
 * 规则执行的稳定幂等身份。
 *
 * <p>S8-2B 要求 Kafka 重投、退避重试与人工重放始终复用原上行消息和不可变规则版本；不能从活动指针重新解析版本，
 * 否则同一输入会在规则变更后产生不同结果。该键因此把项目、消息、规则与版本同时纳入唯一性边界。</p>
 *
 * @param projectId 数据隔离项目 ID
 * @param messageId 原始消息的稳定幂等 ID
 * @param ruleId 规则定义 ID
 * @param ruleVersionId 不可变规则版本 ID
 */
public record RuleExecutionKey(UUID projectId, UUID messageId, UUID ruleId, UUID ruleVersionId) {

    /** 拒绝不完整身份，避免持久回执把不同执行错误合并。 */
    public RuleExecutionKey {
        Objects.requireNonNull(projectId, "projectId 不能为空");
        Objects.requireNonNull(messageId, "messageId 不能为空");
        Objects.requireNonNull(ruleId, "ruleId 不能为空");
        Objects.requireNonNull(ruleVersionId, "ruleVersionId 不能为空");
    }
}
