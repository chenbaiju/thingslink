package com.things.link.rule.infrastructure.messaging;

import com.things.link.rule.application.engine.RuleMessage;
import com.things.link.rule.application.queue.RuleExecutionEnvelope;
import com.things.link.rule.application.queue.RuleExecutionKey;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.SerializationUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 规则 retry Topic 的 Jackson 3 信封往返与畸形记录闭包。 */
class RuleExecutionEnvelopeJacksonDeserializerTests {

    /** 测试映射器与生产均使用 Jackson 3；不允许用 Jackson 2 测试替身掩盖这次缺陷。 */
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    /** 合法信封必须完整恢复 JsonNode、Instant 与不可变幂等身份。 */
    @Test
    void shouldDeserializeEnvelopeWithJacksonThreePayload() {
        RuleExecutionEnvelope expected = envelope();
        RuleExecutionEnvelope actual = (RuleExecutionEnvelope) new RuleExecutionEnvelopeJacksonDeserializer(objectMapper)
                .deserialize(KafkaRuleRecoveryPublisher.RETRY_ONE_MINUTE_TOPIC,
                        objectMapper.writeValueAsBytes(expected));

        assertThat(actual).isEqualTo(expected);
        assertThat(actual.message().payload().path("temperature").asInt()).isEqualTo(42);
    }

    /** 重试持久字节不得把原本未修改的嵌套十进制载荷降成DoubleNode。 */
    @Test
    void preservesNestedDecimalAcrossRetryWire() {
        String wire = objectMapper.writeValueAsString(envelope()).replace(
                "\"temperature\":42", "\"payload\":{\"decimal\":9007199254740993.123456789}");
        RuleExecutionEnvelope actual = (RuleExecutionEnvelope) new RuleExecutionEnvelopeJacksonDeserializer(objectMapper)
                .deserialize(KafkaRuleRecoveryPublisher.RETRY_ONE_MINUTE_TOPIC,
                        wire.getBytes(StandardCharsets.UTF_8));
        assertThat(actual.message().payload().path("payload").path("decimal").decimalValue())
                .isEqualByComparingTo("9007199254740993.123456789");
    }

    /** 直接解码失败必须成为 Kafka SerializationException，并保留 Jackson 3 原始 cause。 */
    @Test
    void shouldRejectMalformedEnvelopeAsSerializationFailure() {
        RuleExecutionEnvelopeJacksonDeserializer deserializer =
                new RuleExecutionEnvelopeJacksonDeserializer(objectMapper);

        assertThatThrownBy(() -> deserializer.deserialize(
                KafkaRuleRecoveryPublisher.RETRY_ONE_MINUTE_TOPIC,
                "{bad-json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("Jackson 3");
    }

    /** ErrorHandlingDeserializer 必须把畸形记录变为可归因 header，禁止同一 offset 原生异常热循环。 */
    @Test
    void shouldExposeMalformedEnvelopeToContainerErrorHandling() {
        ErrorHandlingDeserializer<Object> deserializer = new ErrorHandlingDeserializer<>(
                new RuleExecutionEnvelopeJacksonDeserializer(objectMapper));
        RecordHeaders headers = new RecordHeaders();

        Object value = deserializer.deserialize(KafkaRuleRecoveryPublisher.RETRY_ONE_MINUTE_TOPIC,
                headers, "{bad-json".getBytes(StandardCharsets.UTF_8));

        assertThat(value).isNull();
        assertThat(headers.lastHeader(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER)).isNotNull();
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
