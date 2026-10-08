package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.EventUplinkMessageNormalizer;
import com.things.link.ingestion.application.EventUplinkMessageReader;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.EventUplinkMessage;
import com.things.link.shared.message.RawUplinkMessage;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 实际Kafka编码器覆盖原始字节与独立事件两跳，以及受信类型边界。 */
class EventUplinkKafkaSerializationTests {
    private static final String TOPIC = RawUplinkKafkaConsumer.EVENT_NORMALIZED_TOPIC;
    private static final Map<String, Object> CONFIG = Map.of(JsonDeserializer.TRUSTED_PACKAGES,
            "com.things.link.shared.message,com.things.link.rule.application.queue");

    @Test
    void rawBytesThenEventParamsPreserveExactNumbersScaleAndTrustedContext() {
        var raw = raw();
        try (var serializer = new JsonSerializer<Object>(); var decoder = new UplinkPayloadPrecisionDeserializer()) {
            decoder.configure(CONFIG, false);
            var rawHeaders = new RecordHeaders();
            var restoredRaw = (RawUplinkMessage) decoder.deserialize(RawUplinkKafkaConsumer.RAW_UPLINK_TOPIC,
                    rawHeaders, serializer.serialize(RawUplinkKafkaConsumer.RAW_UPLINK_TOPIC, rawHeaders, raw));
            assertThat(restoredRaw.payload()).isEqualTo(raw.payload());
            var source = new EventUplinkMessageNormalizer(new EventUplinkMessageReader())
                    .tryNormalize(restoredRaw).orElseThrow();
            var eventHeaders = new RecordHeaders();
            var restored = (EventUplinkMessage) decoder.deserialize(TOPIC, eventHeaders,
                    serializer.serialize(TOPIC, eventHeaders, source));
            assertThat(restored).isEqualTo(source);
            assertThat(restored.params()).containsEntry("decimal", new BigDecimal("9007199254740993.123456789"))
                    .containsEntry("integer", new BigInteger("12345678901234567890123456789012345678"))
                    .containsEntry("scale", new BigDecimal("1.0"))
                    .containsEntry("max", new BigDecimal("1e308")).containsEntry("min", new BigDecimal("1e-308"));
            assertThat(((BigDecimal) restored.params().get("scale")).scale()).isEqualTo(1);
            assertThat(restored.rawBytes()).isEqualTo(raw.payload().length);
            assertThat(restored.receivedAt()).isEqualTo(raw.receivedAt());
        }
    }

    @Test
    void headerlessObjectIsNotGuessedAndExplicitEventConfigurationPreservesNumbers() {
        var source = new EventUplinkMessageNormalizer(new EventUplinkMessageReader()).tryNormalize(raw()).orElseThrow();
        try (var serializer = new JsonSerializer<Object>(); var missing = new UplinkPayloadPrecisionDeserializer();
                var configured = new UplinkPayloadPrecisionDeserializer()) {
            byte[] bytes = serializer.serialize(TOPIC, source);
            missing.configure(CONFIG, false);
            assertThat(missing.deserialize(TOPIC, bytes)).isInstanceOf(Map.class);
            var config = new HashMap<String, Object>(CONFIG);
            config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, EventUplinkMessage.class.getName());
            configured.configure(config, false);
            assertThat(configured.deserialize(TOPIC, bytes)).isEqualTo(source);
            var untrusted = new RecordHeaders().add("__TypeId__", "java.io.File".getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> missing.deserialize(TOPIC, untrusted, bytes))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("trusted");
        }
    }

    private static RawUplinkMessage raw() {
        byte[] bytes = ("{\"messageId\":\"" + Uuid7.generate() + "\",\"modelVersion\":\"1.0.0\","
                + "\"occurredAt\":\"2026-10-06T00:00:00Z\",\"params\":{\"decimal\":9007199254740993.123456789,"
                + "\"integer\":12345678901234567890123456789012345678,\"scale\":1.0,\"max\":1e308,\"min\":1e-308}}")
                .getBytes(StandardCharsets.UTF_8);
        return new RawUplinkMessage(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                "tc/v1/project/device/up/event/alarm", bytes, 1, false, "device-client",
                Instant.parse("2026-10-06T00:00:01Z"), "0123456789abcdef0123456789abcdef");
    }
}
