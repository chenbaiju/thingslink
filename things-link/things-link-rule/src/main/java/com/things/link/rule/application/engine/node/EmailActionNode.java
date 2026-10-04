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

/** S9-3 邮件动作：校验静态收件地址并把可渲染正文转成通知意图，节点本身不接触 SMTP。 */
@Component
public final class EmailActionNode implements RuleNode {
    /** 规则版本动作清单中的稳定节点类型。 */
    public static final String TYPE = "email-action";
    /** 邮件与 Webhook 共用 S9-3 通知投递状态机，渠道由载荷封闭。 */
    public static final String INTENT_TYPE = "notification";
    /** 配置字段必须封闭，避免租户输入覆盖可信投递身份。 */
    private static final Set<String> FIELDS = Set.of("recipient", "subject", "body");
    /** 首期只接受常见单地址形式；通讯录、抄送和批量投递不属于 S9-3。 */
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,64}@[^\\s@.]{1,128}\\.[^\\s@]{2,32}$");
    /** 控制面展示和写入前校验共用的封闭 Schema。 */
    private static final JsonNode SCHEMA = new ObjectMapper().readTree("""
            {"type":"object","additionalProperties":false,"required":["recipient","subject","body"],
             "properties":{"recipient":{"type":"string","maxLength":256},
             "subject":{"type":"string","maxLength":256},"body":{"type":"string","maxLength":4096}}}
            """);

    /** {@inheritDoc} */
    @Override public String type() { return TYPE; }
    /** {@inheritDoc} */
    @Override public JsonNode configSchema() { return SCHEMA.deepCopy(); }

    /** {@inheritDoc} */
    @Override public RuleNodeValidation validate(JsonNode config) {
        if (config == null || !config.isObject() || config.size() != FIELDS.size()
                || config.properties().stream().anyMatch(entry -> !FIELDS.contains(entry.getKey()))
                || !config.path("recipient").isString()
                || !EMAIL.matcher(config.path("recipient").asText()).matches()
                || !boundedString(config.path("subject"), 256)
                || !boundedString(config.path("body"), 4096)) {
            return RuleNodeValidation.invalid("config 只允许合法 recipient、subject 与 body");
        }
        return RuleNodeValidation.success();
    }

    /** {@inheritDoc} */
    @Override public RuleNodeResult execute(RuleMessage message, JsonNode config, RuleExecutionContext context) {
        JsonNode payload = new ObjectMapper().createObjectNode()
                .put("channel", "email")
                .put("recipient", config.path("recipient").asText())
                .put("subject", RuleActionTemplateRenderer.render(config.path("subject").asText(), message))
                .put("body", RuleActionTemplateRenderer.render(config.path("body").asText(), message))
                .put("deviceId", message.deviceId().toString())
                .put("occurredAt", message.occurredAt().toString());
        return new RuleNodeResult(RuleRelation.SUCCESS, message,
                List.of(new RuleSideEffectIntent(INTENT_TYPE, payload)));
    }

    /** 字符串长度在控制面和 Worker 再校验一次，防止旧版本绕过新约束。 */
    private static boolean boundedString(JsonNode value, int maximum) {
        return value.isString() && !value.asText().isBlank() && value.asText().length() <= maximum;
    }
}
