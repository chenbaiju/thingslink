package com.things.link.project.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S14-3a 服务期计算的领域级验收（S14-0 P5 冻结口径）。
 *
 * <p>只钉住两条规则：首次购买从支付时刻起算一个周期；续费从当前到期日延长一个周期，
 * 永不从支付日重算。周期推进按 UTC 日历语义（与 PostgreSQL {@code interval} 一致），
 * 因此月末与闰日由本测试直接钉死，而不是等到集成测试才发现偏移。
 */
@DisplayName("S14-3a 订阅服务期计算")
class SubscriptionServicePeriodTests {

    /** 模拟支付成功时刻：续费时它不得参与任何计算。 */
    private static final Instant PAID_AT = Instant.parse("2026-03-31T08:30:00Z");

    /** 首次购买：服务期从支付时刻开始，一年后结束。 */
    @Test
    void firstPurchaseStartsAtPaidAtAndLastsOnePeriod() {
        SubscriptionServicePeriod period = SubscriptionServicePeriod.forOrder(PAID_AT, null, "YEAR");

        assertThat(period.startsAt()).isEqualTo(PAID_AT);
        assertThat(period.endsAt()).isEqualTo(Instant.parse("2027-03-31T08:30:00Z"));
    }

    /** 续费从上一到期日延长，与支付时刻无关：同一次支付放在不同 paidAt 上结果必须一致。 */
    @Test
    void renewalExtendsFromPreviousEndsAtAndIgnoresPaidAt() {
        Instant previousEndsAt = Instant.parse("2027-01-15T00:00:00Z");

        SubscriptionServicePeriod early = SubscriptionServicePeriod.forOrder(
                Instant.parse("2026-10-01T00:00:00Z"), previousEndsAt, "YEAR");
        SubscriptionServicePeriod late = SubscriptionServicePeriod.forOrder(
                Instant.parse("2027-02-20T12:00:00Z"), previousEndsAt, "YEAR");

        assertThat(early.startsAt()).isEqualTo(previousEndsAt);
        assertThat(late.startsAt()).isEqualTo(previousEndsAt);
        assertThat(early.endsAt()).isEqualTo(Instant.parse("2028-01-15T00:00:00Z"));
        assertThat(late.endsAt()).isEqualTo(early.endsAt());
    }

    /** 按月续费同样按 UTC 日历推进：1 月 31 日加一月是 2 月末，不是固定 30 天。 */
    @Test
    void monthlyRenewalUsesUtcCalendarArithmetic() {
        SubscriptionServicePeriod period = SubscriptionServicePeriod.forOrder(
                Instant.parse("2026-05-20T00:00:00Z"), Instant.parse("2026-01-31T00:00:00Z"), "MONTH");

        assertThat(period.startsAt()).isEqualTo(Instant.parse("2026-01-31T00:00:00Z"));
        assertThat(period.endsAt()).isEqualTo(Instant.parse("2026-02-28T00:00:00Z"));
    }

    /** 闰日加一年回退到 2 月 28 日，与 PostgreSQL {@code interval '1 year'} 同语义。 */
    @Test
    void yearlyRenewalFromLeapDayFallsBackToFebruary28() {
        SubscriptionServicePeriod period = SubscriptionServicePeriod.forOrder(
                Instant.parse("2028-01-01T00:00:00Z"), Instant.parse("2028-02-29T00:00:00Z"), "YEAR");

        assertThat(period.endsAt()).isEqualTo(Instant.parse("2029-02-28T00:00:00Z"));
    }

    /** FREE 的 NONE 周期没有可延长的时长：付费订单不得出现，遇到即失败而不是猜默认周期。 */
    @Test
    void nonPaidBillingPeriodIsRejected() {
        assertThatThrownBy(() -> SubscriptionServicePeriod.forOrder(PAID_AT, null, "NONE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MONTH 或 YEAR");
    }
}
