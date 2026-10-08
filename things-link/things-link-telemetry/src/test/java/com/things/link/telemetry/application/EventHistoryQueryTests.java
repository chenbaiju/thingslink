package com.things.link.telemetry.application;

import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class EventHistoryQueryTests {
    @Test void defaultsAndAllFourScalarHistoryFiltersUseRawIntent() {
        assertThat(EventHistoryQuery.parse(Map.of()).limit()).isEqualTo(20);
        var query = EventHistoryQuery.parse(Map.of("eventKey", new String[]{"overheat"}, "level", new String[]{"WARNING"},
                "thingModelVersionId", new String[]{UUID.randomUUID().toString()}, "from", new String[]{"2026-10-06T10:00:00+08:00"},
                "to", new String[]{"2026-10-06T03:00:00Z"}, "limit", new String[]{"100"}));
        assertThat(query.lower()).isEqualTo(Instant.parse("2026-10-06T02:00:00Z"));
        assertThat(query.from()).isEqualTo("2026-10-06T10:00:00+08:00");
        assertThat(query.limit()).isEqualTo(100);
    }
    @Test void rejectsUnknownRepeatedBlankAndInvalidFiltersWithFixedCode() {
        for (Map<String, String[]> values : java.util.List.of(
                Map.of("unexpected", new String[]{"private"}), Map.of("level", new String[]{"INFO", "ERROR"}),
                Map.of("cursor", new String[]{" "}), Map.of("level", new String[]{"info"}),
                Map.of("eventKey", new String[]{"_unaddressable"}), Map.of("thingModelVersionId", new String[]{"1-1-1-1-1"}),
                Map.of("limit", new String[]{"101"}), Map.of("limit", new String[]{"0"}), Map.of("limit", new String[]{"+1"}),
                Map.of("from", new String[]{"2026-10-06T00:00:00Z"}, "to", new String[]{"2026-10-06T00:00:00Z"}))) {
            assertThatThrownBy(() -> EventHistoryQuery.parse(values)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.errorCode().code()).isEqualTo(10001));
        }
        assertThatThrownBy(() -> EventHistoryQuery.requireDetailParameters(Map.of("limit", new String[]{"1"}))).isInstanceOf(BusinessException.class);
    }
    @Test void rejectsLenientDatesAndUnstorableExtendedYearsButPreservesNanos() {
        for (String raw : java.util.List.of("2026-02-30T00:00:00Z", "+10000-01-01T00:00:00Z", "-5000-01-01T00:00:00Z",
                "2026-10-06", "2026-10-06T24:00:00Z", "2026-10-06T00:00:00.1234567890Z", "2026-10-06T00:00:00+24:00")) {
            assertThatThrownBy(() -> EventHistoryQuery.time(raw)).isInstanceOf(BusinessException.class);
        }
        assertThat(EventHistoryQuery.time("2026-10-06T00:00:00.123456789Z").getNano()).isEqualTo(123456789);
        assertThat(EventHistoryQuery.time("0000-01-01T00:00:00+23:59")).isEqualTo(Instant.parse("-0001-12-31T00:01:00Z"));
    }
    @Test void rfc3339LowercaseLeapSecondAndOffsetNanosMatchMqttInterpretation() {
        assertThat(EventHistoryQuery.time("2026-10-06t00:00:00.123456789z")).isEqualTo(Instant.parse("2026-10-06T00:00:00.123456789Z"));
        assertThat(EventHistoryQuery.time("2016-12-31T23:59:60Z")).isEqualTo(Instant.parse("2016-12-31T23:59:59Z"));
        assertThat(EventHistoryQuery.time("2026-10-06t08:00:00.000000001+08:00")).isEqualTo(Instant.parse("2026-10-06T00:00:00.000000001Z"));
        assertThatThrownBy(() -> EventHistoryQuery.time("2016-12-31T12:59:60Z")).isInstanceOf(BusinessException.class);
    }
    @Test void largerRfc3339OffsetsRetainStrictCalendarNanosAndUtcLeapSemantics() {
        assertThat(EventHistoryQuery.time("2026-10-06T18:01:00.123456789+18:01")).isEqualTo(Instant.parse("2026-10-06T00:00:00.123456789Z"));
        assertThat(EventHistoryQuery.time("2026-10-06t23:59:00.000000001+23:59")).isEqualTo(Instant.parse("2026-10-06T00:00:00.000000001Z"));
        assertThat(EventHistoryQuery.time("2026-10-06T00:00:00.999999999-23:59")).isEqualTo(Instant.parse("2026-10-06T23:59:00.999999999Z"));
        var query = EventHistoryQuery.parse(Map.of("from", new String[]{"2026-10-06T23:59:00.000000001+23:59"},
                "to", new String[]{"2026-10-06T00:00:00.000000002Z"}));
        assertThat(query.lower()).isBefore(query.upper());
        assertThat(EventHistoryQuery.time("2017-01-01T23:58:60+23:59")).isEqualTo(Instant.parse("2016-12-31T23:59:59Z"));
        for (String raw : java.util.List.of("2026-02-30T00:00:00+23:59", "2026-10-06T24:00:00+23:59", "2017-01-01T23:59:60+23:59"))
            assertThatThrownBy(() -> EventHistoryQuery.time(raw)).isInstanceOf(BusinessException.class);
    }
    @Test void allOffsetsValidateLeapSecondInUtcRatherThanLocalClock() {
        assertThat(EventHistoryQuery.time("2017-01-01T07:59:60+08:00")).isEqualTo(Instant.parse("2016-12-31T23:59:59Z"));
        assertThat(EventHistoryQuery.time("2016-12-31T18:59:60-05:00")).isEqualTo(Instant.parse("2016-12-31T23:59:59Z"));
        for (String raw : java.util.List.of("2016-12-31T23:59:60+01:00", "2016-12-31T23:59:60-05:00", "2017-01-01T07:58:60+08:00"))
            assertThatThrownBy(() -> EventHistoryQuery.time(raw)).isInstanceOf(BusinessException.class);
    }

}
