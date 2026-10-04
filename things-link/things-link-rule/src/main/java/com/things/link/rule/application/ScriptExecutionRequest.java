package com.things.link.rule.application;

/**
 * 单次不可信脚本执行请求。
 *
 * @param kind 固定运行入口分类
 * @param source 返回函数的 JavaScript 源码，例如 {@code input => ({value: input.value + 1})}
 * @param inputJson 只读输入的 UTF-8 JSON 文本
 */
public record ScriptExecutionRequest(ScriptKind kind, String source, String inputJson) {

    /** 在进入线程池前拒绝缺失字段，避免无意义任务占用沙箱槽位。 */
    public ScriptExecutionRequest {
        if (kind == null || source == null || source.isBlank() || inputJson == null || inputJson.isBlank()) {
            throw new IllegalArgumentException("脚本分类、源码和输入 JSON 均不能为空");
        }
    }
}
