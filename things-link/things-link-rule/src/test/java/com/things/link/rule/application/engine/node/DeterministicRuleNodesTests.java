package com.things.link.rule.application.engine.node;

import com.things.link.rule.application.engine.RuleExecutionContext;
import com.things.link.rule.application.engine.RuleMessage;
import com.things.link.rule.application.engine.RuleNodeResult;
import com.things.link.rule.application.engine.RuleRelation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 首批三类确定性节点测试；验证封闭关系、纯计算和零副作用。 */
class DeterministicRuleNodesTests {

    /** 测试配置和 payload 映射器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 消息类型过滤只返回 TRUE/FALSE，且不改变原消息。 */
    @Test
    void filtersByFrozenMessageType() {
        MessageTypeFilterNode node = new MessageTypeFilterNode();
        JsonNode config = json("{\"messageTypes\":[\"PROPERTY_REPORT\",\"EVENT_REPORT\"]}");
        RuleMessage input = message(json("{\"temperature\":30}"), Map.of());

        RuleNodeResult matched = node.execute(input, config, context());
        RuleNodeResult missed = node.execute(message(json("{}"), Map.of(), "COMMAND_REPLY"), config, context());

        assertThat(node.validate(config).valid()).isTrue();
        assertThat(node.configSchema().get("additionalProperties").booleanValue()).isFalse();
        assertThat(matched.relation()).isEqualTo(RuleRelation.TRUE);
        assertThat(missed.relation()).isEqualTo(RuleRelation.FALSE);
        assertThat(matched.message()).isEqualTo(input);
        assertThat(matched.sideEffectIntents()).isEmpty();
    }

    /** 属性比较支持精确值、数字顺序和缺失路径；类型不匹配稳定返回 FALSE。 */
    @Test
    void comparesPayloadPropertyWithoutCoercion() {
        PayloadPropertyCompareNode node = new PayloadPropertyCompareNode();
        RuleMessage input = message(json("{\"temperature\":30,\"state\":\"ON\"}"), Map.of());

        assertThat(execute(node, input, "{\"pointer\":\"/temperature\",\"operator\":\"GTE\",\"value\":30}")
                .relation()).isEqualTo(RuleRelation.TRUE);
        assertThat(execute(node, input, "{\"pointer\":\"/state\",\"operator\":\"EQ\",\"value\":\"ON\"}")
                .relation()).isEqualTo(RuleRelation.TRUE);
        assertThat(execute(node, input, "{\"pointer\":\"/missing\",\"operator\":\"EXISTS\"}")
                .relation()).isEqualTo(RuleRelation.FALSE);
        assertThat(execute(node, input, "{\"pointer\":\"/state\",\"operator\":\"LT\",\"value\":5}")
                .relation()).isEqualTo(RuleRelation.FALSE);
    }

    /** 元数据默认保留既有键，可显式覆盖，但任何路径都不改变可信身份与 payload。 */
    @Test
    void enrichesMetadataWithExplicitOverwritePolicy() {
        MetadataEnrichmentNode node = new MetadataEnrichmentNode();
        RuleMessage input = message(json("{\"temperature\":30}"), Map.of("source", "mqtt"));

        RuleNodeResult preserved = node.execute(input,
                json("{\"values\":{\"source\":\"rule\",\"category\":\"hot\"}}"), context());
        RuleNodeResult overwritten = node.execute(input,
                json("{\"values\":{\"source\":\"rule\"},\"overwrite\":true}"), context());

        assertThat(preserved.relation()).isEqualTo(RuleRelation.SUCCESS);
        assertThat(preserved.message().metadata()).containsEntry("source", "mqtt").containsEntry("category", "hot");
        assertThat(overwritten.message().metadata()).containsEntry("source", "rule");
        assertThat(preserved.message().messageId()).isEqualTo(input.messageId());
        assertThat(preserved.message().payload()).isEqualTo(input.payload());
        assertThat(preserved.sideEffectIntents()).isEmpty();
    }

    /** 非法配置必须给出固定错误，并拒绝伪造 ADR 0017 的可信身份键。 */
    @Test
    void rejectsUnknownDuplicateAndReservedConfiguration() {
        MessageTypeFilterNode filter = new MessageTypeFilterNode();
        PayloadPropertyCompareNode compare = new PayloadPropertyCompareNode();
        MetadataEnrichmentNode enrichment = new MetadataEnrichmentNode();

        assertThat(filter.validate(json("{\"messageTypes\":[\"A\",\"A\"]}")).valid()).isFalse();
        assertThat(compare.validate(json("{\"pointer\":\"temperature\",\"operator\":\"EQ\",\"value\":1}")).valid())
                .isFalse();
        assertThat(compare.validate(json("{\"pointer\":\"/temperature\",\"operator\":\"GT\",\"value\":\"1\"}")).valid())
                .isFalse();
        assertThat(compare.validate(json("{\"pointer\":\"/temperature\",\"operator\":\"EXISTS\",\"unexpected\":true}"))
                .valid()).isFalse();
        assertThat(enrichment.validate(json("{\"values\":{\"traceId\":\"forged\"}}")).valid()).isFalse();
        assertThat(enrichment.validate(json("{\"values\":{\"category\":\"hot\"},\"unexpected\":true}"))
                .valid()).isFalse();
    }

    /** 通过真实 validate 门禁执行属性比较节点。 */
    private RuleNodeResult execute(PayloadPropertyCompareNode node, RuleMessage message, String configJson) {
        JsonNode config = json(configJson);
        assertThat(node.validate(config).valid()).isTrue();
        return node.execute(message, config, context());
    }

    /** 解析测试 JSON；字面量错误应直接让测试失败。 */
    private JsonNode json(String value) {
        return objectMapper.readTree(value);
    }

    /** 构造默认属性上报消息。 */
    private RuleMessage message(JsonNode payload, Map<String, String> metadata) {
        return message(payload, metadata, "PROPERTY_REPORT");
    }

    /** 构造指定类型、带完整可信身份的消息。 */
    private RuleMessage message(JsonNode payload, Map<String, String> metadata, String type) {
        return new RuleMessage(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "trace-test", Instant.parse("2026-08-13T00:00:00Z"), type, payload, metadata);
    }

    /** 构造纯数据执行上下文。 */
    private static RuleExecutionContext context() {
        return new RuleExecutionContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                Instant.parse("2026-08-13T00:00:01Z"));
    }
}
