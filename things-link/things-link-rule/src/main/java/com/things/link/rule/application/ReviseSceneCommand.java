package com.things.link.rule.application;

import java.util.List;

/**
 * 修改手动场景命令；追加新不可变版本，既有版本绝不覆盖。
 *
 * @param name 项目内唯一名称
 * @param description 用途说明
 * @param expectedVersion 定义乐观锁版本
 * @param conditions 有序条件节点
 * @param actions 有序动作节点
 */
public record ReviseSceneCommand(
        String name,
        String description,
        long expectedVersion,
        List<ConditionSpec> conditions,
        List<ActionSpec> actions) {
}
