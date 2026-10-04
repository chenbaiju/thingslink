package com.things.link.rule.infrastructure.messaging;

import com.things.link.rule.application.engine.RuleMessage;
import com.things.link.rule.application.queue.RuleExecutionEnvelope;
import com.things.link.rule.application.queue.RuleExecutionKey;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 规则恢复路径的 Jackson 3 序列化器：payload 必须保留真实 JSON 而非 Jackson 2 自省标志。 */
class RuleExecutionEnvelopeJacksonSerializerTests {

    /** 测试映射器与生产均使用 Jackson 3；不允许用 Jackson 2 测试替身掩盖这次缺陷。 */
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    /** 序列化结果必须是真实载荷 JSON，绝不能退化成 Jackson 2 对 JsonNode 的自省布尔标志。 */
    @Test
    void shouldSerializePayloadAsActualJsonNotJacksonTwoIntrospection() {
        byte[] bytes = new RuleExecutionEnvelopeJacksonSerializer(objectMapper)
                .serialize(KafkaRuleRecoveryPublisher.RETRY_ONE_MINUTE_TOPIC, envelope());

        String json = new String(bytes, StandardCharsets.UTF_8);

        // 真实载荷必须原样保留；Jackson 2 会把 JsonNode 自省成 nodeType/object 等布尔标志并丢失 temperature。
        assertThat(json).contains("\"payload\":{\"temperature\":42}");
        assertThat(json).doesNotContain("nodeType");
    }

    /** 专用序列化器与专用反序列化器必须构成无损往返，恢复出身份、Instant 与真实 payload。 */
    @Test
    void shouldRoundTripEnvelopeThroughDedicatedPair() {
        RuleExecutionEnvelope expected = envelope();
        byte[] bytes = new RuleExecutionEnvelopeJacksonSerializer(objectMapper)
                .serialize(KafkaRuleRecoveryPublisher.RETRY_ONE_MINUTE_TOPIC, expected);
        RuleExecutionEnvelope actual = (RuleExecutionEnvelope) new RuleExecutionEnvelopeJacksonDeserializer(objectMapper)
                .deserialize(KafkaRuleRecoveryPublisher.RETRY_ONE_MINUTE_TOPIC, bytes);

        assertThat(actual).isEqualTo(expected);
        assertThat(actual.message().payload().path("temperature").asInt()).isEqualTo(42);
    }

    /** @return 带真实 Jackson 3 payload 与 Java 时间的完整重试信封 */
    private RuleExecutionEnvelope envelope() {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        RuleMessage message = new RuleMessage(messageId, tenantId, projectId, UUID.randomUUID(),
                "trace-f33", Instant.parse("2026-08-30T12:00:00Z"), "PROPERTY_REPORT",
                objectMapper.readTree("{\"temperature\":42}"), Map.of("source", "retry-test"));
        return new RuleExecutionEnvelope(
                new RuleExecutionKey(projectId, messageId, ruleId, versionId), tenantId, message, 2,
                Instant.parse("2026-08-30T12:01:00Z"));
    }
}
