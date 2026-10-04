package com.things.link.rule.application.queue;

/**
 * 规则执行回执的幂等端口。
 *
 * <p>实现必须以 {@link RuleExecutionKey} 为原子唯一键抢占执行权；Kafka offset 提交与进程崩溃之间必然发生重投，
 * 因而不能把“已看过消息”仅保存在内存。S8-2B 只定义端口，后续 JDBC 事实实现负责持久化与短租约。</p>
 */
public interface RuleExecutionReceiptStore {

    /**
     * 原子抢占执行权。
     *
     * @param envelope 原消息、不可变规则版本与当前 attempt 组成的执行信封
     * @return true 表示本调用拥有执行权；false 表示已有完成或进行中的回执
     */
    RuleExecutionReceiptClaim tryClaim(RuleExecutionEnvelope envelope);

    /**
     * 标记执行已完成，使后续 Kafka 重投可被幂等吸收。
     *
     * @param envelope 原子抢占成功的当前执行信封
     */
    void complete(RuleExecutionEnvelope envelope);

    /**
     * 释放未完成抢占，使可重试失败能够由后续消息重新取得执行权。
     *
     * @param envelope 需要释放的当前执行信封
     */
    void release(RuleExecutionEnvelope envelope);
}
