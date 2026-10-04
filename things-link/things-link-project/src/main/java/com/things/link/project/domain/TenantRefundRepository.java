package com.things.link.project.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 租户退款事实的持久化端口（S14-5a）。
 *
 * <p>写入口只有两个动作：建一条退款、按渠道退款流水号读取既有退款（幂等重放）。没有任何 DELETE 与
 * UPDATE：退款是资金历史，错了只能再退一笔或走渠道对账，不能改写已发生的事实。
 *
 * <p>金额上限不在这里判定：它由订单行上的 {@code refunded_cents} 与
 * {@link TenantOrderRepository#reserveRefund(UUID, long)} 的 CAS 承担——退款事务先占住金额，
 * 再写退款行，因此并发双退只有一个能成功。
 *
 * <p>退款表与订单/订阅/包一样不套租户 RLS：服务以显式 {@code tenantId} 为参数，
 * 授权留在应用入口（见 D-172/D-173）。
 */
public interface TenantRefundRepository {

    /**
     * 写入一条退款事实。
     *
     * @param tenantId 退款归属租户
     * @param orderId 被退款的订单 ID
     * @param amountCents 退款金额（人民币分）
     * @param currency ISO 4217 币种快照
     * @param provider 退款渠道
     * @param providerRefundId 渠道退款流水号（幂等键）
     * @param status 退款状态
     * @param reason 退款原因
     * @return 新退款行 ID
     */
    UUID insert(UUID tenantId, UUID orderId, long amountCents, String currency,
                PaymentProvider provider, String providerRefundId, RefundStatus status, String reason);

    /**
     * 按渠道与渠道退款流水号读取退款（重放时返回既有事实）。
     *
     * @param provider 退款渠道
     * @param providerRefundId 渠道退款流水号
     * @return 该流水号对应的退款；未发生过时为空
     */
    Optional<TenantRefund> findByProviderRefundId(PaymentProvider provider, String providerRefundId);

    /**
     * 按订单读取成功退款的累计金额（对账不变量与真库用例使用）。
     *
     * @param orderId 订单 ID
     * @return 该订单 {@code SUCCEEDED} 退款的金额合计；没有时返回 0
     */
    long sumSucceededByOrder(UUID orderId);
}
