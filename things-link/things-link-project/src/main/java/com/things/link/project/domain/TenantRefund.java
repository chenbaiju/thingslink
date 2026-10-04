package com.things.link.project.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一行租户退款事实（S14-5a）：与订单并列的资金事实，不是订单的另一个状态。
 *
 * <p>三件事必须齐备才能叫一笔可对账的退款：退了多少（{@code amountCents}/{@code currency}）、
 * 退的哪一笔（{@code orderId}）、渠道凭什么幂等（{@code provider}/{@code providerRefundId}）。
 * {@code reason} 是运营/客户原因的留存，没有原因的退款无法对账与追责。
 *
 * <p>ADR0165允许资源包分次结算，每笔不超过剩余可退金额；首次成功退款收回整包，
 * 后续补退不再改变权益。订阅订单退款仍归D-173后续合同。
 *
 * @param id 退款行 ID
 * @param tenantId 退款归属租户
 * @param orderId 被退款的订单 ID
 * @param amountCents 退款金额（人民币分），恒为正
 * @param currency ISO 4217 币种快照
 * @param provider 退款渠道
 * @param providerRefundId 渠道退款流水号（幂等键）
 * @param status 退款状态
 * @param reason 退款原因
 * @param createdAt 退款事实创建时刻（UTC）
 */
public record TenantRefund(
        UUID id,
        UUID tenantId,
        UUID orderId,
        long amountCents,
        String currency,
        PaymentProvider provider,
        String providerRefundId,
        RefundStatus status,
        String reason,
        Instant createdAt) {

    /** 退款事实必须完整且自洽：退款不可能是零或负数，原因不得空白。 */
    public TenantRefund {
        Objects.requireNonNull(id, "退款 ID 不得为空");
        Objects.requireNonNull(tenantId, "退款租户 ID 不得为空");
        Objects.requireNonNull(orderId, "退款订单 ID 不得为空");
        Objects.requireNonNull(currency, "退款币种不得为空");
        Objects.requireNonNull(provider, "退款渠道不得为空");
        Objects.requireNonNull(providerRefundId, "渠道退款流水号不得为空");
        Objects.requireNonNull(status, "退款状态不得为空");
        Objects.requireNonNull(createdAt, "退款创建时刻不得为空");
        if (amountCents <= 0) {
            throw new IllegalArgumentException("退款金额必须为正数");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("退款原因不得为空或空白");
        }
    }
}
