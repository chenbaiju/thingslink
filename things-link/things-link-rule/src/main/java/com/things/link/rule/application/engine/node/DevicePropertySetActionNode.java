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

/** S9-2 属性设置动作：只冻结非空属性对象，物模型可下发性由设备域权威端口复核。 */
@Component
public final class DevicePropertySetActionNode implements RuleNode {
    /** 节点稳定类型。 */ public static final String TYPE = "device-property-set-action";
    /** 桥接层白名单意图类型。 */ public static final String INTENT_TYPE = "device-property-set";
    /** 控制面展示用最小 Schema。 */ private static final JsonNode SCHEMA = new ObjectMapper().readTree("""
            {"type":"object","additionalProperties":false,"required":["properties"],
             "properties":{"properties":{"type":"object","minProperties":1}}}
            """);

    /** 沿用接口定义的契约。{@inheritDoc} */ @Override public String type() { return TYPE; }
    /** 沿用接口定义的契约。{@inheritDoc} */ @Override public JsonNode configSchema() { return SCHEMA.deepCopy(); }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public RuleNodeValidation validate(JsonNode config) {
        if (config == null || !config.isObject() || config.size() != 1
                || !config.path("properties").isObject() || config.path("properties").isEmpty()) {
            return RuleNodeValidation.invalid("config 只允许非空 properties 对象");
        }
        return RuleNodeValidation.success();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public RuleNodeResult execute(RuleMessage message, JsonNode config, RuleExecutionContext context) {
        JsonNode payload = new ObjectMapper().createObjectNode()
                .put("deviceId", message.deviceId().toString())
                .set("properties", config.path("properties").deepCopy());
        return new RuleNodeResult(RuleRelation.SUCCESS, message,
                List.of(new RuleSideEffectIntent(INTENT_TYPE, payload)));
    }
}
