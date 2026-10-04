package com.things.link.rule.application.engine.node;

import com.things.link.rule.application.engine.RuleExecutionContext;
import com.things.link.rule.application.engine.RuleMessage;
import com.things.link.rule.application.engine.RuleNodeResult;
import com.things.link.rule.application.engine.RuleRelation;
import com.things.link.rule.application.engine.RuleSideEffectIntent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** S9-1 首个动作节点：固定配置门禁、纯字符串渲染、产出通知意图且不改变消息。 */
class NotificationActionNodeTests {

    /** 测试配置和 payload 映射器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 被测通知动作节点。 */
    private final NotificationActionNode node = new NotificationActionNode();

    /** 配置 schema 必须封闭，只接受 channel/recipient 必填与 subject/body 可选字符串。 */
    @Test
    void exposesClosedConfigSchema() {
        JsonNode schema = node.configSchema();

        assertThat(node.type()).isEqualTo("notification-action");
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("additionalProperties").booleanValue()).isFalse();
        assertThat(schema.get("required").get(0).asText()).isEqualTo("channel");
        assertThat(schema.get("required").get(1).asText()).isEqualTo("recipient");
        assertThat(schema.get("properties").has("subject")).isTrue();
        assertThat(schema.get("properties").has("body")).isTrue();
    }

    /** 合法配置通过；缺失必填、未知键或越界字符串必须 fail-closed。 */
    @Test
    void validatesOnlyFixedConfigPaths() {
        assertThat(node.validate(json("{\"channel\":\"email\",\"recipient\":\"ops@example.com\"}")).valid())
                .isTrue();
        assertThat(node.validate(json("{\"channel\":\"email\",\"recipient\":\"ops@example.com\","
                + "\"subject\":\"s\",\"body\":\"b\"}")).valid()).isTrue();

        assertThat(node.validate(json("{}")).valid()).isFalse();
        assertThat(node.validate(json("{\"channel\":\"email\"}")).valid()).isFalse();
        assertThat(node.validate(json("{\"recipient\":\"ops@example.com\"}")).valid()).isFalse();
        assertThat(node.validate(json("{\"channel\":\"email\",\"recipient\":\"ops@example.com\","
                + "\"unexpected\":true}")).valid()).isFalse();
        assertThat(node.validate(json("{\"channel\":\"email\",\"recipient\":\"ops@example.com\","
                + "\"subject\":42}")).valid()).isFalse();
        assertThat(node.validate(json("{\"channel\":\"email\",\"recipient\":\"\"}")).valid()).isFalse();
    }

    /** execute 渲染可信身份与 payload 路径为通知意图，且不改变原消息 payload。 */
    @Test
    void rendersNotificationIntentWithoutMutatingMessage() {
        RuleMessage input = message(json("{\"temperature\":42,\"location\":{\"floor\":3}}"), Map.of());
        JsonNode config = json("{\"channel\":\"email\",\"recipient\":\"ops@example.com\","
                + "\"subject\":\"温度告警 ${payload.temperature}\","
                + "\"body\":\"设备 ${deviceId} 在 ${payload.location.floor} 楼告警，缺失 ${payload.none}\"}");

        RuleNodeResult result = node.execute(input, config, context());

        assertThat(result.relation()).isEqualTo(RuleRelation.SUCCESS);
        assertThat(result.message()).isEqualTo(input);
        assertThat(result.message().payload()).isEqualTo(input.payload());
        assertThat(result.sideEffectIntents()).hasSize(1);

        RuleSideEffectIntent intent = result.sideEffectIntents().getFirst();
        assertThat(intent.type()).isEqualTo("notification");
        assertThat(intent.payload().get("channel").asText()).isEqualTo("email");
        assertThat(intent.payload().get("recipient").asText()).isEqualTo("ops@example.com");
        assertThat(intent.payload().get("subject").asText()).isEqualTo("温度告警 42");
        assertThat(intent.payload().get("body").asText())
                .isEqualTo("设备 " + input.deviceId() + " 在 3 楼告警，缺失 ");
        assertThat(intent.payload().get("deviceId").asText()).isEqualTo(input.deviceId().toString());
        assertThat(intent.payload().get("occurredAt").asText()).isEqualTo(input.occurredAt().toString());
    }

    /** 未配置 subject/body 时渲染为空串，且意图 payload 每次读取返回副本。 */
    @Test
    void rendersAbsentSubjectAndBodyAsEmpty() {
        RuleNodeResult result = node.execute(message(json("{}"), Map.of()),
                json("{\"channel\":\"email\",\"recipient\":\"ops@example.com\"}"), context());

        RuleSideEffectIntent intent = result.sideEffectIntents().getFirst();
        assertThat(intent.payload().get("subject").asText()).isEmpty();
        assertThat(intent.payload().get("body").asText()).isEmpty();
        assertThat(intent.payload()).isNotSameAs(intent.payload());
    }

    /** 解析测试 JSON；字面量错误应直接让测试失败。 */
    private JsonNode json(String value) {
        return objectMapper.readTree(value);
    }

    /** 构造带完整可信身份的消息。 */
    private RuleMessage message(JsonNode payload, Map<String, String> metadata) {
        return new RuleMessage(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "trace-test", Instant.parse("2026-08-13T00:00:00Z"), "PROPERTY_REPORT", payload, metadata);
    }

    /** 构造纯数据执行上下文。 */
    private static RuleExecutionContext context() {
        return new RuleExecutionContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                Instant.parse("2026-08-13T00:00:01Z"));
    }
}
