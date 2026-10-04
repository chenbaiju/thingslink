package com.things.link.rule.application.queue;

/** 规则队列入口的有限结果，供 S8-2C Kafka 钩子决定原 offset 是否可以提交。 */
public enum RuleExecutionSubmission {
    /** 已进入公平队列。 */
    ACCEPTED,
    /** 相同消息与版本已有进行中或完成回执。 */
    REPLAYED,
    /** 队列拒绝后已进入有限退避 Topic。 */
    RETRY_SCHEDULED,
    /** 永久拒绝或重试耗尽后已进入规则 DLQ。 */
    DEAD_LETTER
}
