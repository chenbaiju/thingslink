package com.things.link.project.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * 一次订阅生效的计算服务期（S14-3a，S14-0 P5 冻结口径）。
 *
 * <p>两条冻结规则在这里唯一实现：
 * <ul>
 *   <li><b>首次购买</b>（当前没有带到期日的生效订阅）：{@code startsAt = paidAt}，
 *       {@code endsAt = startsAt + 一个计费周期}；</li>
 *   <li><b>续费</b>（当前生效订阅有 {@code endsAt}）：{@code startsAt = previousEndsAt}，
 *       {@code endsAt = previousEndsAt + 一个计费周期} —— 从当前到期日**延长**，
 *       永远不从 {@code paidAt} 重算，因此支付回调迟到或重试都不会缩短/漂移服务期。</li>
 * </ul>
 *
 * <p>计费周期按 UTC 日历推进（与 PostgreSQL {@code interval '1 month'/'1 year'} 同语义）：
 * 1 月 31 日加一月为 2 月末、闰日 2 月 29 日加一年为次年 2 月 28 日。{@code NONE} 没有周期可言，
 * 付费订单不得出现，遇到即失败而不是猜一个默认周期。升级的按整日折算属 S14-3b，不在本类。
 *
 * @param startsAt 新服务期起始时刻（UTC）
 * @param endsAt 新服务期结束时刻（UTC）
 */
public record SubscriptionServicePeriod(Instant startsAt, Instant endsAt) {

    /** 服务期必须是有效区间，且只允许付费计费周期（MONTH/YEAR）。 */
    public SubscriptionServicePeriod {
        Objects.requireNonNull(startsAt, "服务期起始时刻不得为空");
        Objects.requireNonNull(endsAt, "服务期结束时刻不得为空");
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("服务期结束时刻必须晚于起始时刻");
        }
    }

    /**
     * 按冻结规则计算一次订单生效后的服务期。
     *
     * @param paidAt 本次支付成功时刻，仅在首次购买时作为服务期起点
     * @param previousEndsAt 当前生效订阅的到期时刻；{@code null} 表示首次购买（含长期 FREE 被取代）
     * @param billingPeriod 计费周期快照，仅接受 {@code MONTH} 或 {@code YEAR}
     * @return 计算出的服务期
     * @throws IllegalArgumentException 计费周期为空或不是付费周期
     */
    public static SubscriptionServicePeriod forOrder(Instant paidAt, Instant previousEndsAt,
                                                     String billingPeriod) {
        Objects.requireNonNull(paidAt, "支付成功时刻不得为空");
        Instant startsAt = previousEndsAt != null ? previousEndsAt : paidAt;
        return new SubscriptionServicePeriod(startsAt, advance(startsAt, billingPeriod));
    }

    /**
     * 按 UTC 日历推进一个计费周期。
     *
     * @param startsAt 周期起点
     * @param billingPeriod 计费周期快照
     * @return 周期终点
     * @throws IllegalArgumentException 计费周期不是 MONTH/YEAR
     */
    private static Instant advance(Instant startsAt, String billingPeriod) {
        ZonedDateTime utcStart = startsAt.atZone(ZoneOffset.UTC);
        ZonedDateTime utcEnd = switch (billingPeriod) {
            case "MONTH" -> utcStart.plusMonths(1);
            case "YEAR" -> utcStart.plusYears(1);
            default -> throw new IllegalArgumentException(
                    "订单计费周期必须是 MONTH 或 YEAR，实际为: " + billingPeriod);
        };
        return utcEnd.toInstant();
    }
}
