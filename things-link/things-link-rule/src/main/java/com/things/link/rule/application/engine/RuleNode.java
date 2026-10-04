package com.things.link.rule.application.engine;

import tools.jackson.databind.JsonNode;

/**
 * 最小规则节点契约；配置描述、校验和执行全部由同一节点拥有，防止三套口径漂移。
 */
public interface RuleNode {

    /** 返回代码冻结的稳定节点类型。 */
    String type();

    /** 返回供未来控制面生成配置的 JSON Schema 快照。 */
    JsonNode configSchema();

    /** 在执行前校验配置，不读取业务数据库或外部系统。 */
    RuleNodeValidation validate(JsonNode config);

    /** 以不可变消息和受限上下文执行纯计算。 */
    RuleNodeResult execute(RuleMessage message, JsonNode config, RuleExecutionContext context);
}
