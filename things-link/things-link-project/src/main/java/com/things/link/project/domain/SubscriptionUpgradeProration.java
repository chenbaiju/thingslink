package com.things.link.project.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 一次升级的按整日折算快照（S14-3b，S14-0 P5 冻结口径）。
 *
 * <p>这里唯一实现 P5 的升级折算规则，升级订单的金额与生效事务都只读这一份结果：
 * <ul>
 *   <li>{@code remainingDays = ceil((periodEnd - now) / 1 day)}，恒至少 1；</li>
 *   <li>日单价 {@code = round-half-up(年价 / 365)}，一律人民币分；</li>
 *   <li>{@code credit = 旧档日单价 × remainingDays}、{@code charge = 新档日单价 × remainingDays}；</li>
 *   <li>{@code difference = max(0, charge - credit)} —— 差额为负记 0。</li>
 * </ul>
 *
 * <p><b>为什么日单价先取整、再乘天数。</b>P5 冻结的是「日单价按分四舍五入」，不是「总额
 * 最后取整」：先取整让每一步都能被客户独立重算（年价 ÷ 365 四舍五入到分，乘以剩余天数）。
 * 由于 365 是奇数，整数分的「年价 ÷ 365」不可能恰好落在 .5 上，half-up 与任何四舍五入
 * 实现都必然一致，没有平台相关的争议空间。
 *
 * <p><b>为什么总额不再二次取整。</b>{@code 日单价 × 剩余整日} 已是整数分乘积，P5 里的
 * {@code round(...)} 因此是恒等操作；这里刻意不做多余的舍入，也不引入浮点。
 *
 * <p><b>为什么 {@code effectiveAt} 是报价时刻而不是支付时刻。</b>金额在下单时冻结，支付成功
 * 事件只负责生效；若生效时用新的 {@code now} 重算，客户看到的价与订阅实际起算点会分叉。
 * 模拟渠道下支付紧随报价，真实渠道（S14-5）必须在回调时比对报价是否过期，而不是静默重算。
 *
 * @param effectiveAt 折算基准时刻（升级报价时刻，UTC）
 * @param periodEndsAt 原服务期终点，升级后必须保持不变
 * @param remainingDays 剩余整日，恒 {@code >= 1}
 * @param oldDailyPriceCents 来源档日单价（人民币分）
 * @param newDailyPriceCents 目标档日单价（人民币分）
 * @param creditCents 已用服务期抵扣额（人民币分）
 * @param chargeCents 剩余服务期应收额（人民币分）
 * @param differenceCents 应补差额 {@code max(0, charge - credit)}（人民币分）
 */
