package com.things.link.project.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证策略基点驱动的 UTC 日额度四级状态与大数安全。 */
class QuotaMetricUsageTests {

    /** 80%/100%/120% 边界必须依次进入软限、硬限和降级，且最高等级优先。 */
    @Test
    void classifiesConfiguredDailyThresholdsInSeverityOrder() {
        assertThat(usage(799).status()).isEqualTo(QuotaMetricUsage.Status.NORMAL);
        assertThat(usage(800).status()).isEqualTo(QuotaMetricUsage.Status.SOFT_LIMIT);
        assertThat(usage(1000).status()).isEqualTo(QuotaMetricUsage.Status.HARD_LIMIT);
        assertThat(usage(1199).status()).isEqualTo(QuotaMetricUsage.Status.HARD_LIMIT);
        assertThat(usage(1200).status()).isEqualTo(QuotaMetricUsage.Status.DEGRADED);
    }

    /** 大账单判定不得因 long 乘法溢出而回退到 NORMAL。 */
    @Test
    void comparesNearLongMaximumWithoutOverflow() {
        long limit = Long.MAX_VALUE / 2;
        QuotaMetricUsage usage = new QuotaMetricUsage(
                QuotaMetric.UPLINK_MESSAGE, limit, limit, Long.MAX_VALUE, 8000, 12000);

        assertThat(usage.status()).isEqualTo(QuotaMetricUsage.Status.DEGRADED);
    }

    /** @param used 租户日用量 @return 1000 上限、80%/120% 阈值的用量对象 */
    private static QuotaMetricUsage usage(long used) {
        return new QuotaMetricUsage(QuotaMetric.UPLINK_MESSAGE, 1000L, used, used, 8000, 12000);
    }
}
