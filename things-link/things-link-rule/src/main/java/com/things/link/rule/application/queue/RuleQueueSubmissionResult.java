package com.things.link.rule.application.queue;

/**
 * 规则公平队列的有限提交结果。
 *
 * <p>结果枚举同时为 S8-2B 的低基数可观测性保留稳定维度；禁止改成包含租户或项目 ID 的自由文本。</p>
 */
public enum RuleQueueSubmissionResult {

    /** 工作已经进入运行态或有限等待队列。 */
    ACCEPTED,
    /** 租户策略等待额度已满。 */
    TENANT_QUEUE_FULL,
    /** 项目策略等待额度已满。 */
    PROJECT_QUEUE_FULL,
    /** 实例总等待项物理上限已满。 */
    INSTANCE_QUEUE_FULL,
    /** 实例活跃租户物理上限已满。 */
    ACTIVE_TENANT_LIMIT,
    /** 实例活跃项目物理上限已满。 */
    ACTIVE_PROJECT_LIMIT,
    /** 调度器已关闭。 */
    CLOSED
}
