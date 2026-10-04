package com.things.link.project.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S14-3c：P4 五个通知时间点的算法纯单测。
 *
 * <p>用固定 Instant 独立重算，不复用生产常量，避免「两边同时改错」；永久 FREE（{@code endsAt == null}）
 * 必须被显式拒绝，而不是被当成「早已到期」。
 */
class SubscriptionNotificationKindTests {

    /** 服务期终点；五个时间点全部由它推导。 */
    private static final Instant ENDS_AT = Instant.parse("2027-01-01T00:00:00Z");

    /** 到期前 7 天、到期日、宽限第 7 天、宽限结束前 1 天四个点全部落在服务期终点上。 */
    @Test
    void derivesFourPeriodPointsFromExpiryInExactUtcHours() {
        SubscriptionLifecycleState active = state(ENDS_AT, null, null);

        assertThat(SubscriptionNotificationKind.EXPIRY_MINUS_7_DAYS.fireAt(active))
                .isEqualTo(Instant.parse("2026-12-25T00:00:00Z"));
        assertThat(SubscriptionNotificationKind.EXPIRY_DAY.fireAt(active)).isEqualTo(ENDS_AT);
        assertThat(SubscriptionNotificationKind.GRACE_DAY_7.fireAt(active))
                .isEqualTo(Instant.parse("2027-01-08T00:00:00Z"));
        assertThat(SubscriptionNotificationKind.GRACE_END_MINUS_1_DAY.fireAt(active))
                .as("宽限 14 天，结束前 1 天 = 到期日 + 13 × 24 小时")
                .isEqualTo(Instant.parse("2027-01-14T00:00:00Z"));
        assertThat(SubscriptionNotificationKind.RESTRICTED_SWITCH.fireAt(active))
                .as("尚未切换受限时该时间点不可推导，必须返回空而不是猜一个时刻")
                .isNull();
    }

    /** 切换后即时这个点只由实际受限时刻决定，且随受限时刻变化。 */
    @Test
    void restrictedSwitchPointUsesActualRestrictionInstant() {
        Instant restrictedAt = ENDS_AT.plus(Duration.ofDays(14));
        SubscriptionLifecycleState restricted = state(ENDS_AT, restrictedAt, restrictedAt);

        assertThat(SubscriptionNotificationKind.RESTRICTED_SWITCH.fireAt(restricted)).isEqualTo(restrictedAt);
    }

    /** 长期 FREE 没有服务期终点：任何到点计算都必须失败，而不是把 NULL 当已到期。 */
    @Test
    void perpetualSubscriptionCannotProduceNotificationPoints() {
        SubscriptionLifecycleState perpetual = new SubscriptionLifecycleState(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), SubscriptionStatus.ACTIVE,
                Instant.parse("2026-01-01T00:00:00Z"), null, null, null);

        assertThatThrownBy(() -> SubscriptionNotificationKind.EXPIRY_DAY.fireAt(perpetual))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 构造一条生命周期事实。
     *
     * @param endsAt 服务期终点
     * @param graceEndsAt 宽限终点
     * @param restrictedAt 受限时刻
     * @return 订阅事实
     */
    private SubscriptionLifecycleState state(Instant endsAt, Instant graceEndsAt, Instant restrictedAt) {
        return new SubscriptionLifecycleState(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                SubscriptionStatus.ACTIVE, ENDS_AT.minus(Duration.ofDays(365)), endsAt, graceEndsAt,
                restrictedAt);
    }
}
