package com.things.link.rule.application;

import tools.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * 控制面条件规格；创建/修改场景时随动作一起冻结，写入不可变版本事实供执行处理器反序列化。
 *
 * @param nodeType 条件节点稳定类型（S9-4 仅白名单 {@code payload-property-compare}）
 * @param config 条件节点配置快照
 */
public record ConditionSpec(String nodeType, JsonNode config) {

    /** 防御性复制配置，禁止调用方在版本事实落库后继续修改。 */
    public ConditionSpec {
        Objects.requireNonNull(nodeType, "nodeType 不能为空");
        Objects.requireNonNull(config, "config 不能为空");
        if (nodeType.isBlank()) {
            throw new IllegalArgumentException("nodeType 不能为空");
        }
        config = config.deepCopy();
    }

    /** 每次读取返回副本，保持条件规格不可变。 */
    @Override
    public JsonNode config() {
        return config.deepCopy();
    }
}
