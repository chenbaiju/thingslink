package com.things.link.project.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S14-3b 升级按整日折算的纯算术验收（S14-0 P5 冻结口径）。
 *
 * <p>算例全部写死可独立重算的数值，不复用生产常量：四档参考年价 ￥0/￥2,980/￥5,980/￥11,980
 * 对应日价 0/816/1638/3282 分（￥2,980 ÷ 365 = 816.438… 舍 816，￥5,980 ÷ 365 = 1638.356… 舍 1638）。
 * 真库集成测试负责证明这套算术确实被订单与订阅生效路径使用，这里只钉算术本身。
 */
@DisplayName("S14-3b 升级按整日折算")
class SubscriptionUpgradeProrationTests {

    /** 标准档参考年价（￥2,980）。 */
    private static final long STANDARD_ANNUAL = 298000L;
    /** 企业档参考年价（￥5,980）。 */
    private static final long ENTERPRISE_ANNUAL = 598000L;
    /** 一个 365 天的服务期起点（2026 年不是闰年）。 */
    private static final Instant PERIOD_START = Instant.parse("2026-01-01T00:00:00Z");
    /** 服务期终点：起点加一年，恰好 365 天。 */
    private static final Instant PERIOD_END = Instant.parse("2027-01-01T00:00:00Z");

    /** 首日升级：剩余 365 天，抵扣 297840、应收 597870、补差 300030。 */
    @Test
    void firstDayOfPeriodProratesWholeYear() {
        SubscriptionUpgradeProration proration = SubscriptionUpgradeProration.between(
                PERIOD_START, PERIOD_END, STANDARD_ANNUAL, ENTERPRISE_ANNUAL);

        assertThat(proration.remainingDays()).isEqualTo(365);
        assertThat(proration.oldDailyPriceCents()).isEqualTo(816L);
        assertThat(proration.newDailyPriceCents()).isEqualTo(1638L);
        assertThat(proration.creditCents()).isEqualTo(816L * 365L);
        assertThat(proration.chargeCents()).isEqualTo(1638L * 365L);
        assertThat(proration.differenceCents()).isEqualTo(300030L);
    }

    /** 中点升级：2026-07-02 距终点 183 天，补差 150426。 */
    @Test
    void middleOfPeriodProratesRemainingWholeDays() {
        SubscriptionUpgradeProration proration = SubscriptionUpgradeProration.between(
                Instant.parse("2026-07-02T00:00:00Z"), PERIOD_END, STANDARD_ANNUAL, ENTERPRISE_ANNUAL);

        assertThat(proration.remainingDays()).isEqualTo(183);
        assertThat(proration.creditCents()).isEqualTo(149328L);
        assertThat(proration.chargeCents()).isEqualTo(299754L);
        assertThat(proration.differenceCents()).isEqualTo(150426L);
    }

    /** 末日升级：服务期还剩整 1 天，补差只是 1 天差价 822 分。 */
    @Test
    void lastDayOfPeriodProratesSingleDay() {
        SubscriptionUpgradeProration proration = SubscriptionUpgradeProration.between(
                Instant.parse("2026-12-31T00:00:00Z"), PERIOD_END, STANDARD_ANNUAL, ENTERPRISE_ANNUAL);

        assertThat(proration.remainingDays()).isEqualTo(1);
        assertThat(proration.creditCents()).isEqualTo(816L);
        assertThat(proration.chargeCents()).isEqualTo(1638L);
        assertThat(proration.differenceCents()).isEqualTo(822L);
    }

