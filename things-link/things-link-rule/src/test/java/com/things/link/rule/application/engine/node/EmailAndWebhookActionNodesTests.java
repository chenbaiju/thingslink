package com.things.link.rule.application.engine.node;

import com.things.link.rule.application.engine.RuleExecutionContext;
import com.things.link.rule.application.engine.RuleMessage;
import com.things.link.rule.application.engine.RuleRelation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** S9-3 邮件/Webhook 节点测试：封闭配置、安全目标、模板渲染与纯意图边界。 */
class EmailAndWebhookActionNodesTests {
    /** 测试 JSON 映射器。 */ private final ObjectMapper mapper = new ObjectMapper();
    /** 被测邮件节点。 */ private final EmailActionNode email = new EmailActionNode();
    /** 被测 Webhook 节点。 */ private final WebhookActionNode webhook = new WebhookActionNode();

    /** 邮件只接受单个合法地址和长度受限正文，未知字段 fail-closed。 */
    @Test void validatesEmailConfiguration() {
        assertThat(email.validate(json("{\"recipient\":\"ops@example.com\",\"subject\":\"告警\",\"body\":\"正文\"}")).valid()).isTrue();
        assertThat(email.validate(json("{\"recipient\":\"bad\",\"subject\":\"告警\",\"body\":\"正文\"}")).valid()).isFalse();
        assertThat(email.validate(json("{\"recipient\":\"ops@example.com\",\"subject\":\"告警\",\"body\":\"正文\",\"cc\":\"x@y.cn\"}")).valid()).isFalse();
        assertThat(email.configSchema().path("additionalProperties").booleanValue()).isFalse();
    }

    /** Webhook 只允许静态 HTTPS 主机，拒绝 HTTP、user-info 与未知字段。 */
    @Test void validatesWebhookConfiguration() {
        assertThat(webhook.validate(json("{\"url\":\"https://hooks.example.com/rule\",\"body\":\"{}\"}")).valid()).isTrue();
        assertThat(webhook.validate(json("{\"url\":\"http://hooks.example.com/rule\",\"body\":\"{}\"}")).valid()).isFalse();
        assertThat(webhook.validate(json("{\"url\":\"https://user@hooks.example.com/rule\",\"body\":\"{}\"}")).valid()).isFalse();
        assertThat(webhook.configSchema().path("additionalProperties").booleanValue()).isFalse();
    }

    /** 两节点均只产出 notification 意图并保持原消息不变。 */
    @Test void rendersStableNotificationIntents() {
        RuleMessage message = message();
        var mail = email.execute(message, json("{\"recipient\":\"ops@example.com\",\"subject\":\"温度 ${payload.temperature}\",\"body\":\"${deviceId}\"}"), context());
        var hook = webhook.execute(message, json("{\"url\":\"https://hooks.example.com/rule\",\"body\":\"{\\\"value\\\":\\\"${payload.temperature}\\\"}\"}"), context());

        assertThat(mail.relation()).isEqualTo(RuleRelation.SUCCESS);
        assertThat(mail.message()).isEqualTo(message);
        assertThat(mail.sideEffectIntents().getFirst().type()).isEqualTo("notification");
        assertThat(mail.sideEffectIntents().getFirst().payload().path("channel").asText()).isEqualTo("email");
        assertThat(mail.sideEffectIntents().getFirst().payload().path("subject").asText()).isEqualTo("温度 42");
        assertThat(hook.sideEffectIntents().getFirst().payload().path("channel").asText()).isEqualTo("webhook");
        assertThat(hook.sideEffectIntents().getFirst().payload().path("body").asText()).isEqualTo("{\"value\":\"42\"}");
    }

    /** 测试 JSON 字面量解析失败应直接终止测试。 */ private JsonNode json(String value) { return mapper.readTree(value); }
    /** 构造完整可信消息。 */
    private RuleMessage message() {
        return new RuleMessage(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "trace-s9-3", Instant.parse("2026-08-14T00:00:00Z"), "PROPERTY_REPORT",
                json("{\"temperature\":42}"), Map.of());
    }
    /** 构造纯数据上下文。 */
    private static RuleExecutionContext context() {
        return new RuleExecutionContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                Instant.parse("2026-08-14T00:00:01Z"));
    }
}
