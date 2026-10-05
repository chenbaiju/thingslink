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
import java.util.regex.Pattern;

/**
 * 通用通知动作节点：把租户配置的通知参数渲染为一条「通知副作用意图」，不发送、不读库、不碰网络。
 *
 * <p>本节点仅产出 {@code RuleSideEffectIntent("notification", ...)}；S9-3 的投递状态机通过 Outbox/Kafka
 * 调用共用 sender，保持外部 I/O 与规则执行事务隔离。</p>
 */
@Component
public final class NotificationActionNode implements RuleNode {

    /** 节点注册表中的稳定类型。 */
    public static final String TYPE = "notification-action";
    /** 意图类型与 S9-2/3 桥接白名单对齐，本节点只允许这一种副作用。 */
    public static final String INTENT_TYPE = "notification";
    /** 配置仅包含固定的 channel/recipient 必填字符串与可选 subject/body 字符串。 */
    private static final JsonNode CONFIG_SCHEMA = new ObjectMapper().readTree("""
            {"type":"object","additionalProperties":false,"required":["channel","recipient"],
             "properties":{"channel":{"type":"string","minLength":1,"maxLength":32},
             "recipient":{"type":"string","minLength":1,"maxLength":256},
             "subject":{"type":"string","maxLength":256},
             "body":{"type":"string","maxLength":4096}}}
            """);
    /** 允许在 subject/body 中引用的固定可信身份占位符。 */
    private static final Set<String> ALLOWED_KEYS = Set.of("channel", "recipient", "subject", "body");
    /** 匹配 {@code ${payload.a.b}} 形式的载荷路径模板，路径只允许字母、数字、点与下划线。 */
    private static final Pattern PAYLOAD_PLACEHOLDER =
            Pattern.compile("\\$\\{payload\\.([A-Za-z0-9_.]+)}");

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
        if (config == null || !config.isObject() || config.isEmpty() || config.size() > ALLOWED_KEYS.size()
                || !config.has("channel") || !config.get("channel").isString()
                || config.get("channel").stringValue().isBlank()
                || config.get("channel").stringValue().length() > 32
                || !config.has("recipient") || !config.get("recipient").isString()
                || config.get("recipient").stringValue().isBlank()
                || config.get("recipient").stringValue().length() > 256
                || config.properties().stream().anyMatch(entry -> !ALLOWED_KEYS.contains(entry.getKey()))) {
            return RuleNodeValidation.invalid("config 只允许 channel、recipient 与可选 subject、body");
        }
        if ((config.has("subject") && (!config.get("subject").isString()
                || config.get("subject").stringValue().length() > 256))
                || (config.has("body") && (!config.get("body").isString()
                || config.get("body").stringValue().length() > 4096))) {
            return RuleNodeValidation.invalid("subject/body 必须是长度受限的字符串");
        }
        return RuleNodeValidation.success();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public RuleNodeResult execute(RuleMessage message, JsonNode config, RuleExecutionContext context) {
        JsonNode payload = new ObjectMapper().createObjectNode()
                .put("channel", config.get("channel").stringValue())
                .put("recipient", render(config.get("recipient").stringValue(), message))
                .put("subject", render(config.path("subject").asText(""), message))
                .put("body", render(config.path("body").asText(""), message))
                .put("deviceId", message.deviceId().toString())
                .put("occurredAt", message.occurredAt().toString());
        RuleSideEffectIntent intent = new RuleSideEffectIntent(INTENT_TYPE, payload);
        return new RuleNodeResult(RuleRelation.SUCCESS, message, List.of(intent));
    }

    /** 只替换固定可信身份与 {@code ${payload.*}} 载荷路径；无法解析的路径渲染为空串而不抛错。 */
    private static String render(String template, RuleMessage message) {
        String rendered = template
                .replace("${deviceId}", message.deviceId().toString())
                .replace("${messageId}", message.messageId().toString())
                .replace("${traceId}", message.traceId())
                .replace("${occurredAt}", message.occurredAt().toString());
        var matcher = PAYLOAD_PLACEHOLDER.matcher(rendered);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(
                    resolvePath(message.payload(), matcher.group(1))));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /** 沿点分路径读取标量；缺失或非标量返回空串。 */
    private static String resolvePath(JsonNode root, String path) {
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            if (current == null || !current.isObject() || !current.has(segment)) {
                return "";
            }
            current = current.get(segment);
        }
        return current == null || !current.isValueNode() ? "" : current.asText();
    }
}
