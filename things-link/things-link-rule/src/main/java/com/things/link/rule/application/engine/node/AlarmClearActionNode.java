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
 * S9-3 告警清除动作节点：只引用 S6 告警规则，并产出结束对应活动代的异步意图。
 *
 * <p>下游按告警规则、可信设备和规则冻结的告警类型定位活动实例；不存在时保持 no-op。节点不接受实例 ID、
 * version 或清除原因，避免绕过 S6 状态机与 CAS 边界。</p>
 */
@Component
public final class AlarmClearActionNode implements RuleNode {

    /** 不可变规则版本中保存的稳定节点类型。 */
    public static final String TYPE = "alarm-clear-action";

    /** Outbox 桥接层识别的稳定副作用类型。 */
    public static final String INTENT_TYPE = "alarm-clear";

    /** 配置只保存 S6 告警规则引用，所有实例身份由公开端口重新校验。 */
    private static final JsonNode SCHEMA = new ObjectMapper().readTree("""
            {"type":"object","additionalProperties":false,"required":["alarmRuleId"],
             "properties":{"alarmRuleId":{"type":"string","format":"uuid"}}}
            """);

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public String type() {
        return TYPE;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public JsonNode configSchema() {
        return SCHEMA.deepCopy();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public RuleNodeValidation validate(JsonNode config) {
        if (config == null || !config.isObject() || config.size() != 1
                || !config.path("alarmRuleId").isString() || !uuid(config.path("alarmRuleId").asText())) {
            return RuleNodeValidation.invalid("config 只允许合法 UUID alarmRuleId");
        }
        return RuleNodeValidation.success();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
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
