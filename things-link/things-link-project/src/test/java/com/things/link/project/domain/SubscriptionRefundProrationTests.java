package com.things.link.project.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0166 的独立金额算例；不冒充来源链或真实退款事务验收。 */
class SubscriptionRefundProrationTests {

    /** 固定 UTC 起点，所有边界用例不依赖机器时间。 */
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    /** P5 的 10/30 未使用比例，整数分精确可重算。 */
    @Test
    void tenUnusedDaysOfThirtyRefundOneThird() {
        var result = calculate(30, 20, 30_000, 0);
        assertThat(result.totalDays()).isEqualTo(30);
        assertThat(result.unusedDays()).isEqualTo(10);
        assertThat(result.refundableCents()).isEqualTo(10_000);
    }

    /** 预付未开始周期可全退，报价早于起点不能扩大原始天数。 */
    @Test
    void prepaidPeriodIsCappedAtWholePeriod() {
        assertThat(calculate(30, -300, 30_001, 0).refundableCents()).isEqualTo(30_001);
        assertThat(calculate(30, 0, 30_001, 0).unusedDays()).isEqualTo(30);
    }

    /** 到期瞬间和到期之后都不返还已经使用的周期。 */
    @Test
    void endedPeriodsHaveZeroRefund() {
        assertThat(calculate(30, 30, 30_000, 0).refundableCents()).isZero();
        assertThat(calculate(30, 31, 30_000, 0).unusedDays()).isZero();
    }

    /** 一日余一纳秒与整一日是两种不同的取整结果。 */
    @Test
    void nanosecondRemainderCountsAsAnotherDay() {
        Instant end = START.plus(30, ChronoUnit.DAYS);
        Instant dayBefore = end.minus(1, ChronoUnit.DAYS);
        assertThat(SubscriptionRefundProration.between(START, end, dayBefore, 30_000, 0)
                .refundableCents()).isEqualTo(1000);
        assertThat(SubscriptionRefundProration.between(START, end, dayBefore.minusNanos(1), 30_000, 0)
                .refundableCents()).isEqualTo(2000);
        assertThat(SubscriptionRefundProration.between(START, end, end.minusNanos(1), 30_000, 0)
                .unusedDays()).isEqualTo(1);
    }

    /** 原始周期自身存在纳秒余数时，总天数也必须向上取整。 */
    @Test
    void partialOriginalDayUsesCeilingDenominator() {
        var result = SubscriptionRefundProration.between(START, START.plusSeconds(86400).plusNanos(1),
                START.plusNanos(1), 100, 0);
        assertThat(result.totalDays()).isEqualTo(2);
        assertThat(result.refundableCents()).isEqualTo(50);
    }

    /** 月与闰年使用原始区间，不强行按 30 或 365 日改写。 */
    @Test
    void calendarIntervalsRetainLeapAndMonthLengths() {
        var leap = SubscriptionRefundProration.between(Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2024-12-31T00:00:00Z"), 36_600, 0);
        assertThat(leap.totalDays()).isEqualTo(366);
        assertThat(leap.refundableCents()).isEqualTo(100);
        var month = SubscriptionRefundProration.between(Instant.parse("2024-02-01T00:00:00Z"),
                Instant.parse("2024-03-01T00:00:00Z"), Instant.parse("2024-02-20T00:00:00Z"), 2900, 0);
        assertThat(month.totalDays()).isEqualTo(29);
        assertThat(month.refundableCents()).isEqualTo(1000);
    }

    /** 升级只传实际收取的补差，余下三分之一舍去不足一分。 */
    @Test
    void upgradeDifferenceIsNotExpandedIntoGrossCharge() {
        assertThat(calculate(3, 2, 101, 0).refundableCents()).isEqualTo(33);
        assertThat(calculate(3, 2, 0, 0).refundableCents()).isZero();
    }

    /** 已退累计限制本次可退金额，不允许重复超过原价。 */
    @Test
    void previousRefundLimitsAvailableAmount() {
        assertThat(calculate(30, 20, 30_000, 29_900).refundableCents()).isEqualTo(100);
        assertThat(calculate(30, 0, 30_000, 30_000).refundableCents()).isZero();
    }

    /** 中间乘法超 long 仍须准确；不能饱和、溢出或用 double。 */
    @Test
    void longMaximumAmountUsesExactIntermediateArithmetic() {
        assertThat(calculate(3, 1, Long.MAX_VALUE, 0).refundableCents()).isEqualTo(6_148_914_691_236_517_204L);
        assertThat(calculate(3, 0, Long.MAX_VALUE, 0).refundableCents()).isEqualTo(Long.MAX_VALUE);
    }

    /** Instant 全区间也能计算，不能把天数错误收窄到 int。 */
    @Test
    void extremeInstantIntervalDoesNotOverflowDays() {
        var result = SubscriptionRefundProration.between(Instant.MIN, Instant.MAX, Instant.MIN, 100, 0);
        assertThat(result.totalDays()).isGreaterThan(Integer.MAX_VALUE);
        assertThat(result.refundableCents()).isEqualTo(100);
    }

    /** 资金累计和服务期输入异常明确失败。 */
    @Test
    void rejectsInvalidFacts() {
        assertThatThrownBy(() -> calculate(0, 0, 100, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculate(-1, 0, 100, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculate(30, 0, -1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculate(30, 0, 100, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculate(30, 0, 100, 101)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 以固定起点表示整日算例。 */
    private SubscriptionRefundProration calculate(long days, long elapsed, long amount, long refunded) {
        return SubscriptionRefundProration.between(START, START.plus(days, ChronoUnit.DAYS),
                START.plus(elapsed, ChronoUnit.DAYS), amount, refunded);
    }
}
