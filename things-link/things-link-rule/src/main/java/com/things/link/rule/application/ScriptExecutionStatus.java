package com.things.link.rule.application;

/** 不可信脚本执行的封闭结果，调用方不得依赖异常文本判断安全策略。 */
public enum ScriptExecutionStatus {
    /** 脚本在全部资源边界内成功返回 JSON。 */
    SUCCESS,
    /** 语法、类型或业务执行失败。 */
    FAILURE,
    /** CPU 或宿主墙钟上限触发。 */
    TIMEOUT,
    /** 堆、语句、栈、AST 或输出上限触发。 */
    RESOURCE_EXHAUSTED,
    /** 独立线程池及其有界队列已满。 */
    REJECTED
}
