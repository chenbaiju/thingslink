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
import java.util.UUID;

/**
 * S9-3 告警创建动作节点：只引用 S6 告警规则，并产出直接激活告警的异步意图。
 *
 * <p>告警类型、严重程度和目标设备必须由下游按 {@code alarmRuleId} 读取权威规则，不能冻结在动作配置中形成
 * 第二份可漂移事实。消息身份与双时间来自已经确权的 {@link RuleMessage}，租户配置不能覆盖。</p>
 */
@Component
public final class AlarmCreateActionNode implements RuleNode {

    /** 不可变规则版本中保存的稳定节点类型。 */
    public static final String TYPE = "alarm-create-action";

    /** Outbox 桥接层识别的稳定副作用类型。 */
    public static final String INTENT_TYPE = "alarm-create";

    /** 配置只保存 S6 告警规则引用，禁止复制告警属性。 */
    private static final JsonNode SCHEMA = new ObjectMapper().readTree("""
            {"type":"object","additionalProperties":false,"required":["alarmRuleId"],
             "properties":{"alarmRuleId":{"type":"string","format":"uuid"}}}
            """);

    /** {@inheritDoc} */
    @Override
    public String type() {
        return TYPE;
    }

    /** {@inheritDoc} */
    @Override
    public JsonNode configSchema() {
        return SCHEMA.deepCopy();
    }

    /** {@inheritDoc} */
    @Override
    public RuleNodeValidation validate(JsonNode config) {
        if (config == null || !config.isObject() || config.size() != 1
                || !config.path("alarmRuleId").isString() || !uuid(config.path("alarmRuleId").asText())) {
            return RuleNodeValidation.invalid("config 只允许合法 UUID alarmRuleId");
        }
        return RuleNodeValidation.success();
    }

    /** {@inheritDoc} */
    @Override
    public RuleNodeResult execute(RuleMessage message, JsonNode config, RuleExecutionContext context) {
        JsonNode payload = new ObjectMapper().createObjectNode()
                .put("alarmRuleId", config.path("alarmRuleId").asText())
                .put("deviceId", message.deviceId().toString())
                .put("messageId", message.messageId().toString())
                .put("occurredAt", message.occurredAt().toString())
                .put("receivedAt", context.startedAt().toString())
                .put("traceId", message.traceId());
        return new RuleNodeResult(RuleRelation.SUCCESS, message,
                List.of(new RuleSideEffectIntent(INTENT_TYPE, payload)));
    }

    /**
     * UUID 解析只用于配置校验，不把解析异常泄漏成控制面 500。
     *
     * @param value 候选规则 ID
     * @return 是否为 UUID 文本
     */
    private static boolean uuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
