package com.things.link.project.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一条租户订单事实（S14-3a，S14-3b 扩充升级折算快照，S14-4a 扩充资源包购买）。
 *
 * <p>订单锁定的金额/币种都是下单时的快照：调价必须新建产品修订版，历史订单不得原地重解释。
 * {@code providerEventId} 只在 {@link TenantOrderStatus#PAID} 时有值，它就是「同一支付事件只
 * 生效一次」的幂等键。
 *
 * <p>{@code kind} 区分三条生效路径：{@link TenantOrderKind#PURCHASE} 走 S14-3a 的
 * 「首购 now → now + 周期 / 续费从上一到期日延长」；{@link TenantOrderKind#UPGRADE} 走
 * S14-3b 的「报价时刻起、原到期日止，金额为按整日折算的补差」；{@link TenantOrderKind#PACKAGE}
 * 走 S14-4a 的「写入一条独立有效期的资源包」。
 *
 * <p>三条路径携带的快照互斥且同生共死：升级订单有 {@code sourcePlanRevisionId} +
 * {@code proration}，资源包订单有 {@code packagePurchase} 且 {@code planRevisionId} 为空。
 * 生效事务只许读这份快照，不许在支付时用新的 {@code now} 重算。
 *
 * @param id 订单行 ID
 * @param tenantId 订单归属租户
 * @param planRevisionId 下单时锁定的产品修订版（升级时为目标修订版）；资源包订单为 {@code null}
 * @param kind 订单种类：整份购买/续费、升级补差或资源包购买
 * @param sourcePlanRevisionId 升级前的来源修订版；非升级订单为 {@code null}
 * @param proration 升级折算快照；非升级订单为 {@code null}
 * @param packagePurchase 资源包购买快照；非资源包订单为 {@code null}
 * @param provider 支付渠道
 * @param status 订单状态
 * @param providerEventId 渠道支付事件 ID；未支付时为 {@code null}
 * @param amountCents 成交金额快照（人民币分）：购买取参考价、升级取折算补差、资源包取单位参考价基准
 * @param currency ISO 4217 币种快照
 * @param createdAt 下单时刻
 * @param paidAt 支付成功时刻；未支付时为 {@code null}
 * @param refundedCents 已成功退款的累计金额（S14-5a，人民币分）；恒在 {@code [0, amountCents]} 内
 * @param revision 订单行乐观锁版本
 */
public record TenantOrder(
        UUID id,
        UUID tenantId,
        UUID planRevisionId,
        TenantOrderKind kind,
        UUID sourcePlanRevisionId,
        SubscriptionUpgradeProration proration,
        ResourcePackagePurchase packagePurchase,
        PaymentProvider provider,
        TenantOrderStatus status,
        String providerEventId,
        long amountCents,
        String currency,
        Instant createdAt,
        Instant paidAt,
        long refundedCents,
        long revision) {

    /**
     * 订单事实必须完整且自洽：ID、归属、种类、渠道与状态缺一不可；三条路径的快照要么都在、
     * 要么都不在；升级订单金额必须等于折算补差。
     */
    public TenantOrder {
        Objects.requireNonNull(id, "订单 ID 不得为空");
        Objects.requireNonNull(tenantId, "订单租户 ID 不得为空");
        Objects.requireNonNull(kind, "订单种类不得为空");
        Objects.requireNonNull(provider, "订单支付渠道不得为空");
        Objects.requireNonNull(status, "订单状态不得为空");
        Objects.requireNonNull(currency, "订单币种不得为空");
        Objects.requireNonNull(createdAt, "订单创建时刻不得为空");
        boolean upgrade = kind == TenantOrderKind.UPGRADE;
        if (upgrade != (sourcePlanRevisionId != null) || upgrade != (proration != null)) {
            throw new IllegalArgumentException("升级订单必须同时携带来源修订版与折算快照，其他订单不得携带");
        }
        boolean materialPackage = kind == TenantOrderKind.PACKAGE;
        if (materialPackage != (packagePurchase != null)) {
            throw new IllegalArgumentException("资源包订单必须携带包购买快照，其他订单不得携带");
        }
        if (materialPackage != (planRevisionId == null)) {
            throw new IllegalArgumentException("资源包订单不得绑定产品修订版，其他订单必须绑定修订版");
        }
        if (amountCents < 0) {
            throw new IllegalArgumentException("订单金额不得为负数");
        }
        if (refundedCents < 0 || refundedCents > amountCents) {
            throw new IllegalArgumentException("累计退款金额必须落在 [0, 订单金额] 内");
        }
        if (upgrade && amountCents != proration.differenceCents()) {
            throw new IllegalArgumentException("升级订单金额必须等于折算补差");
        }
    }
}
