package com.things.link.project.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * ADR0166 不可变模拟结算结果，不代表真实渠道资金到账。
 * @param operationId 保存报价的操作 @param tenantId 租户 @param subscriptionId 被终止订阅
 * @param freeSubscriptionId 回落FREE身份 @param executedAt 实际终止时刻 @param totalCents 整组模拟退款分数
 * @param settlements 原始逐单结果，包含零金额
 */
public record SubscriptionRefundExecution(UUID operationId,UUID tenantId,UUID subscriptionId,UUID freeSubscriptionId,
        Instant executedAt,long totalCents,List<Settlement> settlements) {
    /** 冻结结果不允许重复订单或金额不一致。 */
    public SubscriptionRefundExecution {
        Objects.requireNonNull(operationId); Objects.requireNonNull(tenantId); Objects.requireNonNull(subscriptionId);
        Objects.requireNonNull(freeSubscriptionId); Objects.requireNonNull(executedAt); settlements=List.copyOf(settlements);
        long sum=0;
        for(var line:settlements) sum=Math.addExact(sum,line.amountCents());
        if (settlements.isEmpty() || totalCents<0 || totalCents!=sum
                || settlements.stream().map(Settlement::orderId).distinct().count()!=settlements.size()) {
            throw new IllegalArgumentException("模拟结算结果不一致");
        }
    }

    /** @param orderId 原订单 @param subscriptionId 原来源订阅 @param amountCents 模拟退回分数 @param refundId 正金额退款身份，零为空 */
    public record Settlement(UUID orderId,UUID subscriptionId,long amountCents,UUID refundId) {
        /** 零金额不能冒充一笔退款。 */
        public Settlement {
            Objects.requireNonNull(orderId); Objects.requireNonNull(subscriptionId);
            if (amountCents<0 || (amountCents==0)!=(refundId==null)) throw new IllegalArgumentException("模拟结算明细无效");
        }
    }
}
