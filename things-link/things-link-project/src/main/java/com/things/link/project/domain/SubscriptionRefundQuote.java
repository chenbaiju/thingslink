package com.things.link.project.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * ADR0166 持久模拟报价；本身不表示已退钱或已取消。
 * @param operationId 操作身份 @param tenantId 租户 @param currentSubscriptionId 当前付费订阅
 * @param assignmentVersion 报价时绑定版本 @param quotedAt 数据库实际时刻 @param expiresAt 最晚执行时刻
 * @param reason 原因 @param algorithm 冻结算法 @param totalCents 整组分数 @param stateSnapshot 待复验原始JSON
 * @param lines 不可变逐单结果
 */
public record SubscriptionRefundQuote(UUID operationId,UUID tenantId,UUID currentSubscriptionId,long assignmentVersion,
        Instant quotedAt,Instant expiresAt,String reason,String algorithm,long totalCents,String stateSnapshot,List<Line> lines) {
    /** 本版唯一算法，后续版本必须独立兼容原报价。 */
    public static final String ALGORITHM="UNUSED_CEIL_DAYS_V1";
    /** 身份、期限、金额与明细保持自洽。 */
    public SubscriptionRefundQuote {
        Objects.requireNonNull(operationId); Objects.requireNonNull(tenantId); Objects.requireNonNull(currentSubscriptionId);
        Objects.requireNonNull(quotedAt); Objects.requireNonNull(expiresAt); Objects.requireNonNull(reason);
        Objects.requireNonNull(algorithm); Objects.requireNonNull(stateSnapshot); lines=List.copyOf(lines);
        if (assignmentVersion<=0 || totalCents<0 || lines.isEmpty() || !expiresAt.isAfter(quotedAt)
                || expiresAt.isAfter(quotedAt.plusSeconds(300)) || reason.isBlank() || reason.length()>500) {
            throw new IllegalArgumentException("模拟订阅报价无效");
        }
        long sum=0;
        for (Line line:lines) sum=Math.addExact(sum,line.refundableCents());
        if (sum!=totalCents || lines.stream().map(Line::orderId).distinct().count()!=lines.size()) {
            throw new IllegalArgumentException("模拟报价金额或订单身份不一致");
        }
    }

    /**
     * @param subscriptionId 原订阅 @param orderId 原订单 @param kind 首购续费或升级
     * @param startsAt 原始起点 @param endsAt 原始终点 @param subscriptionRevision 当前订阅版本
     * @param orderRevision 当前订单版本 @param amountCents 原金额 @param refundedCents 原已退累计
     * @param totalDays 总整日 @param unusedDays 未使用整日 @param refundableCents 本笔分数
     */
    public record Line(UUID subscriptionId,UUID orderId,TenantOrderKind kind,Instant startsAt,Instant endsAt,
            long subscriptionRevision,long orderRevision,long amountCents,long refundedCents,
            long totalDays,long unusedDays,long refundableCents) {
        /** 不接受包订单、重复退款余额或无效区间。 */
        public Line {
            Objects.requireNonNull(subscriptionId); Objects.requireNonNull(orderId); Objects.requireNonNull(kind);
            Objects.requireNonNull(startsAt); Objects.requireNonNull(endsAt);
            if ((kind!=TenantOrderKind.PURCHASE && kind!=TenantOrderKind.UPGRADE) || !endsAt.isAfter(startsAt)
                    || subscriptionRevision<=0 || orderRevision<=0 || amountCents<0 || refundedCents<0
                    || refundedCents>amountCents || refundableCents>amountCents-refundedCents) {
                throw new IllegalArgumentException("模拟报价订单明细无效");
            }
            new SubscriptionRefundProration(totalDays,unusedDays,refundableCents);
        }
    }
}
