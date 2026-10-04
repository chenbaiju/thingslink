package com.things.link.rule.application;

import java.util.List;

/**
 * 创建消息规则及首个不可变版本的命令。
 *
 * @param name 项目内唯一名称
 * @param description 用途说明
 * @param source JavaScript 函数源码
 * @param actions 随版本一起冻结的动作规格；可空表示无动作
 */
public record CreateMessageRuleCommand(
        String name,
        String description,
        String source,
        List<ActionSpec> actions) {

    /** 固化动作集合，禁止调用方在版本事实落库后继续修改。 */
    public CreateMessageRuleCommand {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    /** 无动作规则兼容构造。 */
    public CreateMessageRuleCommand(String name, String description, String source) {
        this(name, description, source, List.of());
    }
}
