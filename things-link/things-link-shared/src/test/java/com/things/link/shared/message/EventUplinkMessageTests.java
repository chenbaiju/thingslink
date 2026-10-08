package com.things.link.shared.message;

import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventUplinkMessageTests {
    @Test
    void freezesScalarParamsAndRejectsMutableContainersAndNumbers() {
        Map<String, Object> original = new LinkedHashMap<>(Map.of("decimal", new BigDecimal("1.0")));
        var message = message(original);
        original.put("decimal", new BigDecimal("2"));
        assertThat(message.params()).containsEntry("decimal", new BigDecimal("1.0"));
        assertThatThrownBy(() -> message.params().put("new", true)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> message(Map.of("nested", Map.of("value", 1)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> message(Map.of("mutable", new AtomicInteger(1)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(message(Map.of()).params()).isEmpty();
    }

    @Test
    void requiresExplicitBoundedModelAndMqttProtocol() {
        for (String version : new String[] {"01.0.0", "65536.0.0", "1.0", "1.0.0 ", "999999999.0.0"})
            assertThatThrownBy(() -> EventUplinkMessage.validateModelVersion(version)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventUplinkMessage.validateModelVersion(null)).isInstanceOf(IllegalArgumentException.class);
        EventUplinkMessage.validateModelVersion("65535.0.65535");
        assertThatThrownBy(() -> new EventUplinkMessage(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                TransportProtocol.HTTP, "alarm", "1.0.0", Instant.now(), Instant.now(), "trace", 100, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorCannotBypassParameterResourceAndFiniteNumberBounds() {
        for (Object value : new Object[] {Double.NaN, Double.POSITIVE_INFINITY, new BigDecimal("1e309"),
                new BigDecimal("1e-309"), new BigDecimal("123456789012345678901234567890123456789"),
                "x".repeat(4097), "\ud800", new Object(), java.util.List.of(1)}) {
            assertThatThrownBy(() -> message(Map.of("parameter", value))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(message(Map.of("max", new BigDecimal("1e308"), "min", new BigDecimal("1e-308"))).params()).hasSize(2);
        var tooMany = new LinkedHashMap<String, Object>();
        for (int index = 0; index < 101; index++) tooMany.put("p" + index, true);
        assertThatThrownBy(() -> message(tooMany)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> message(Map.of("bad key", true))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> message(null)).isInstanceOf(IllegalArgumentException.class);
        var received = Instant.parse("2026-10-06T00:00:00Z");
        assertThatThrownBy(() -> new EventUplinkMessage(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), TransportProtocol.MQTT, "alarm", "1.0.0", received.plusSeconds(301),
                received, "trace", 100, Map.of())).isInstanceOf(IllegalArgumentException.class);
        for (int rawBytes : new int[] {0, -1, 65537}) {
            assertThatThrownBy(() -> new EventUplinkMessage(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                    Uuid7.generate(), TransportProtocol.MQTT, "alarm", "1.0.0", Instant.now(), Instant.now(),
                    "trace", rawBytes, Map.of())).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsDecodedNullCharacterWithoutIncludingOriginalText() {
        assertThatThrownBy(() -> message(Map.of("text", "synthetic-secret\0suffix")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("事件文本含不可存储空字符").hasNoCause();
        assertThat(message(Map.of("text", "原文\n\t😀")).params()).containsEntry("text", "原文\n\t😀");
    }

    @Test
    void internalConstructionRejectsUnstorableYearsButAllowsOffsetUtcEdgesAndNanoseconds() {
        var minimum = Instant.parse("0000-01-01T00:00:00Z").minusSeconds(86_400);
        var maximum = Instant.parse("+10000-01-01T00:00:00Z").plusSeconds(86_400);
        for (Instant invalid : new Instant[] {minimum.minusNanos(1), maximum,
                Instant.parse("-5000-01-01T00:00:00Z"), Instant.parse("+1000000-01-01T00:00:00Z")}) {
            assertThatThrownBy(() -> at(invalid, Instant.parse("+1000001-01-01T00:00:00Z")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("事件发生时刻超出四位日期存储边界").hasNoCause();
        }
        assertThat(at(minimum, minimum).occurredAt()).isEqualTo(minimum);
        assertThat(at(maximum.minusNanos(1), maximum).occurredAt()).isEqualTo(maximum.minusNanos(1));
        var precise = Instant.parse("2026-10-06T00:00:00.123456789Z");
        assertThat(at(precise, precise).occurredAt()).isEqualTo(precise);
    }

    private static EventUplinkMessage at(Instant occurredAt, Instant receivedAt) {
        return new EventUplinkMessage(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                TransportProtocol.MQTT, "alarm", "1.0.0", occurredAt, receivedAt, "trace", 100, Map.of());
    }

    private static EventUplinkMessage message(Map<String, Object> params) {
        return new EventUplinkMessage(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                TransportProtocol.MQTT, "alarm", "1.0.0", Instant.now(), Instant.now(), "trace", 100, params);
    }
}
