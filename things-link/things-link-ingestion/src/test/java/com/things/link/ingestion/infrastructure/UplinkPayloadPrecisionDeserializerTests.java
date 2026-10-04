package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.DevicePropertyReportReader;
import com.things.link.ingestion.application.RawUplinkMessageNormalizer;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.RawUplinkMessage;
import com.things.link.shared.message.GatewayBatchMessage;
import com.things.link.shared.message.SubDeviceReport;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.message.StandardUplinkMessage;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 直连与网关原始载荷跨Kafka编码、sealed条目及不改payload的规则映射精度。 */
class UplinkPayloadPrecisionDeserializerTests {
    /** 超出Double有效精度的原始小数。 */
    private static final String DECIMAL = "9007199254740993.123456789";
    /** 超出Long范围的合法JSON整数。 */
    private static final String INTEGER = "9007199254740993123456789";
    /** 与生产一致的受信消息包，禁止通配符。 */
    private static final Map<String, Object> CONFIG = Map.of(JsonDeserializer.TRUSTED_PACKAGES,
            "com.things.link.shared.message,com.things.link.rule.application.queue");

    /** 原缺陷仍以默认解码器独立复现，不以改写后的预期替代失败证据。 */
    @Test
    void defaultReadersLoseDecimalsButScopedReadersPreserveBothKafkaHops() {
        var mapper = new ObjectMapper();
        var raw = raw();
        var old = mapper.readValue(raw.payload(), com.things.link.shared.message.DevicePropertyReport.class);
        Object oldDecimal = ((Map<?, ?>) old.payload().get("payload")).get("decimal");
        assertThat(oldDecimal).isInstanceOf(Double.class);
        assertThat(new BigDecimal(oldDecimal.toString())).isNotEqualByComparingTo(DECIMAL);
        var source = new RawUplinkMessageNormalizer(new DevicePropertyReportReader(mapper)).normalize(raw);
        assertExact(source);
        try (var serializer = new JsonSerializer<Object>(); var decoder = new JsonDeserializer<Object>()) {
            decoder.configure(CONFIG, false);
            var headers = new RecordHeaders();
            var decoded = (StandardUplinkMessage) decoder.deserialize("normalized", headers,
                    serializer.serialize("normalized", headers, source));
            Object kafkaDecimal = ((Map<?, ?>) decoded.payload().get("payload")).get("decimal");
            assertThat(kafkaDecimal).isInstanceOf(Double.class);
            assertThat(new BigDecimal(kafkaDecimal.toString())).isNotEqualByComparingTo(DECIMAL);
        }
        var normalized = roundTrip(RawUplinkKafkaConsumer.NORMALIZED_UPLINK_TOPIC, source);
        var ruleMapper = new RuleUplinkMessageMapper(mapper);
        var rule = ruleMapper.toRuleMessage(normalized);
        var unchanged = ruleMapper.toStandardMessage(rule, rule);
        assertExact(unchanged);
        assertExact(roundTrip(ProcessedUplinkKafkaConsumer.PROCESSED_UPLINK_TOPIC, unchanged));
    }

