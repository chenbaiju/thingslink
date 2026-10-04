package com.things.link.rule.application;

/**
 * 脚本运行入口的固定分类。
 *
 * <p>该枚举同时作为低基数指标标签；禁止把规则 ID、租户 ID 或任意脚本名称放入指标。</p>
 */
public enum ScriptKind {
    /** 编解码脚本；S8 后续切片接入。 */
    CODEC,
    /** 消息规则转换脚本。 */
    RULE,
    /** 云函数脚本；当前只冻结指标分类。 */
    FUNCTION,
    /** 无副作用调试执行。 */
    DEBUG
}
