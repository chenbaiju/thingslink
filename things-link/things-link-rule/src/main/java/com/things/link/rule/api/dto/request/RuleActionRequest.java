package com.things.link.rule.api.dto.request;
/** 动作类型与完整配置，统一由用途目录映射为业务错误。 */
public record RuleActionRequest(String nodeType, tools.jackson.databind.JsonNode config) { }
