package com.things.link.rule.application;

import tools.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * 控制面动作规格；创建/修改规则时随源码一起冻结，写入不可变版本事实供生产计划反序列化。
 *
 * @param nodeType 动作节点稳定类型
 * @param config 动作节点配置快照
 */
public record ActionSpec(String nodeType, JsonNode config) {

    /** 防御性复制配置，禁止调用方在版本事实落库后继续修改。 */
    public ActionSpec {
        Objects.requireNonNull(nodeType, "nodeType 不能为空");
        Objects.requireNonNull(config, "config 不能为空");
        if (nodeType.isBlank()) {
            throw new IllegalArgumentException("nodeType 不能为空");
        }
        config = config.deepCopy();
    }

    /** 每次读取返回副本，保持动作规格不可变。 */
    @Override
    public JsonNode config() {
        return config.deepCopy();
    }
}
