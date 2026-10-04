package com.things.link.project.domain;

import com.things.link.project.domain.plan.ResourcePackageAddition;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 租户资源包事实的持久化端口（S14-4a；S14-4b 增加人工调整的幂等与撤销写入）。
 *
 * <p>写入口只有六个动作：建一条包（购买或人工调整）、把 PENDING 激活、把 ACTIVE 置为到期、
 * 按来源订单读取、按幂等键读取人工调整、以及把调整撤销。没有任何 DELETE：包是历史事实，
 * 取消与退款靠状态推进。
 *
 * <p>所有状态推进语句自身都带 {@code WHERE status = ...} 的幂等仲裁：并发 worker 或重跑时
 * 只有一条能更新到行。合成读取（{@link #findActiveAdditions}）只认
 * {@code status = 'ACTIVE'} 且处于自身 {@code [startsAt, endsAt)} 窗口内的包 —— 购买与人工调整
 * 共用这一条合成规则，来源不参与过滤。
 *
 * <p>包表与 {@code sys_tenant_order}/{@code sys_tenant_subscription} 一样不套租户 RLS：
 * 服务以显式 {@code tenantId} 为参数，授权留在应用入口。
 */
public interface TenantResourcePackageRepository {

    /**
     * 写入一条资源包事实（状态由生效事务决定：订阅有效则 ACTIVE，否则 PENDING）。
     *
     * @param tenantId 归属租户
     * @param dimensionCode 冻结维度编码
     * @param amount 该包为维度增加的额度
     * @param unit 额度单位
     * @param window 计量窗口
     * @param startsAt 包自身服务期起点（UTC）
     * @param endsAt 包自身服务期终点（UTC）
     * @param source 来源
     * @param sourceOrderId 来源订单 ID；购买必填、人工调整必须为空
     * @param adjustment 人工调整元数据；购买必须为空
     * @param status 初始状态
     * @return 新包 ID
     */
    UUID insert(UUID tenantId, String dimensionCode, long amount, String unit, String window,
                Instant startsAt, Instant endsAt, ResourcePackageSource source,
                UUID sourceOrderId, ResourcePackageAdjustment adjustment,
                ResourcePackageStatus status);

    /**
     * 按 ID 读取资源包事实。
     *
     * @param packageId 包 ID
     * @return 包事实；不存在时为空
     */
    Optional<TenantResourcePackage> findById(UUID packageId);

    /**
     * 按来源订单读取该订单生效出来的包（幂等重放时返回既有事实）。
     *
     * @param orderId 来源订单 ID
     * @return 该订单创建的包；订单未生效时为空
     */
    Optional<TenantResourcePackage> findBySourceOrder(UUID orderId);

    /**
     * 按幂等键读取该租户的人工调整（重放时返回既有事实，不写第二行）。
     *
     * @param tenantId 租户 ID
     * @param idempotencyKey 调用方幂等键
     * @return 该键对应的调整；未提交过时为空
     */
    Optional<TenantResourcePackage> findAdjustmentByKey(UUID tenantId, String idempotencyKey);

    /**
     * 读取某租户「未终结」的扩容与调整事实（S14-4c 只读面）。
     *
     * <p>只返回 {@code ACTIVE} 与 {@code PENDING}：终态（{@code EXPIRED}/
     * {@code CANCELLED}/{@code REFUNDED}）属于历史台账，不属于「此刻/将来会生效的额度」，
     * 放进租户只读面会让列表随年份无界增长并让「我的额度为什么高」这一问更难回答。
     *
     * <p>不做时刻过滤：未来起点的 {@code ACTIVE} 行也要让租户看见（它会在窗口开始时自动生效），
     * 是否此刻参与合成由 {@link PlanQuotaAddition#from} 判定并显式表达。
     *
     * @param tenantId 租户 ID
     * @return 非终结事实，按起点升序（同起点按 ID 稳定排序）；没有时为空列表
     */
    List<TenantResourcePackage> findLiveByTenant(UUID tenantId);

    /**
     * 该资源包/人工调整行此刻是否正在贡献有效额度（判定完全在数据库内完成）。
     *
     * <p>与 {@code findActiveAdditions} 同一判据（{@code status = 'ACTIVE'} 且窗口覆盖数据库
     * {@code now()}），但作用于单行。退款与撤销人工调整要在**不引入第二个时间基准**的前提下决定
     * 「本次变化是否需要失效运行时策略缓存」：拿 JVM 时钟去比较数据库写入的 {@code starts_at/ends_at}，
     * 两者相差几毫秒就会给出相反结论并**跳过一次缓存失效**，让运行时继续按已被收回的额度放行
     * （D-180 已在资源包生效路径上复现过同一类缺口）。
     *
     * <p>行不存在时返回 {@code false}（fail-closed：不认为它在贡献额度）。
     *
     * @param packageId 资源包或人工调整行 ID
     * @return 此刻正在贡献有效额度时为 {@code true}
     */
    boolean isCurrentlyEffective(UUID packageId);

    /**
     * 读取某租户此刻有效的资源包加数（按维度/单位/窗口分组求和）。
     *
     * <p>只统计 {@code status = 'ACTIVE'} 且 {@code startsAt <= at <= endsAt} 的包；PENDING/
     * EXPIRED/CANCELLED/REFUNDED 贡献 0。
     *
     * @param tenantId 租户 ID
     * @param at 合成时刻（UTC）
     * @return 有效加数；没有时为不可变空列表
     */
    List<ResourcePackageAddition> findActiveAdditions(UUID tenantId, Instant at);

    /**
     * 扫描已到期但仍为 ACTIVE 的包（{@code ends_at <= now}）。
     *
     * @param now 当前时刻（UTC）
     * @param limit 单轮上限
     * @return 待到期包，按到期时刻正序
     */
    List<TenantResourcePackage> findDueForExpiry(Instant now, int limit);

    /**
     * 扫描已到起点、仍为 PENDING、且租户订阅处于 ACTIVE/GRACE 的包。
     *
     * @param now 当前时刻（UTC）
     * @param limit 单轮上限
     * @return 待激活包，按起算时刻正序
     */
    List<TenantResourcePackage> findDueForActivation(Instant now, int limit);

    /**
     * 把到期包置为 EXPIRED（{@code WHERE status = 'ACTIVE' AND ends_at <= now} 自身幂等）。
     *
     * @param packageId 包 ID
     * @param now 当前时刻，用于数据库侧复验「确实已到期」
     * @return 本次确实推进了状态时为 {@code true}
     */
    boolean markExpired(UUID packageId, Instant now);

    /**
     * 把待生效包置为 ACTIVE（{@code WHERE status = 'PENDING' AND starts_at <= now} 自身幂等）。
     *
     * @param packageId 包 ID
     * @param now 当前时刻，用于数据库侧复验「确实已到起点」
     * @return 本次确实推进了状态时为 {@code true}
     */
    boolean activatePending(UUID packageId, Instant now);

    /**
     * 把人工调整置为 {@code CANCELLED}（{@code WHERE status IN ('ACTIVE','PENDING')} 自身幂等）。
     *
     * <p>只接受 {@code source = 'OPERATION_ADJUSTMENT'} 的行：购买包的取消必须走退款（S14-5），
     * 否则会留下「收了钱、权益没了、账上没退」的三方不一致。调用方（服务）已按来源与可撤销性
     * 做过拒绝，这里再钉一次来源，避免仓储被绕过时静默改错事实。
     *
     * @param packageId 调整行 ID
     * @param now 撤销时刻（UTC），写入 {@code updated_at}
     * @return 本次确实推进了状态时为 {@code true}；已是终态时为 {@code false}
     */
    boolean cancelAdjustment(UUID packageId, Instant now);

    /**
     * 把**购买**包置为 {@code REFUNDED}（{@code WHERE status IN ('ACTIVE','PENDING')} 自身幂等）。
     *
     * <p>退款成功即收回权益：置为 {@code REFUNDED} 后包立刻不再参与有效权益合成（求和只认
     * {@code ACTIVE}）。只接受 {@code source = 'PURCHASE'}：人工调整没有资金事实，不存在退款路径。
     * 已到期/已取消/已退款的行返回 {@code false}——它们的权益本来就不再贡献，改写状态只会篡改历史。
     *
     * @param packageId 包 ID
     * @param now 退款时刻（UTC），写入 {@code updated_at}
     * @return 本次确实推进了状态时为 {@code true}
     */
    boolean markRefunded(UUID packageId, Instant now);
}
