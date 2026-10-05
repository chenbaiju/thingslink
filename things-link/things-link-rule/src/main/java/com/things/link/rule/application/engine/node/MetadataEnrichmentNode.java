package com.things.link.rule.application.engine.node;

import com.things.link.rule.application.engine.RuleExecutionContext;
import com.things.link.rule.application.engine.RuleMessage;
import com.things.link.rule.application.engine.RuleNode;
import com.things.link.rule.application.engine.RuleNodeResult;
import com.things.link.rule.application.engine.RuleNodeValidation;
import com.things.link.rule.application.engine.RuleRelation;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 以静态字符串补全 metadata；默认不覆盖已有键，且不读取外部属性服务。 */
@Component
public final class MetadataEnrichmentNode implements RuleNode {

    /** 节点注册表中的稳定类型。 */
    public static final String TYPE = "metadata-enrichment";
    /** ADR 0017 的可信身份永远不能伪装成可写 metadata。 */
    private static final Set<String> RESERVED_KEYS = Set.of(
            "messageId", "tenantId", "projectId", "deviceId", "traceId", "occurredAt", "type");
    /** 配置仅包含有界字符串 values 与显式覆盖开关。 */
    private static final JsonNode CONFIG_SCHEMA = new ObjectMapper().readTree("""
            {"type":"object","additionalProperties":false,"required":["values"],
             "properties":{"values":{"type":"object","minProperties":1,"maxProperties":32,
             "additionalProperties":{"type":"string","maxLength":256}},"overwrite":{"type":"boolean","default":false}}}
            """);

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public String type() {
        return TYPE;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public JsonNode configSchema() {
        return CONFIG_SCHEMA.deepCopy();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public RuleNodeValidation validate(JsonNode config) {
        if (config == null || !config.isObject() || config.size() < 1 || config.size() > 2
                || !config.has("values") || !config.get("values").isObject()
                || (config.has("overwrite") && !config.get("overwrite").isBoolean())
                || config.properties().stream().anyMatch(entry -> !Set.of("values", "overwrite")
                        .contains(entry.getKey()))) {
            return RuleNodeValidation.invalid("config 只允许 values 和可选 overwrite");
        }
        JsonNode values = config.get("values");
        if (values.isEmpty() || values.size() > 32) {
            return RuleNodeValidation.invalid("values 必须包含 1 到 32 项");
        }
        Set<String> names = new HashSet<>();
        for (Map.Entry<String, JsonNode> entry : values.properties()) {
            if (entry.getKey().isBlank() || entry.getKey().length() > 64 || RESERVED_KEYS.contains(entry.getKey())
                    || !entry.getValue().isString() || entry.getValue().stringValue().length() > 256
                    || !names.add(entry.getKey())) {
                return RuleNodeValidation.invalid("metadata 键值不合法或试图覆盖可信身份");
            }
        }
        return RuleNodeValidation.success();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public RuleNodeResult execute(RuleMessage message, JsonNode config, RuleExecutionContext context) {
        Map<String, String> enriched = new HashMap<>(message.metadata());
        boolean overwrite = config.path("overwrite").asBoolean(false);
        for (Map.Entry<String, JsonNode> entry : config.get("values").properties()) {
            if (overwrite) {
                enriched.put(entry.getKey(), entry.getValue().stringValue());
            } else {
                enriched.putIfAbsent(entry.getKey(), entry.getValue().stringValue());
            }
        }
        return RuleNodeResult.withoutSideEffect(RuleRelation.SUCCESS, message.withMetadata(enriched));
    }
}
