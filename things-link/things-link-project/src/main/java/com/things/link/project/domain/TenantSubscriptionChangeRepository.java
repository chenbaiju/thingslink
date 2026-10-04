package com.things.link.project.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 预约降级事实的持久化端口（S14-3b）。
 *
 * <p>写入口只有「建一条 PENDING 预约」与「把 PENDING 置为 CANCELLED」两个动作，没有 DELETE、
 * 也没有「改目标」：客户改主意先撤销再预约，两步都留行留审计。套用（PENDING → APPLIED）
 * 属 S14-3c，本端口不预先长出那半截语义。
 *
 * <p>{@link #lockTenant(UUID)} 与订阅生效事务取同一把租户行锁（{@code sys_tenant}），
 * 让「读当前 ACTIVE → 写预约」与「支付成功 → 关旧开新」互相串行：否则升级生效与降级预约
 * 可能同时读到旧订阅，留下一条指向已被取代订阅的预约。唯一 PENDING 由
 * {@code sys_tenant_subscription_pending_change_pending_uk} 兜底。
 */
public interface TenantSubscriptionChangeRepository {

    /**
     * 锁定租户行，作为预约与撤销的串行化锚点。
     *
     * @param tenantId 租户 ID
     * @return 租户存在时为 {@code true}；不存在时为 {@code false}
     */
    boolean lockTenant(UUID tenantId);

    /**
     * 按 ID 读取一条预约（不加锁）。
     *
     * @param changeId 预约 ID
     * @return 预约事实；不存在时为空
     */
    Optional<SubscriptionPendingChange> findById(UUID changeId);

    /**
     * 读取租户当前唯一的 PENDING 预约。
     *
     * @param tenantId 租户 ID
     * @return 待生效预约；没有时为空
     */
    Optional<SubscriptionPendingChange> findPending(UUID tenantId);

    /**
     * 写入一条 PENDING 预约。
     *
     * @param tenantId 归属租户
     * @param subscriptionId 预约时的 ACTIVE 订阅
     * @param fromPlanRevisionId 来源修订版快照
     * @param targetPlanRevisionId 目标修订版
     * @param effectiveAt 生效时刻（= 预约时当前服务期终点）
     * @return 新预约 ID
     */
    UUID insertPending(UUID tenantId, UUID subscriptionId, UUID fromPlanRevisionId,
                       UUID targetPlanRevisionId, Instant effectiveAt);

    /**
     * 把仍处于 PENDING 的预约置为 CANCELLED 并写入撤销时刻。
     *
     * <p>{@code WHERE status = 'PENDING'} 让本语句自身即幂等仲裁：重复撤销更新不到行。
     *
     * @param changeId 预约 ID
     * @return 本次确实撤销了预约时为 {@code true}
     */
    boolean cancelPending(UUID changeId);

    /**
     * 扫描已到生效时刻且仍 PENDING 的预约（S14-3c 消费面）。
     *
     * <p>本方法只负责「捞出来」；是否真的套用由 S14-3c 在套用前复验订阅仍为 ACTIVE 决定
     * （S14-3b 的注释已把该复验写成硬约束：续费不清除预约，预约行指向的订阅可能已不是当前 ACTIVE）。
     *
     * @param now 当前时刻（UTC）
     * @param limit 单轮上限
     * @return 到期预约，按生效时刻正序
     */
    java.util.List<SubscriptionPendingChange> findDuePending(Instant now, int limit);

    /**
     * 把仍处于 PENDING 的预约置为 APPLIED 并写入套用时刻。
     *
     * <p>{@code WHERE status = 'PENDING'} 使本语句自身即幂等仲裁：重复套用更新不到行。
     *
     * @param changeId 预约 ID
     * @param appliedAt 套用时刻（UTC）
     * @return 本次确实套用了预约时为 {@code true}
     */
    boolean markApplied(UUID changeId, Instant appliedAt);
}
