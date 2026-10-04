package com.things.link.project.domain;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * ADR0166 的模拟订阅未使用整日比例计算，不判断退款资格，也不变更订阅。
 *
 * <p>输入必须来自已核验的原始订单和服务期。升级传入实际补差，不能传入抵扣或目录现价。
 * 使用任意精度中间值，避免合法 long 金额乘天数时溢出；人民币分最后向下取整。
 *
 * @param totalDays 原始服务期向上取整天数
 * @param unusedDays 未使用区间向上取整天数
 * @param refundableCents 不超过订单剩余金额的模拟退款分数
 */
public record SubscriptionRefundProration(long totalDays, long unusedDays, long refundableCents) {

    /** 一整日的秒数；UTC 时长不受夏令时影响。 */
    private static final long SECONDS_PER_DAY = 86_400L;

    /** 校验结果不可表达负数、空服务期或超出原区间的剩余天数。 */
    public SubscriptionRefundProration {
        if (totalDays <= 0 || unusedDays < 0 || unusedDays > totalDays || refundableCents < 0
                || (unusedDays == 0 && refundableCents != 0)) {
            throw new IllegalArgumentException("订阅退款整日比例结果无效");
        }
    }

    /**
     * 按原始区间和数据库报价时刻计算剩余整日比例。
     *
     * @param startsAt 原始服务期起点或升级冻结生效点
     * @param endsAt 原始服务期终点
     * @param quotedAt 数据库 UTC 报价时刻
     * @param paidCents 原订单实际成交金额
     * @param refundedCents 原订单已退款累计
     * @return 仅供后续来源核验和事务执行使用的金额结果
     * @throws IllegalArgumentException 非正服务区间或非法金额累计
     */
    public static SubscriptionRefundProration between(Instant startsAt, Instant endsAt, Instant quotedAt,
                                                       long paidCents, long refundedCents) {
        Objects.requireNonNull(startsAt, "原始起点不得为空");
        Objects.requireNonNull(endsAt, "原始终点不得为空");
        Objects.requireNonNull(quotedAt, "报价时刻不得为空");
        if (!endsAt.isAfter(startsAt) || paidCents < 0 || refundedCents < 0 || refundedCents > paidCents) {
            throw new IllegalArgumentException("原始服务区间或订单退款累计无效");
        }
        long total = ceilDays(Duration.between(startsAt, endsAt));
        long unused = !quotedAt.isBefore(endsAt) ? 0
                : !quotedAt.isAfter(startsAt) ? total : ceilDays(Duration.between(quotedAt, endsAt));
        long proportional = BigInteger.valueOf(paidCents).multiply(BigInteger.valueOf(unused))
                .divide(BigInteger.valueOf(total)).longValueExact();
        return new SubscriptionRefundProration(total, unused, Math.min(proportional, paidCents - refundedCents));
    }

    /** 正时长向上取整；先除后加，避免秒数加一天溢出，纳秒余数同样计入。 */
    private static long ceilDays(Duration duration) {
        long seconds = duration.getSeconds();
        return seconds / SECONDS_PER_DAY
                + (seconds % SECONDS_PER_DAY != 0 || duration.getNano() != 0 ? 1 : 0);
    }
}
