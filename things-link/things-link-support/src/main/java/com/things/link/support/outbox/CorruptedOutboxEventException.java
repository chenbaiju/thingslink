package com.things.link.support.outbox;

/** 原Outbox行不能满足持久事件契约；区别于数据库查询、连接及事务异常。 */
public class CorruptedOutboxEventException extends RuntimeException {
    /** @param cause 仅OutboxEvent构造时确定的元数据损坏，不得包装JDBC异常 */
    public CorruptedOutboxEventException(IllegalArgumentException cause) { super("原Outbox事件元数据损坏", cause); }
}
