package com.things.link.rule.application;

/** 租户脚本唯一执行端口；任何规则、编解码或云函数都不得绕过它直接创建 Polyglot Context。 */
public interface ScriptSandbox {

    /**
     * 只解析源码并确认其为函数，不调用租户函数正文。
     *
     * @param kind 固定脚本分类
     * @param source JavaScript 函数源码
     * @return 不回传解析异常正文的验证结果
     */
    ScriptValidationResult validate(ScriptKind kind, String source);

    /**
     * 在不可信 isolate 和独立有界线程池中执行一次脚本。
     *
     * @param request 已冻结的源码与 JSON 输入
     * @return 不抛出租户异常的封闭结果
     */
    ScriptExecutionResult execute(ScriptExecutionRequest request);
}
