package com.things.link.rule.application.queue;

/** 持久回执抢占的封闭结果，避免把仍在执行误判为可提交位点的重放。 */
public enum RuleExecutionReceiptClaim {
    /** 当前 Worker 已取得租约。 */
    ACQUIRED,
    /** continuation 或终态恢复消息已经持久化，可安全吸收重投。 */
    COMPLETED,
    /** 另一 Worker 的有效租约仍在执行，当前源记录不得提交位点。 */
    BUSY
}
