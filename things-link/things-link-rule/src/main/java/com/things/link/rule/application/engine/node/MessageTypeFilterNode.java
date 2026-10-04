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

import java.util.HashSet;
import java.util.Set;

/** 按代码冻结的消息类型字符串集合分流，不解析表达式或执行租户代码。 */
@Component
public final class MessageTypeFilterNode implements RuleNode {

    /** 节点注册表中的稳定类型。 */
    public static final String TYPE = "message-type-filter";
    /** JSON Schema 只允许 messageTypes，避免配置拼写错误被静默忽略。 */
    private static final JsonNode CONFIG_SCHEMA = new ObjectMapper().readTree("""
            {"type":"object","additionalProperties":false,"required":["messageTypes"],
             "properties":{"messageTypes":{"type":"array","minItems":1,"uniqueItems":true,
             "items":{"type":"string","minLength":1,"maxLength":64}}}}
            """);

    /** {@inheritDoc} */
    @Override
    public String type() {
        return TYPE;
    }

    /** {@inheritDoc} */
    @Override
    public JsonNode configSchema() {
        return CONFIG_SCHEMA.deepCopy();
    }

    /** {@inheritDoc} */
    @Override
    public RuleNodeValidation validate(JsonNode config) {
        if (config == null || !config.isObject() || config.size() != 1 || !config.has("messageTypes")) {
            return RuleNodeValidation.invalid("config 必须且只能包含 messageTypes");
        }
        JsonNode types = config.get("messageTypes");
        if (!types.isArray() || types.isEmpty() || types.size() > 32) {
            return RuleNodeValidation.invalid("messageTypes 必须包含 1 到 32 项");
        }
        Set<String> unique = new HashSet<>();
        for (JsonNode item : types) {
            if (!item.isString() || item.stringValue().isBlank() || item.stringValue().length() > 64
                    || !unique.add(item.stringValue())) {
                return RuleNodeValidation.invalid("messageTypes 项必须是唯一的非空字符串");
            }
        }
        return RuleNodeValidation.success();
    }

    /** {@inheritDoc} */
    @Override
    public RuleNodeResult execute(RuleMessage message, JsonNode config, RuleExecutionContext context) {
        boolean matches = false;
        for (JsonNode item : config.get("messageTypes")) {
            if (message.type().equals(item.stringValue())) {
                matches = true;
                break;
            }
        }
        return RuleNodeResult.withoutSideEffect(matches ? RuleRelation.TRUE : RuleRelation.FALSE, message);
    }
}
