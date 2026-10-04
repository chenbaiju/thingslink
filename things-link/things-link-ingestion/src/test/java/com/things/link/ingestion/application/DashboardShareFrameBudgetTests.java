package com.things.link.ingestion.application;

import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/** 固定桶传输预算的真实边界，不通过长时间发送数万个提示猜测滑动窗口。 */
class DashboardShareFrameBudgetTests {
    /** 帧恰好32KiB可接受，单帧溢出不占用窗口。 */
    @Test
    void frameAndAggregateLimitsChargeEveryAttempt() {
        DashboardShareFrameBudget budget = new DashboardShareFrameBudget();
        assertThat(budget.reserve(32769, 0)).isFalse();
        for (int index = 0; index < 64; index++) assertThat(budget.reserve(32768, 0)).isTrue();
        assertThat(budget.reserve(1, 0)).isFalse();
        assertThat(budget.reserve(-1, 0)).isFalse();
    }

    /** 秒边界不能提前清除仍在60秒内的帧；保守多计不足一秒。 */
    @Test
    void rollingWindowDoesNotResetAtWallMinuteOrReuseLiveBucket() {
        DashboardShareFrameBudget budget = new DashboardShareFrameBudget();
        long sent = TimeUnit.MILLISECONDS.toNanos(59999);
        for (int index = 0; index < 64; index++) assertThat(budget.reserve(32768, sent)).isTrue();
        assertThat(budget.reserve(1, TimeUnit.SECONDS.toNanos(60))).isFalse();
        assertThat(budget.reserve(1, TimeUnit.SECONDS.toNanos(119))).isFalse();
        assertThat(budget.reserve(32768, TimeUnit.SECONDS.toNanos(120))).isTrue();
    }
}
