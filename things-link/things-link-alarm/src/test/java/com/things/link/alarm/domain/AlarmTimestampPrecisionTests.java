package com.things.link.alarm.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AlarmTimestampPrecisionTests {
    @Test void roundsBothSidesOfHalfMicrosecondAndCarriesIntoNextSecond() {
        assertEquals(Instant.parse("2026-09-23T12:28:23.856301Z"),
                AlarmTimestampPrecision.toMicros(Instant.parse("2026-09-23T12:28:23.856301499Z")));
        assertEquals(Instant.parse("2026-09-23T12:28:23.856302Z"),
                AlarmTimestampPrecision.toMicros(Instant.parse("2026-09-23T12:28:23.856301500Z")));
        assertEquals(Instant.parse("2026-09-23T12:28:24Z"),
                AlarmTimestampPrecision.toMicros(Instant.parse("2026-09-23T12:28:23.999999500Z")));
    }
}
