package com.things.link.rule.application.queue;

/** 生产规则执行日志的只追加端口。 */
public interface RuleExecutionLogStore {

    /**
     * 追加一次 attempt 的封闭结果；相同执行身份与 attempt 的重复完成通知被持久层吸收。
     *
     * @param entry 不含载荷、源码或异常正文的最小日志
     * @return 新增一行时为 true，重复 attempt 时为 false
     */
    boolean append(RuleExecutionLogEntry entry);
}
