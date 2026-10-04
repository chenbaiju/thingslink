package com.things.link.alarm.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** 告警持久事实与公开来源共用的 PostgreSQL 微秒时间合同。 */
public final class AlarmTimestampPrecision {
    private AlarmTimestampPrecision() { }

    /** 写入前四舍五入到微秒，包含跨秒进位。 */
    public static Instant toMicros(Instant value) {
        return value.plusNanos(500).truncatedTo(ChronoUnit.MICROS);
    }
}
