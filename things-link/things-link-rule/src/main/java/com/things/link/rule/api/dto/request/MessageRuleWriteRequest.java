package com.things.link.rule.api.dto.request;
/** 规则创建或修订请求；修订必须提供expectedVersion，动作完整替换而非增量合并。 */
public record MessageRuleWriteRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=128) String name,
        @jakarta.validation.constraints.Size(max=512) String description,
        String source, Long expectedVersion, java.util.List<RuleActionRequest> actions) { }
