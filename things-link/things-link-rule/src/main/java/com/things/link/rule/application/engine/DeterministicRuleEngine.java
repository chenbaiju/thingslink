package com.things.link.rule.application.engine;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * S8-2A 单节点确定性执行入口；队列、公平调度和拓扑推进由 S8-2B 在此契约之外实现。
 */
@Component
public final class DeterministicRuleEngine {

    /** 按固定类型索引节点，重复类型在启动时立即失败。 */
    private final Map<String, RuleNode> nodes;

    /**
     * 从 Spring 节点集合构建不可变注册表。
     *
     * @param nodes 当前版本支持的节点
     */
    public DeterministicRuleEngine(List<RuleNode> nodes) {
        this.nodes = nodes.stream().collect(Collectors.toUnmodifiableMap(RuleNode::type, Function.identity()));
    }

    /**
     * 校验配置后执行一个节点；未知类型或非法配置在进入生产队列前 fail-closed。
     *
     * @param type 节点稳定类型
     * @param config 节点配置
     * @param message 不可变输入消息
     * @param context 受限执行上下文
     * @return 确定性节点结果
     */
    public RuleNodeResult execute(
            String type,
            JsonNode config,
            RuleMessage message,
            RuleExecutionContext context) {
        RuleNodeValidation validation = validate(type, config);
        if (!validation.valid()) {
            throw new IllegalArgumentException("规则节点配置不合法: " + String.join(",", validation.errors()));
        }
        Objects.requireNonNull(message, "message 不能为空");
        Objects.requireNonNull(context, "context 不能为空");
        return nodes.get(type).execute(message, config.deepCopy(), context);
    }

    /**
     * 仅校验节点配置；控制面在把动作写入不可变版本事实前必须调用，非法配置立即 fail-closed。
     *
     * @param type 节点稳定类型
     * @param config 节点配置
     * @return 封闭校验结果
     */
    public RuleNodeValidation validate(String type, JsonNode config) {
        Objects.requireNonNull(type, "type 不能为空");
        Objects.requireNonNull(config, "config 不能为空");
        RuleNode node = nodes.get(type);
        if (node == null) {
            return RuleNodeValidation.invalid("不支持的规则节点类型: " + type);
        }
        return node.validate(config);
    }
}
