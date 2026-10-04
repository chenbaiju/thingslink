package com.things.link.project.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 订阅生命周期写入口（S14-3a）。
 *
 * <p>{@link TenantSubscriptionRepository} 的注释已经把边界写死：S14-2a 的端口只有「读 FREE 快照」
 * 与「幂等建一条 ACTIVE 免费订阅」，订阅状态机必须新增显式端口，而不是让那个端口长出 UPDATE 语义。
 * 本接口就是那条新增端口 —— 关闭旧的 ACTIVE、建新的 ACTIVE、读回当前生效订阅，都只在这里。
 *
 * <h2>唯一 ACTIVE 不变式怎么守</h2>
 * 数据库侧是 {@code sys_tenant_subscription_active_tenant_uk} 部分唯一索引（每个租户至多一条 ACTIVE），
 * 它是最后兜底；应用侧先用租户行锁把「关旧 + 建新」串行化，让两个并发支付不会同时读到同一条
 * 「旧 ACTIVE」。先 {@link #supersede(UUID)} 再 {@link #activate} 的顺序保证同一事务内的两条语句
 * 之间不存在两条 ACTIVE 行，因此索引在事务提交时看到的一定是合法状态。
 */
public interface TenantSubscriptionLifecycleRepository {

    /**
     * 锁定租户行并返回当前有效策略绑定版本，作为订阅生效事务的串行化锚点。
     *
     * <p>读出的版本会原样传给
     * {@link com.things.link.project.application.QuotaPolicyAssignmentService#assign(UUID, UUID, long)}
     * 的 CAS：锁持有期间没有其他事务能推进它，因此 CAS 必然成功，且每次真实生效恰好推进一次。
     *
     * @param tenantId 租户 ID
     * @return 当前 {@code quota_policy_assignment_version}
     * @throws IllegalStateException 租户不存在（订单外键已保证，正常不会发生）
     */
    long lockTenantAndReadAssignmentVersion(UUID tenantId);

    /**
     * 读取租户当前绑定的配额策略 ID，供「不换策略但要失效缓存」的写入复用
     * {@link com.things.link.project.application.QuotaPolicyAssignmentService#assign}。
     *
     * <p>调用方必须已在本事务持有该租户行锁（{@link #lockTenantAndReadAssignmentVersion}），
     * 否则读到的绑定可能已被并发切换。
     *
     * @param tenantId 租户 ID
     * @return 当前 {@code sys_tenant.quota_policy_id}
     * @throws org.springframework.dao.EmptyResultDataAccessException 租户不存在
     */
    UUID readPolicyId(UUID tenantId);

    /**
     * 锁定并读取当前 ACTIVE 基础订阅。
     *
     * @param tenantId 租户 ID
     * @return 当前生效订阅；没有时为空（仅存量直插租户可能出现）
     */
    Optional<ActiveSubscription> lockActiveSubscription(UUID tenantId);

    /**
     * 按来源订单读取该订单生效出来的订阅（幂等重放时返回既有事实）。
     *
     * @param orderId 来源订单 ID
     * @return 该订单创建的订阅；订单未生效时为空
     */
    Optional<ActiveSubscription> findBySourceOrder(UUID orderId);

    /**
     * 把当前 ACTIVE 订阅置为终态 {@code SUPERSEDED}。
     *
     * <p>选 {@code SUPERSEDED} 而不是删除或复用 {@code CANCELLED}：被升级/续费取代不是用户取消，
     * 也不是到期，必须留下「它曾被谁取代」的历史行；迁移的 CHECK 已包含该取值。
     *
     * @param subscriptionId 待关闭的订阅 ID
     */
    void supersede(UUID subscriptionId);

    /**
     * 写入一条新的 ACTIVE 基础订阅，来源订单与成交价目快照一并落库。
     *
     * <p>续费方式固定写 {@code MANUAL}：支付功能尚未开发，系统没有自动扣款授权，
     * 不能写 {@code AUTO} 去暗示一个不存在的自动续费承诺。
     *
     * @param tenantId 归属租户
     * @param planRevisionId 生效锁定的产品修订版
     * @param startsAt 服务期起始时刻
     * @param endsAt 服务期结束时刻
     * @param billingPeriod 计费周期快照
     * @param priceCents 成交价目快照（本片为模拟金额）
     * @param currency ISO 4217 币种
     * @param sourceOrderId 来源订单 ID
     * @return 新订阅 ID
     */
    UUID activate(UUID tenantId, UUID planRevisionId, Instant startsAt, Instant endsAt,
                  String billingPeriod, long priceCents, String currency, UUID sourceOrderId);

    // ------------------------------------------------------------------
    // S14-3c：时间推进（到期宽限与受限切换）所需的读取与状态推进。
    //
    // 这些方法与上面的「关旧 + 建新」共用同一张表与同一把租户行锁，因此放在同一端口，
    // 而不是再开一个能写同一行的第二端口。所有推进语句都自带 {@code WHERE status = ...}
    // 的自身幂等仲裁：并发 worker 或重跑时只有一条能更新到行。
    // ------------------------------------------------------------------

    /**
     * 按 ID 读取订阅生命周期事实（不加锁）。
     *
     * @param subscriptionId 订阅 ID
     * @return 订阅事实；不存在时为空
     */
    Optional<SubscriptionLifecycleState> findState(UUID subscriptionId);

    /**
     * 读取租户当前生效的订阅事实：优先 ACTIVE，其次 GRACE，最后 RESTRICTED_FREE。
     *
     * <p>「优先 ACTIVE」是续费后的正确口径：宽限期内续费会新建一条 ACTIVE 订阅，
     * 旧 GRACE 行仍在表中，但客户已恢复生效，扩大类动作必须随之放行、旧行也不再推进。
     *
     * @param tenantId 租户 ID
     * @return 当前生效订阅；没有时为空
     */
    Optional<SubscriptionLifecycleState> findCurrentState(UUID tenantId);

    /**
     * 扫描到期未转宽限的 ACTIVE 订阅（{@code ends_at <= now}）。
     *
     * <p>永久 FREE（{@code ends_at IS NULL}）天然不在结果里：它是 P4「无终点」的长期订阅，
     * 时间推进必须原样跳过。
     *
     * @param now 当前时刻（UTC）
     * @param limit 单轮上限
     * @return 待转宽限的订阅，按到期时刻正序
     */
    List<SubscriptionLifecycleState> findDueForGrace(Instant now, int limit);

    /**
     * 扫描宽限已结束的 GRACE 订阅（{@code grace_ends_at <= now}）。
     *
     * <p>只返回「租户当前没有 ACTIVE 订阅」的行：宽限期内续费会新建 ACTIVE，旧 GRACE 行随即
     * 失去推进资格，不能被误切成 RESTRICTED_FREE。
     *
     * @param now 当前时刻（UTC）
     * @param limit 单轮上限
     * @return 待切受限免费的订阅，按宽限结束时刻正序
     */
    List<SubscriptionLifecycleState> findDueForRestriction(Instant now, int limit);

    /**
     * 扫描需要参与通知时间点计算的当前订阅（ACTIVE/GRACE/RESTRICTED_FREE 且有服务期终点）。
     *
     * @param limit 单轮上限
     * @return 当前订阅，按服务期终点正序
     */
    List<SubscriptionLifecycleState> findCurrentWithPeriodEnd(int limit);

    /**
     * 把到期 ACTIVE 订阅推进为 GRACE 并写入宽限终点。
     *
     * <p>{@code WHERE status = 'ACTIVE'} 与 {@code ends_at <= now} 使本语句自身即幂等仲裁。
     *
     * @param subscriptionId 订阅 ID
     * @param graceEndsAt 宽限终点（= 服务期终点 + 14 自然日，精确 UTC 时刻）
     * @param now 当前时刻，用于「确实到期」的数据库侧复验
     * @return 本次确实推进了状态时为 {@code true}
     */
    boolean enterGrace(UUID subscriptionId, Instant graceEndsAt, Instant now);

    /**
     * 把宽限结束的 GRACE 订阅推进为 RESTRICTED_FREE 并写入受限时刻。
     *
     * @param subscriptionId 订阅 ID
     * @param restrictedAt 受限时刻（UTC）
     * @param now 当前时刻，用于「宽限确实已结束」的数据库侧复验
     * @return 本次确实推进了状态时为 {@code true}
     */
    boolean enterRestrictedFree(UUID subscriptionId, Instant restrictedAt, Instant now);

    /**
     * 把 ACTIVE 订阅原地切换到目标产品修订版（消费 S14-3b 预约降级）。
     *
     * <p>不新建订阅行：降级在服务期终点生效，服务期本身不变，变更事实由
     * {@code sys_tenant_subscription_pending_change} 的 APPLIED 行与审计承担；原地换挡保留了
     * 原服务期的连续解释，也避免在支付未交付时凭空延长一个未付费周期。
     *
     * @param subscriptionId 订阅 ID
     * @param planRevisionId 目标产品修订版 ID
     * @return 本次确实切换了修订版时为 {@code true}（仅在仍为 ACTIVE 时成立）
     */
    boolean changePlanRevision(UUID subscriptionId, UUID planRevisionId);
}