    /** 剩余天数向上取整：不足一整天仍算一天；多出一天一秒则算两天。 */
    @Test
    void remainingDaysRoundsUpAnyPartialDay() {
        assertThat(SubscriptionUpgradeProration.remainingDays(
                Instant.parse("2026-12-31T12:00:00Z"), PERIOD_END))
                .as("只剩 12 小时也算 1 天")
                .isEqualTo(1);
        assertThat(SubscriptionUpgradeProration.remainingDays(
                Instant.parse("2026-12-30T23:59:59Z"), PERIOD_END))
                .as("1 天 1 秒向上取整为 2 天")
                .isEqualTo(2);
        assertThat(SubscriptionUpgradeProration.remainingDays(PERIOD_START, PERIOD_END))
                .as("恰好整 365 天不得被取整成 366")
                .isEqualTo(365);
    }

    /** 日单价一律按年价 ÷ 365 四舍五入到分（half-up），且没有浮点误差。 */
    @Test
    void dailyPriceRoundsHalfUpToCents() {
        assertThat(SubscriptionUpgradeProration.dailyPriceCents(0L)).isZero();
        assertThat(SubscriptionUpgradeProration.dailyPriceCents(365L)).isEqualTo(1L);
        assertThat(SubscriptionUpgradeProration.dailyPriceCents(547L))
                .as("547 / 365 = 1.4986 舍 1")
                .isEqualTo(1L);
        assertThat(SubscriptionUpgradeProration.dailyPriceCents(548L))
                .as("548 / 365 = 1.5014 入 2")
                .isEqualTo(2L);
        assertThat(SubscriptionUpgradeProration.dailyPriceCents(STANDARD_ANNUAL)).isEqualTo(816L);
        assertThat(SubscriptionUpgradeProration.dailyPriceCents(ENTERPRISE_ANNUAL)).isEqualTo(1638L);
        assertThat(SubscriptionUpgradeProration.dailyPriceCents(1198000L))
                .as("专业档 ￥11,980 ÷ 365 = 3282.19 舍 3282")
                .isEqualTo(3282L);
    }

    /** 新旧档日价相同时补差正好为 0：订单仍要落，但金额是 0。 */
    @Test
    void equalDailyPricesProduceZeroDifference() {
        SubscriptionUpgradeProration proration = SubscriptionUpgradeProration.between(
                PERIOD_START, PERIOD_END, STANDARD_ANNUAL, STANDARD_ANNUAL);

        assertThat(proration.creditCents()).isEqualTo(proration.chargeCents());
        assertThat(proration.differenceCents()).isZero();
    }

    /** 差额为负时记 0：应收小于抵扣不允许出现「倒找钱」的负补差。 */
    @Test
    void negativeDifferenceIsFlooredAtZero() {
        SubscriptionUpgradeProration proration = SubscriptionUpgradeProration.between(
                PERIOD_START, PERIOD_END, ENTERPRISE_ANNUAL, 100000L);

        assertThat(proration.chargeCents()).isLessThan(proration.creditCents());
        assertThat(proration.differenceCents()).isZero();
    }

    /** 服务期已结束或恰好此刻结束：剩余整日无法为正，必须拒绝而不是折算成 0 或负数。 */
    @Test
    void rejectsPeriodThatAlreadyEnded() {
        assertThatThrownBy(() -> SubscriptionUpgradeProration.between(
                PERIOD_END, PERIOD_END, STANDARD_ANNUAL, ENTERPRISE_ANNUAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubscriptionUpgradeProration.between(
                PERIOD_END.plusSeconds(1), PERIOD_END, STANDARD_ANNUAL, ENTERPRISE_ANNUAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 被篡改的折算快照无法重建：金额与日单价/剩余天数的算式必须自洽。 */
    @Test
    void rejectsInconsistentSnapshot() {
        assertThatThrownBy(() -> new SubscriptionUpgradeProration(PERIOD_START, PERIOD_END, 365,
                816L, 1638L, 297840L, 597870L, 1L))
                .as("补差必须等于 max(应收 - 抵扣, 0)")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SubscriptionUpgradeProration(PERIOD_START, PERIOD_END, 0,
                816L, 1638L, 0L, 0L, 0L))
                .as("剩余整日必须为正")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
