package com.things.link.rule.application;

import java.util.List;

/**
 * 修改定义并追加脚本版本的命令。
 *
 * @param name 新名称
 * @param description 新用途说明
 * @param source 新 JavaScript 函数源码
 * @param expectedVersion 规则定义乐观锁版本
 * @param actions 随新版本一起冻结的动作规格；可空表示无动作
 */
public record ReviseMessageRuleCommand(
        String name,
        String description,
        String source,
        long expectedVersion,
        List<ActionSpec> actions) {

    /** 固化动作集合，禁止调用方在版本事实落库后继续修改。 */
    public ReviseMessageRuleCommand {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    /** 无动作修订兼容构造。 */
    public ReviseMessageRuleCommand(String name, String description, String source, long expectedVersion) {
        this(name, description, source, expectedVersion, List.of());
    }
}
