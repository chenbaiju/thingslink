package com.things.link.rule.application;

import java.util.List;

/**
 * 创建手动场景命令；条件与动作都是有序数组，随首个不可变版本冻结。
 *
 * @param name 项目内唯一名称
 * @param description 用途说明
 * @param conditions 有序条件节点（ALL_OF 短路求值）
 * @param actions 有序动作节点
 */
public record CreateSceneCommand(
        String name,
        String description,
        List<ConditionSpec> conditions,
        List<ActionSpec> actions) {
}
