package com.things.link.telemetry.domain;

import java.time.Duration;

/**
 * 历史查询粒度。
 *
 * <p>顺序就是 ADR 0015 规定的自动升粒度顺序，不能随意调整，否则同一时间窗的实际粒度会发生兼容性变化。</p>
 */
public enum HistoryGranularity {
    /** 原始点，不经过聚合。 */
    RAW(null),
    /** 一分钟桶。 */
    ONE_MINUTE(Duration.ofMinutes(1)),
    /** 一小时桶。 */
    ONE_HOUR(Duration.ofHours(1)),
    /** 一天桶，按 UTC 自然日切分。 */
    ONE_DAY(Duration.ofDays(1));

    /** 固定桶宽；原始点没有桶宽。 */
    private final Duration bucketWidth;

    /** 创建粒度常量。 */
    HistoryGranularity(Duration bucketWidth) {
        this.bucketWidth = bucketWidth;
    }

    /** @return 固定桶宽；原始粒度返回 {@code null} */
    public Duration bucketWidth() {
        return bucketWidth;
    }

    /** @return 是否为预聚合粒度 */
    public boolean aggregated() {
        return bucketWidth != null;
    }
}
