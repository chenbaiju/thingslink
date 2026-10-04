package com.things.link.project.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 租户订单事实的持久化端口（S14-3a）。
 *
 * <p>写入口只有「建一条 CREATED 订单」「标记支付成功」「标记取消」三个动作，没有任何 DELETE：
 * 订单是历史事实，取消与支付都靠状态推进而不是删行。阅读入口额外提供「下单所需的修订版购买投影」，
 * 让订单服务在同一事务里一次读齐金额、币种与配额模板，不散落第二份目录查询。
 *
 * <p>{@link #lockOrder(UUID)} 用行锁把「同一订单的并发重复支付」串行化：先到者写 PAID 并生效，
 * 后到者读到 PAID 且事件相同即幂等重放。跨订单的同租户并发由
 * {@link TenantSubscriptionLifecycleRepository#lockTenantAndReadAssignmentVersion(UUID)} 的租户行锁串行化。
 */
public interface TenantOrderRepository {

    /**
     * 判断租户是否存在（下单前的显式校验，避免把外键冲突当业务错误抛出）。
     *
     * @param tenantId 租户 ID
     * @return 存在时为 {@code true}
     */
    boolean tenantExists(UUID tenantId);

    /**
     * 读取指定产品修订版的购买投影（含参考价、档位展示顺序与配额模板 ID）。
     *
     * @param planRevisionId 产品修订版 ID
     * @return 修订版存在时的购买投影；不存在时为空
     */
    Optional<PlanPurchase> findPlanPurchase(UUID planRevisionId);

    /**
     * 按 ID 读取订单（不加锁，用于写入后的回读与只读展示）。
     *
     * @param orderId 订单 ID
     * @return 订单事实；不存在时为空
     */
    Optional<TenantOrder> findOrder(UUID orderId);

    /**
     * 按 ID 锁定并读取订单（{@code FOR UPDATE}），串行化同一订单的并发支付。
     *
     * @param orderId 订单 ID
     * @return 订单事实；不存在时为空
     */
    Optional<TenantOrder> lockOrder(UUID orderId);

    /**
     * 按幂等键读取已消费该支付事件的订单。
     *
     * @param provider 支付渠道
     * @param providerEventId 渠道支付事件 ID
     * @return 已消费该事件的订单；没有时为空
     */
    Optional<TenantOrder> findByProviderEventId(PaymentProvider provider, String providerEventId);

    /**
     * 写入一条 CREATED 模拟订单，金额与币种为下单时快照。
     *
     * @param tenantId 归属租户
     * @param planRevisionId 锁定的产品修订版
     * @param provider 支付渠道
     * @param amountCents 模拟金额（人民币分），取修订版参考价
     * @param currency ISO 4217 币种
     * @return 新订单 ID
     */
    UUID insertOrder(UUID tenantId, UUID planRevisionId, PaymentProvider provider,
                     long amountCents, String currency);

    /**
     * 写入一条 CREATED 升级订单（S14-3b）：金额与折算快照一次落库，支付时只许原样读回。
     *
     * @param tenantId 归属租户
     * @param planRevisionId 目标产品修订版
     * @param sourcePlanRevisionId 升级前的来源修订版
     * @param proration 按整日折算的快照；金额取 {@code proration.differenceCents()}
     * @param provider 支付渠道
     * @param currency ISO 4217 币种
     * @return 新订单 ID
     */
    UUID insertUpgradeOrder(UUID tenantId, UUID planRevisionId, UUID sourcePlanRevisionId,
                            SubscriptionUpgradeProration proration, PaymentProvider provider,
                            String currency);

    /**
     * 写入一条 CREATED 资源包订单（S14-4a）：包维度、额度、单位/窗口、周期基数与起算时刻一次落库。
     *
     * <p>金额取包维度的每单位参考价基准（模拟），不取任何套餐修订版的参考价；生效时只许原样读回快照。
     *
     * @param tenantId 归属租户
     * @param purchase 资源包购买快照
     * @param provider 支付渠道
     * @param amountCents 模拟金额（人民币分）
     * @param currency ISO 4217 币种
     * @return 新订单 ID
     */
    UUID insertPackageOrder(UUID tenantId, ResourcePackagePurchase purchase, PaymentProvider provider,
                            long amountCents, String currency);

    /**
     * 把仍处于 CREATED 的订单标记为 PAID，并写入支付事件与支付时刻。
     *
     * <p>{@code WHERE status = 'CREATED'} 让本语句自身即幂等仲裁：并发下只有一个调用能更新到行，
     * 另一个返回空。渠道级唯一索引 {@code sys_tenant_order_provider_event_uk} 是更外层的兜底。
     *
     * @param orderId 订单 ID
     * @param provider 支付渠道
     * @param providerEventId 渠道支付事件 ID
     * @return 更新成功时的支付时刻；订单已不在 CREATED 时为空
     */
    Optional<Instant> markPaid(UUID orderId, PaymentProvider provider, String providerEventId);

    /**
     * 把仍处于 CREATED 的订单标记为 CANCELLED。
     *
     * @param orderId 订单 ID
     * @return 本次确实取消了订单时为 {@code true}
     */
    boolean cancelOrder(UUID orderId);

    /**
     * 以 CAS 占住一笔订单的**全额**退款金额（S14-5a）。
     *
     * <p>{@code UPDATE ... WHERE status = 'PAID' AND refunded_cents = 0 AND amount_cents = ?}
     * 自身就是并发仲裁：两个并发全额退款只有一个能更新到行，另一个拿不到金额、必须被拒绝，
     * 因此「一笔订单最多全额退一次」不依赖应用层先读后写。金额与订单金额必须相等是本片的
     * 全额退款口径（部分退款需产品裁决，见 D-173）。
     *
     * @param orderId 订单 ID
     * @param amountCents 退款金额（人民币分），必须等于订单金额
     * @return 本次确实占住退款金额时为 {@code true}
     */
    boolean reserveFullRefund(UUID orderId, long amountCents);
    /** ADR0165：以剩余可退金额为上界累加正整数分，SQL自身防超退及溢出。 */
    boolean reserveRefund(UUID orderId, long amountCents);

}
