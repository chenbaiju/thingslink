package com.things.link.rule.application.engine.node;

import com.things.link.rule.application.engine.RuleExecutionContext;
import com.things.link.rule.application.engine.RuleMessage;
import com.things.link.rule.application.engine.RuleNode;
import com.things.link.rule.application.engine.RuleNodeResult;
import com.things.link.rule.application.engine.RuleNodeValidation;
import com.things.link.rule.application.engine.RuleRelation;
import com.things.link.rule.application.engine.RuleSideEffectIntent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

/** S9-2 设备命令动作：只生成经过结构校验的命令意图，不读库、不发消息。 */
@Component
public final class DeviceCommandActionNode implements RuleNode {
    /** 节点稳定类型。 */ public static final String TYPE = "device-command-action";
    /** 桥接层白名单意图类型。 */ public static final String INTENT_TYPE = "device-command";
    /** 禁止未知字段把路由事实混入租户配置。 */ private static final Set<String> FIELDS = Set.of("commandKey", "input");
    /** 控制面展示用最小 Schema。 */ private static final JsonNode SCHEMA = new ObjectMapper().readTree("""
            {"type":"object","additionalProperties":false,"required":["commandKey","input"],
             "properties":{"commandKey":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,64}$"},
             "input":{"type":"object"}}}
            """);

    /** {@inheritDoc} */ @Override public String type() { return TYPE; }
    /** {@inheritDoc} */ @Override public JsonNode configSchema() { return SCHEMA.deepCopy(); }

    /** {@inheritDoc} */
    @Override public RuleNodeValidation validate(JsonNode config) {
        if (config == null || !config.isObject() || config.size() != 2
                || config.properties().stream().anyMatch(entry -> !FIELDS.contains(entry.getKey()))
                || !config.path("commandKey").isString()
                || !config.path("commandKey").asText().matches("[A-Za-z0-9_-]{1,64}")
                || !config.path("input").isObject()) {
            return RuleNodeValidation.invalid("config 只允许合法 commandKey 与 input 对象");
        }
        return RuleNodeValidation.success();
    }

    /** {@inheritDoc} */
    @Override public RuleNodeResult execute(RuleMessage message, JsonNode config, RuleExecutionContext context) {
        JsonNode payload = new ObjectMapper().createObjectNode()
                .put("deviceId", message.deviceId().toString())
                .put("commandKey", config.path("commandKey").asText())
                .set("input", config.path("input").deepCopy());
        return new RuleNodeResult(RuleRelation.SUCCESS, message,
                List.of(new RuleSideEffectIntent(INTENT_TYPE, payload)));
    }
}
