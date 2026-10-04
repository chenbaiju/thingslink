package com.things.link.project.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 支付失败事实的持久化端口（S14-5b）。
 *
 * <p>写入口只有两个动作：写一条失败事实、按渠道失败事件 ID 读取既有事实（幂等重放）。
 * 没有任何 UPDATE/DELETE 与状态推进：失败不改变订单，也不产生权益——这是本片与退款/支付成功
 * 路径最大的不同，端口因此只有「记下来」与「读回来」两个能力。
 *
 * <p>失败表与订单/退款/包一样不套租户 RLS：服务以显式 {@code tenantId} 为参数，
 * 授权留在应用入口（见 D-172）。
 */
public interface OrderPaymentFailureRepository {

    /**
     * 写入一条支付失败事实。
     *
     * @param tenantId 失败归属租户
     * @param orderId 失败订单 ID
     * @param provider 支付渠道
     * @param providerEventId 渠道失败事件 ID（幂等键）
     * @param failureCode 渠道失败分类
     * @param amountCents 失败时刻订单金额快照（人民币分）
     * @param currency ISO 4217 币种快照
     * @param occurredAt 渠道报告的发生时刻（UTC）
     * @return 新失败行 ID
     */
    UUID insert(UUID tenantId, UUID orderId, PaymentProvider provider, String providerEventId,
                String failureCode, long amountCents, String currency, java.time.Instant occurredAt);

    /**
     * 按渠道与渠道失败事件 ID 读取失败事实（重放时返回既有事实）。
     *
     * @param provider 支付渠道
     * @param providerEventId 渠道失败事件 ID
     * @return 该事件对应的失败事实；未发生过时为空
     */
    Optional<OrderPaymentFailure> findByProviderEventId(PaymentProvider provider, String providerEventId);
}
