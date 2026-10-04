package com.things.link.rule.application.queue;

/**
 * 由公平调度器调用的单条规则执行端口。
 *
 * <p>该端口故意不暴露 Kafka、数据库或线程池类型：S8-2B 的协调器可以在执行线程外处理回执、恢复消息和指标，
 * S8-2C 再把可信上行挂到同一入口，避免业务规则直接占用 Kafka consumer 线程。</p>
 */
public interface RuleExecutionProcessor {

    /**
     * 执行一条已获调度槽位的规则消息。
     *
     * @param envelope 不可变规则执行信封
     * @throws RuleExecutionException 执行失败时使用封闭分类通知协调器
     */
    RuleExecutionResult process(RuleExecutionEnvelope envelope);
}
