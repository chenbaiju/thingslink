package com.things.link.project.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 一笔资源包购买的订单快照（S14-4a）。
 *
 * <p>它在 {@link TenantOrder} 里与 {@code amountCents} 同生共死：支付成功事件只许原样读回这份
 * 「买的是哪个维度、多少额度、多长周期、从何时起算」，不许在支付时按新的 {@code now} 重算。
 *
 * <p>{@code startsAt} 为 {@code null} 表示「支付成功时起算」；非空表示下单时指定未来起算时刻
 * （P6：startsAt = 生效时刻或购买时指定未来时刻）。{@code periodMonths} 是 P6 默认的 12 个
 * UTC 日历月基数，生效事务用它换算 {@code endsAt}。
 *
 * @param dimensionCode 冻结维度编码（取自 product-revision-1 的 PlanDimension.code）
 * @param amount 该包为维度增加的额度，恒为正
 * @param unit 额度单位；必须与基础档该维度冻结单位一致才参与合成
 * @param window 计量窗口；必须与基础档该维度冻结窗口一致才参与合成
 * @param periodMonths 包周期基数（UTC 日历月），1..120
 * @param startsAt 起算时刻；{@code null} 表示支付成功时起算
 */
public record ResourcePackagePurchase(
        String dimensionCode,
        long amount,
        String unit,
        String window,
        int periodMonths,
        Instant startsAt) {

    /** 订单快照必须是可机器重放的确定值。 */
    public ResourcePackagePurchase {
        if (dimensionCode == null || !dimensionCode.matches("^[A-Z][A-Z0-9_]{2,63}$")) {
            throw new IllegalArgumentException("资源包维度编码不合法");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("资源包额度必须为正数");
        }
        Objects.requireNonNull(unit, "资源包单位不得为空");
        Objects.requireNonNull(window, "资源包窗口不得为空");
        if (periodMonths < 1 || periodMonths > 120) {
            throw new IllegalArgumentException("资源包周期基数必须在 1..120 个 UTC 日历月之间");
        }
    }
}
