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

import java.net.URI;
import java.util.List;
import java.util.Set;

/** S9-3 Webhook 动作：只冻结 HTTPS 目标和渲染正文，签名与网络调用由 S6 渠道适配器完成。 */
@Component
public final class WebhookActionNode implements RuleNode {
    /** 规则版本动作清单中的稳定节点类型。 */
    public static final String TYPE = "webhook-action";
    /** Webhook 复用通知投递状态机，避免再造第二套重试与死信事实。 */
    public static final String INTENT_TYPE = "notification";
    /** 目标 URL 必须是静态配置，禁止 payload 模板改变主机形成 SSRF 绕过。 */
    private static final Set<String> FIELDS = Set.of("url", "body");
    /** 控制面展示和写入前校验共用的封闭 Schema。 */
    private static final JsonNode SCHEMA = new ObjectMapper().readTree("""
            {"type":"object","additionalProperties":false,"required":["url","body"],
             "properties":{"url":{"type":"string","format":"uri","maxLength":2048},
             "body":{"type":"string","maxLength":16384}}}
            """);

    /** {@inheritDoc} */
    @Override public String type() { return TYPE; }
    /** {@inheritDoc} */
    @Override public JsonNode configSchema() { return SCHEMA.deepCopy(); }

    /** {@inheritDoc} */
    @Override public RuleNodeValidation validate(JsonNode config) {
        if (config == null || !config.isObject() || config.size() != FIELDS.size()
                || config.properties().stream().anyMatch(entry -> !FIELDS.contains(entry.getKey()))
                || !config.path("url").isString() || !secureUrl(config.path("url").asText())
                || !config.path("body").isString() || config.path("body").asText().isBlank()
                || config.path("body").asText().length() > 16384) {
            return RuleNodeValidation.invalid("config 只允许 HTTPS url 与长度受限的 body");
        }
        return RuleNodeValidation.success();
    }

    /** {@inheritDoc} */
    @Override public RuleNodeResult execute(RuleMessage message, JsonNode config, RuleExecutionContext context) {
        JsonNode payload = new ObjectMapper().createObjectNode()
                .put("channel", "webhook")
                .put("recipient", config.path("url").asText())
                .put("subject", "")
                .put("body", RuleActionTemplateRenderer.render(config.path("body").asText(), message))
                .put("deviceId", message.deviceId().toString())
                .put("occurredAt", message.occurredAt().toString());
        return new RuleNodeResult(RuleRelation.SUCCESS, message,
                List.of(new RuleSideEffectIntent(INTENT_TYPE, payload)));
    }

    /** HTTPS、显式主机且无 user-info 是 S6 Webhook 安全边界；最终发送前适配器仍会再次校验。 */
    private static boolean secureUrl(String value) {
        if (value.length() > 2048) return false;
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && !uri.getHost().isBlank() && uri.getUserInfo() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
