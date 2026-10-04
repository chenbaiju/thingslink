package com.things.link.rule.application.queue;

import tools.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * 已发布规则版本中冻结的动作步骤；与脚本源码同属一条不可变版本事实，队列重试必须原样复用。
 *
 * @param nodeType 代码冻结的动作节点类型
 * @param config 动作节点配置快照
 */
public record PublishedRuleAction(String nodeType, JsonNode config) {

    /** 防御性复制配置，禁止队列接手后动作事实继续变化。 */
    public PublishedRuleAction {
        Objects.requireNonNull(nodeType, "nodeType 不能为空");
        Objects.requireNonNull(config, "config 不能为空");
        if (nodeType.isBlank()) {
            throw new IllegalArgumentException("nodeType 不能为空");
        }
        config = config.deepCopy();
    }

    /** 每次读取返回副本，保持动作事实不可变。 */
    @Override
    public JsonNode config() {
        return config.deepCopy();
    }
}