public record SubscriptionUpgradeProration(
        Instant effectiveAt,
        Instant periodEndsAt,
        int remainingDays,
        long oldDailyPriceCents,
        long newDailyPriceCents,
        long creditCents,
        long chargeCents,
        long differenceCents) {

    /** P5 冻结的日单价基数：一年一律按 365 天折算，不区分平闰年。 */
    public static final int DAYS_PER_YEAR = 365;

    /** 一天的秒数，用于 {@code ceil} 整日折算。 */
    private static final long SECONDS_PER_DAY = 86_400L;

    /**
     * 折算快照必须自洽：剩余整日为正、服务期终点晚于基准时刻、金额非负且满足 P5 的算式。
     *
     * <p>校验放在构造器里，是为了让「数据库读回的快照」与「当场算出的快照」走同一条
     * 不变式通道：任何被静默改写的一列都会在重建对象时立刻失败，而不是等到对账。
     */
    public SubscriptionUpgradeProration {
        Objects.requireNonNull(effectiveAt, "折算基准时刻不得为空");
        Objects.requireNonNull(periodEndsAt, "原服务期终点不得为空");
        if (remainingDays < 1) {
            throw new IllegalArgumentException("升级剩余整日必须为正，实际为: " + remainingDays);
        }
        if (!periodEndsAt.isAfter(effectiveAt)) {
            throw new IllegalArgumentException("原服务期终点必须晚于折算基准时刻");
        }
        if (oldDailyPriceCents < 0 || newDailyPriceCents < 0 || creditCents < 0
                || chargeCents < 0 || differenceCents < 0) {
            throw new IllegalArgumentException("升级折算金额不得为负数");
        }
        if (creditCents != multiplyExact(oldDailyPriceCents, remainingDays)
                || chargeCents != multiplyExact(newDailyPriceCents, remainingDays)) {
            throw new IllegalArgumentException("升级抵扣/应收必须等于日单价乘以剩余整日");
        }
        if (differenceCents != Math.max(0L, chargeCents - creditCents)) {
            throw new IllegalArgumentException("升级补差必须等于 max(应收 - 抵扣, 0)");
        }
    }

    /**
     * 按 P5 冻结口径计算一次升级折算。
     *
     * @param quotedAt 升级报价时刻（UTC）
     * @param periodEndsAt 当前生效订阅的原服务期终点
     * @param oldAnnualPriceCents 来源档参考年价（人民币分）
     * @param newAnnualPriceCents 目标档参考年价（人民币分）
     * @return 折算快照
     * @throws IllegalArgumentException 服务期已结束、年价为负或金额溢出
     */
    public static SubscriptionUpgradeProration between(Instant quotedAt, Instant periodEndsAt,
                                                      long oldAnnualPriceCents, long newAnnualPriceCents) {
        Objects.requireNonNull(quotedAt, "升级报价时刻不得为空");
        Objects.requireNonNull(periodEndsAt, "原服务期终点不得为空");
        int remainingDays = remainingDays(quotedAt, periodEndsAt);
        long oldDaily = dailyPriceCents(oldAnnualPriceCents);
        long newDaily = dailyPriceCents(newAnnualPriceCents);
        long credit = multiplyExact(oldDaily, remainingDays);
        long charge = multiplyExact(newDaily, remainingDays);
        return new SubscriptionUpgradeProration(quotedAt, periodEndsAt, remainingDays,
                oldDaily, newDaily, credit, charge, Math.max(0L, charge - credit));
    }

    /**
     * 年价折算日单价：{@code round-half-up(年价 / 365)}，人民币分整数。
     *
     * <p>用整数运算实现 half-up：{@code floor((2a + 365) / 730)}。因为 365 是奇数，
     * 整数年价除以 365 的商不可能恰好是 {@code .5}，所以这个式子与「四舍五入」逐值等价，
     * 且没有任何浮点误差。
     *
     * @param annualPriceCents 年价（人民币分）
     * @return 日单价（人民币分）
     * @throws IllegalArgumentException 年价为负
     */
    public static long dailyPriceCents(long annualPriceCents) {
        if (annualPriceCents < 0) {
            throw new IllegalArgumentException("年价不得为负数: " + annualPriceCents);
        }
        return Math.floorDiv(multiplyExact(annualPriceCents, 2L) + DAYS_PER_YEAR,
                multiplyExact(DAYS_PER_YEAR, 2L));
    }

    /**
     * 剩余整日 {@code = ceil((periodEndsAt - quotedAt) / 1 day)}。
     *
     * @param quotedAt 折算基准时刻
     * @param periodEndsAt 服务期终点
     * @return 剩余整日，恒 {@code >= 1}
     * @throws IllegalArgumentException 服务期终点不晚于基准时刻
     */
    public static int remainingDays(Instant quotedAt, Instant periodEndsAt) {
        Objects.requireNonNull(quotedAt, "折算基准时刻不得为空");
        Objects.requireNonNull(periodEndsAt, "服务期终点不得为空");
        if (!periodEndsAt.isAfter(quotedAt)) {
            throw new IllegalArgumentException("服务期已结束，无法按整日折算升级");
        }
        Duration remaining = Duration.between(quotedAt, periodEndsAt);
        long seconds = remaining.getSeconds();
        long extraSecond = remaining.getNano() > 0 ? 1L : 0L;
        long days = (seconds + extraSecond + SECONDS_PER_DAY - 1) / SECONDS_PER_DAY;
        // 服务期以 Instant 表达，天数不可能超过 int 范围；超出即数据异常，显式失败而不是截断。
        return Math.toIntExact(days);
    }

    /**
     * 整数乘法，溢出即失败而不是回绕。
     *
     * @param left 被乘数
     * @param right 乘数
     * @return 乘积
     */
    private static long multiplyExact(long left, long right) {
        return Math.multiplyExact(left, right);
    }
}
