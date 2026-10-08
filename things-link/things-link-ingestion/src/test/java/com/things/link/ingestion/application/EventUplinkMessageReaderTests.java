package com.things.link.ingestion.application;

import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventUplinkMessageReaderTests {
    private final EventUplinkMessageReader reader = new EventUplinkMessageReader();

    @Test
    void permitsEmptyParamsAndPreservesOriginalDecimalScaleAndUnicodeText() {
        assertThat(reader.read(json("{}")).params()).isEmpty();
        String upper = new String(json("{}"), StandardCharsets.UTF_8).toUpperCase(java.util.Locale.ROOT)
                .replace("MESSAGEID", "messageId").replace("MODELVERSION", "modelVersion")
                .replace("OCCURREDAT", "occurredAt").replace("PARAMS", "params");
        assertThat(reader.read(upper.getBytes(StandardCharsets.UTF_8)).params()).isEmpty();
        var report = reader.read(json("{\"integer\":9007199254740993123456789,\"decimal\":9007199254740993.123456789,\"scale\":1.0,\"text\":\"  原文🙂  \",\"switch\":true}"));
        assertThat(report.params()).containsEntry("integer", new BigInteger("9007199254740993123456789"))
                .containsEntry("decimal", new BigDecimal("9007199254740993.123456789"))
                .containsEntry("scale", new BigDecimal("1.0")).containsEntry("text", "  原文🙂  ");
        assertThat(((BigDecimal) report.params().get("scale")).scale()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{\"null\":null}", "{\"bad key\":1}",
            "{\"nested\":{}}", "{\"list\":[]}", "{\"number\":1e309}", "{\"number\":1e-309}",
            "{\"number\":123456789012345678901234567890123456789}", "{\"number\":NaN}",
            "{\"text\":\"\\ud800\"}", "{\"text\":\"\\udc00\"}", "{\"key\":1,\"k\\u0065y\":2}"})
    void rejectsUnsupportedParamsWithFixedDiagnostic(String params) {
        assertThatThrownBy(() -> reader.read(json(params))).isInstanceOf(InvalidUplinkMessageException.class)
                .hasMessage("EVENT_PAYLOAD_INVALID").hasNoCause();
    }

    @Test
    void rejectsEscapedNullCharacterWithOnlyFixedDiagnostic() {
        assertThatThrownBy(() -> reader.read(json("{\"text\":\"synthetic-secret\\u0000suffix\"}")))
                .isInstanceOf(InvalidUplinkMessageException.class)
                .hasMessage("EVENT_PAYLOAD_INVALID").hasNoCause();
    }

    @Test
    void rejectsUnknownDuplicateNullTrailingAndNonCanonicalEnvelope() {
        String valid = new String(json("{}"), StandardCharsets.UTF_8);
        for (String invalid : new String[] {
                valid.substring(0, valid.length() - 1) + ",\"eventKey\":\"alarm\"}",
                valid.replace("\"params\":{}", "\"params\":{},\"params\":{}"),
                valid + " {}", valid.replace("1.0.0", "01.0.0"), valid.replace("1.0.0", "65536.0.0"),
                valid.replace("\"1.0.0\"", "null"), valid.replace("2026-10-06T00:00:00Z", "2026-10-06"),
                valid.replace("\"messageId\"", "\"tenantId\""), "null", "[]" }) {
            assertThatThrownBy(() -> reader.read(invalid.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(InvalidUplinkMessageException.class).hasMessage("EVENT_PAYLOAD_INVALID").hasNoCause();
        }
        assertThatThrownBy(() -> reader.read(new byte[] {(byte) 0xc3, (byte) 0x28}))
                .isInstanceOf(InvalidUplinkMessageException.class).hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-5000-01-01T00:00:00Z", "+1000000-01-01T00:00:00Z", "+2026-10-06T00:00:00Z",
            "2026-10-06T24:00:00Z", "2026-10-06T00:00:00+01:00:00", "2026-10-06T00:00:00",
            "2026-02-30T00:00:00Z", "2026-10-06T00:60:00Z", "2026-10-06T00:00:61Z",
            "2026-10-06T00:00:00.1234567890Z", "2026-10-06T00:00:00+24:00", "2016-12-31T23:59:60+01:00",
            "2016-12-31T23:59:60-05:00", "2017-01-01T07:58:60+08:00"})
    void rejectsNonRfcOrUnstorableTimeWithOnlyFixedDiagnostic(String occurredAt) {
        assertThatThrownBy(() -> reader.read(timestamp(occurredAt)))
                .isInstanceOf(InvalidUplinkMessageException.class).hasMessage("EVENT_PAYLOAD_INVALID").hasNoCause();
    }

    @Test
    void preservesOffsetEquivalenceNanosecondsAndFourDigitYearEdges() {
        var expected = Instant.parse("2026-10-06T00:00:00.123456789Z");
        assertThat(reader.read(timestamp("2026-10-06t08:30:00.123456789+08:30")).occurredAt()).isEqualTo(expected);
        assertThat(reader.read(timestamp("2026-10-06T00:00:00.123456789z")).occurredAt()).isEqualTo(expected);
        assertThat(reader.read(timestamp("0000-01-01T00:00:00+18:00")).occurredAt())
                .isEqualTo(Instant.parse("-0001-12-31T06:00:00Z"));
        assertThat(reader.read(timestamp("9999-12-31T23:59:59.999999999-18:00")).occurredAt())
                .isEqualTo(Instant.parse("+10000-01-01T17:59:59.999999999Z"));
        assertThat(reader.read(timestamp("2016-12-31T23:59:60Z")).occurredAt())
                .isEqualTo(Instant.parse("2016-12-31T23:59:60Z"));
    }

    @Test
    void acceptsLargerRfcOffsetsWithoutLosingNanosYearEdgesOrUtcLeapSemantics() {
        assertThat(reader.read(timestamp("2026-10-06T18:01:00.123456789+18:01")).occurredAt())
                .isEqualTo(Instant.parse("2026-10-06T00:00:00.123456789Z"));
        assertThat(reader.read(timestamp("2026-10-06t23:59:00.000000001+23:59")).occurredAt())
                .isEqualTo(Instant.parse("2026-10-06T00:00:00.000000001Z"));
        assertThat(reader.read(timestamp("2026-10-06T00:00:00.999999999-23:59")).occurredAt())
                .isEqualTo(Instant.parse("2026-10-06T23:59:00.999999999Z"));
        assertThat(reader.read(timestamp("0000-01-01T00:00:00+23:59")).occurredAt())
                .isEqualTo(Instant.parse("-0001-12-31T00:01:00Z"));
        assertThat(reader.read(timestamp("9999-12-31T23:59:59.999999999-23:59")).occurredAt())
                .isEqualTo(Instant.parse("+10000-01-01T23:58:59.999999999Z"));
        assertThat(reader.read(timestamp("2017-01-01T23:58:60+23:59")).occurredAt())
                .isEqualTo(Instant.parse("2016-12-31T23:59:59Z"));
        assertThat(reader.read(timestamp("2016-12-31T00:00:60-23:59")).occurredAt())
                .isEqualTo(Instant.parse("2016-12-31T23:59:59Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-02-30T00:00:00+23:59", "2026-10-06T24:00:00+23:59",
            "2017-01-01T23:59:60+23:59", "2016-12-31T00:01:60-23:59", "2026-10-06T00:60:00+18:01"})
    void largerOffsetFallbackStillRejectsInvalidCalendarAndNonUtcLeapWithFixedDiagnostic(String occurredAt) {
        assertThatThrownBy(() -> reader.read(timestamp(occurredAt)))
                .isInstanceOf(InvalidUplinkMessageException.class).hasMessage("EVENT_PAYLOAD_INVALID").hasNoCause();
    }

    @Test
    void acceptsUtcLeapSecondAtOrdinaryPositiveAndNegativeOffsets() {
        assertThat(reader.read(timestamp("2017-01-01T07:59:60+08:00")).occurredAt())
                .isEqualTo(Instant.parse("2016-12-31T23:59:59Z"));
        assertThat(reader.read(timestamp("2016-12-31T18:59:60-05:00")).occurredAt())
                .isEqualTo(Instant.parse("2016-12-31T23:59:59Z"));
    }

    private static byte[] timestamp(String occurredAt) {
        return new String(json("{\"text\":\"synthetic-secret\"}"), StandardCharsets.UTF_8)
                .replace("2026-10-06T00:00:00Z", occurredAt).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void enforcesPayloadParameterAndUnicodeCodePointBoundaries() {
        assertThat(reader.read(json("{\"text\":\"" + "🙂".repeat(4096) + "\"}")).params()).hasSize(1);
        assertThatThrownBy(() -> reader.read(json("{\"text\":\"" + "🙂".repeat(4097) + "\"}")))
                .isInstanceOf(InvalidUplinkMessageException.class);
        StringBuilder params = new StringBuilder("{");
        for (int index = 0; index < 101; index++) {
            if (index > 0) params.append(',');
            params.append('"').append('p').append(index).append("\":true");
        }
        assertThatThrownBy(() -> reader.read(json(params.append('}').toString())))
                .isInstanceOf(InvalidUplinkMessageException.class);
        byte[] valid = json("{}");
        byte[] max = java.util.Arrays.copyOf(valid, 65_536);
        java.util.Arrays.fill(max, valid.length, max.length, (byte) ' ');
        assertThat(reader.read(max).params()).isEmpty();
        assertThat(reader.read(json(params.substring(0, params.lastIndexOf(",")) + "}")).params()).hasSize(100);
        assertThatThrownBy(() -> reader.read(new byte[65_537]))
                .isInstanceOf(InvalidUplinkMessageException.class);
        assertThatThrownBy(() -> reader.read(null)).isInstanceOf(InvalidUplinkMessageException.class);
    }

    private static byte[] json(String params) {
        return ("{\"messageId\":\"" + Uuid7.generate() + "\",\"modelVersion\":\"1.0.0\","
                + "\"occurredAt\":\"2026-10-06T00:00:00Z\",\"params\":" + params + "}").getBytes(StandardCharsets.UTF_8);
    }
}