    /** batch是独立Kafka边界；混合sealed条目必须在Jackson2类型检查和Jackson3精确重读后保真。 */
    @Test
    void preservesMixedBatchEntriesAndNestedPrecisionAcrossKafkaBoundary() {
        var payload = Map.<String, Object>of("object", Map.of("decimal", new BigDecimal(DECIMAL),
                "integer", new BigInteger(INTEGER), "list", List.of(new BigDecimal(DECIMAL), new BigInteger(INTEGER))));
        var valid = new GatewayBatchMessage.Valid(new SubDeviceReport(Uuid7.generate(), "sub_01",
                Instant.parse("2026-09-08T00:00:00Z"), "1.0.0", payload), 129);
        var rejected = new GatewayBatchMessage.Rejected(GatewayBatchMessage.RejectReason.INVALID_MESSAGE_ID, 128);
        var source = new GatewayBatchMessage(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), 257,
                Instant.parse("2026-09-08T00:00:01Z"), "0123456789abcdef0123456789abcdef", List.of(valid, rejected));
        try (var serializer = new JsonSerializer<Object>(); var decoder = new UplinkPayloadPrecisionDeserializer();
                var oldDecoder = new JsonDeserializer<Object>()) {
            decoder.configure(CONFIG, false);
            oldDecoder.configure(CONFIG, false);
            var headers = new RecordHeaders();
            byte[] bytes = serializer.serialize(DeviceBatchKafkaConsumer.BATCH_TOPIC, headers, source);
            var old = (GatewayBatchMessage) oldDecoder.deserialize(DeviceBatchKafkaConsumer.BATCH_TOPIC,
                    new RecordHeaders(headers), bytes);
            Object rounded = ((Map<?, ?>) ((GatewayBatchMessage.Valid) old.entries().getFirst())
                    .report().payload().get("object")).get("decimal");
            assertThat(rounded).isInstanceOf(Double.class);
            assertThat(new BigDecimal(rounded.toString())).isNotEqualByComparingTo(DECIMAL);
            var restored = (GatewayBatchMessage) decoder.deserialize(DeviceBatchKafkaConsumer.BATCH_TOPIC,
                    new RecordHeaders(headers), bytes);
            assertThat(restored).isEqualTo(source);
            assertThat(restored.entries().getFirst()).isInstanceOf(GatewayBatchMessage.Valid.class);
            assertThat(restored.entries().getLast()).isEqualTo(rejected);
            var report = ((GatewayBatchMessage.Valid) restored.entries().getFirst()).report();
            // device复核仅传递不可变Map；拆分后仍需穿过normalized和processed两次序列化。
            var standard = new StandardUplinkMessage(report.messageId(), restored.tenantId(), restored.projectId(),
                    Uuid7.generate(), restored.gatewayId(), TransportProtocol.MQTT, StandardUplinkMessage.Direction.UP,
                    StandardUplinkMessage.Type.PROPERTY_REPORT, report.modelVersion(), report.occurredAt(),
                    restored.receivedAt(), restored.traceId(), valid.rawBytes(), Map.of("payload", report.payload().get("object")));
            assertExact(roundTrip(ProcessedUplinkKafkaConsumer.PROCESSED_UPLINK_TOPIC,
                    roundTrip(RawUplinkKafkaConsumer.NORMALIZED_UPLINK_TOPIC, standard)));
        }
    }

    /** 无头默认Object不推断网关身份；只有显式默认网关类型才进入精确恢复。 */
    @Test
    void headerlessObjectRemainsUntypedAndConfiguredBatchPreservesOutOfDoubleRangeDecimals() {
        var source = new GatewayBatchMessage(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), 128,
                Instant.parse("2026-09-08T00:00:01Z"), "0123456789abcdef0123456789abcdef",
                List.of(new GatewayBatchMessage.Valid(new SubDeviceReport(Uuid7.generate(), "sub_01",
                        Instant.parse("2026-09-08T00:00:00Z"), "1.0.0",
                        Map.of("object", Map.of("large", new BigDecimal("1e400"), "small", new BigDecimal("1e-400")))), 128)));
        try (var serializer = new JsonSerializer<Object>(); var missing = new UplinkPayloadPrecisionDeserializer();
                var configured = new UplinkPayloadPrecisionDeserializer()) {
            byte[] bytes = serializer.serialize(DeviceBatchKafkaConsumer.BATCH_TOPIC, source);
            missing.configure(CONFIG, false);
            assertThat(missing.deserialize(DeviceBatchKafkaConsumer.BATCH_TOPIC, bytes)).isInstanceOf(Map.class);
            var config = new java.util.HashMap<String, Object>(CONFIG);
            config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, GatewayBatchMessage.class.getName());
            configured.configure(config, false);
            assertThat(configured.deserialize(DeviceBatchKafkaConsumer.BATCH_TOPIC, bytes)).isEqualTo(source);
        }
    }

    /** 默认中间Double溢出不允许改变原始十进制；有限值资格仍由设备域裁决。 */
    @Test
    void preliminaryTypeCheckDoesNotRejectDecimalOutsideDoubleRange() {
        var base = raw();
        byte[] wire = new String(base.payload(), StandardCharsets.UTF_8).replace(DECIMAL, "1e400")
                .getBytes(StandardCharsets.UTF_8);
        var source = new RawUplinkMessageNormalizer(new DevicePropertyReportReader(new ObjectMapper()))
                .normalize(new RawUplinkMessage(
                base.tenantId(), base.projectId(), base.deviceId(), base.topic(), wire, base.qos(),
                base.retained(), base.clientId(), base.receivedAt(), base.traceId()));
        try (var serializer = new JsonSerializer<Object>(); var decoder = new UplinkPayloadPrecisionDeserializer()) {
            decoder.configure(CONFIG, false);
            var headers = new RecordHeaders();
            var decoded = (StandardUplinkMessage) decoder.deserialize("normalized", headers,
                    serializer.serialize("normalized", headers, source));
            assertThat(((Map<?, ?>) decoded.payload().get("payload")).get("decimal"))
                    .isEqualTo(new BigDecimal("1e400"));
        }
    }

    /** 原始字节和其他类仍由默认解码器处理，类型头不能绕开受信包。 */
    @Test
    void rawNullAndUntrustedTypesRetainOriginalSemantics() {
        try (var serializer = new JsonSerializer<Object>(); var decoder = new UplinkPayloadPrecisionDeserializer()) {
            decoder.configure(CONFIG, false);
            var headers = new RecordHeaders();
            var source = raw();
            var decoded = (RawUplinkMessage) decoder.deserialize("raw", headers,
                    serializer.serialize("raw", headers, source));
            assertThat(decoded.payload()).isEqualTo(source.payload());
            assertThat(decoder.deserialize("raw", new RecordHeaders(), (byte[]) null)).isNull();
            var untrusted = new RecordHeaders().add("__TypeId__", "java.io.File".getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> decoder.deserialize("normalized", untrusted, "{}".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("trusted");
        }
    }

    /** 使用生产配置的实际Kafka serializer与窄解码器完成一次wire往返。 */
    private StandardUplinkMessage roundTrip(String topic, StandardUplinkMessage source) {
        try (var serializer = new JsonSerializer<Object>(); var decoder = new UplinkPayloadPrecisionDeserializer()) {
            decoder.configure(CONFIG, false);
            var headers = new RecordHeaders();
            var result = (StandardUplinkMessage) decoder.deserialize(topic, headers,
                    serializer.serialize(topic, headers, source));
            assertExact(result);
            return result;
        }
    }

    /** 深层数字类型及精确值都必须保留。 */
    private void assertExact(StandardUplinkMessage message) {
        Map<?, ?> value = (Map<?, ?>) message.payload().get("payload");
        assertThat(value.get("decimal")).isEqualTo(new BigDecimal(DECIMAL));
        assertThat(value.get("integer")).isEqualTo(new BigInteger(INTEGER));
        assertThat(((List<?>) value.get("list")).getFirst()).isEqualTo(new BigDecimal(DECIMAL));
    }

    /** UUIDv7及语义版本是实际DevicePropertyReport前置。 */
    private RawUplinkMessage raw() {
        String wire = "{\"messageId\":\"" + Uuid7.generate() + "\",\"occurredAt\":\"2026-09-08T00:00:00Z\","
                + "\"modelVersion\":\"1.0.0\",\"payload\":{\"payload\":{\"decimal\":" + DECIMAL
                + ",\"integer\":" + INTEGER + ",\"list\":[" + DECIMAL + "]}}}";
        return new RawUplinkMessage(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                "tc/v1/project/device/up/property/report", wire.getBytes(StandardCharsets.UTF_8), 1, false,
                "test-client", Instant.parse("2026-09-08T00:00:00Z"), "0123456789abcdef0123456789abcdef");
    }
}
