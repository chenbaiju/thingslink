package com.things.link.project.application;

import com.things.link.project.domain.ActiveSubscription;
import com.things.link.project.domain.PlanPurchase;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.SubscriptionPendingChange;
import com.things.link.project.domain.TenantOrderRepository;
import com.things.link.project.domain.TenantSubscriptionChangeRepository;
import com.things.link.project.domain.TenantSubscriptionLifecycleRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 预约降级的事务应用服务（S14-3b，架构文档 §3.2，S14-0 P5）。
 *
 * <h2>本片只写「计划」，不写「切换」</h2>
 * P5 冻结：降级**下个周期生效**、周期结束前任意时刻可撤销。本服务因此只做两件事 ——
 * 把「下周期要切到哪个修订版」持久化成一条 {@code PENDING} 预约，以及把 {@code PENDING}
 * 置为 {@code CANCELLED}。真正的切换（关旧、开新、绑策略、审计）属 S14-3c；本服务把
 * 状态与目标写到 3c 可以直接消费的形状（生效时刻 = 当前服务期终点、subscription_id 指向
 * 预约时的 ACTIVE 订阅），并保证唯一 ACTIVE 不变式完全不受影响：本服务从不触碰
 * {@code sys_tenant_subscription.status}。
 *
 * <h2>幂等与冲突</h2>
 * <ul>
 *   <li>目标相同的重复预约是幂等重放：返回既有 PENDING，不写第二行、不重复审计；</li>
 *   <li>已有 PENDING 且目标或订阅不同：拒绝（{@link ProjectErrorCode#PENDING_CHANGE_CONFLICT}），
 *       要求显式先撤销 —— 静默替换会抹掉客户上一次表达的意图，也让两个并发请求各自认为
 *       自己是最终状态。最终仲裁者仍是
 *       {@code sys_tenant_subscription_pending_change_pending_uk}；</li>
 *   <li>重复撤销是 no-op（返回 {@code false}），不重复审计。</li>
 * </ul>
 *
 * <h2>并发</h2>
 * 预约与撤销都先取 {@code sys_tenant} 行锁，与「支付成功 → 关旧开新」用同一把锁串行化：
 * 否则预约可能读到一条随后被升级取代的订阅，留下永远无法套用的 PENDING。
 *
 * <h2>与续费的边界（留给 S14-3c）</h2>
 * 续费（S14-3a 的整份购买）会把原订阅置为 {@code SUPERSEDED} 并从其到期日延长，本服务
 * <b>不</b>因此取消预约：客户「周期末降级」的意图在续费后的新周期末依然成立，静默取消
 * 反而是丢失客户意图。代价是预约行的 {@code subscriptionId} 可能不再是当前 ACTIVE，
 * S14-3c 套用前必须校验该订阅仍是 ACTIVE，校验失败时不得凭 target 盲切。
 */
@Service
public class TenantSubscriptionChangeService {

    /** 预约降级事实的读写端口。 */
    private final TenantSubscriptionChangeRepository changeRepository;
    /** 订阅生命周期的读入口（当前 ACTIVE 订阅是升降级判定的输入）。 */
    private final TenantSubscriptionLifecycleRepository subscriptionLifecycleRepository;
    /** 修订版购买投影的读取入口；复用 S14-3a 的同一份目录 SQL，不新建第二个读取器。 */
    private final TenantOrderRepository orderRepository;
    /** 商业审计写入（预约 / 撤销）。 */
    private final AuditLogService auditLogService;

    /**
     * @param changeRepository 预约降级事实的读写端口
     * @param subscriptionLifecycleRepository 订阅生命周期读入口
     * @param orderRepository 修订版购买投影读取入口
     * @param auditLogService 商业审计写入
     */
    public TenantSubscriptionChangeService(TenantSubscriptionChangeRepository changeRepository,
                                           TenantSubscriptionLifecycleRepository subscriptionLifecycleRepository,
                                           TenantOrderRepository orderRepository,
                                           AuditLogService auditLogService) {
        this.changeRepository = changeRepository;
        this.subscriptionLifecycleRepository = subscriptionLifecycleRepository;
        this.orderRepository = orderRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * 读取租户当前唯一的 PENDING 预约降级（只读，供 S14-3b 读面与 S14-3c 消费）。
     *
     * @param tenantId 租户 ID
     * @return 待生效预约；没有时为空
     */
    @Transactional(readOnly = true)
    public Optional<SubscriptionPendingChange> findPendingChange(UUID tenantId) {
        Objects.requireNonNull(tenantId, "租户 ID 不得为空");
        return changeRepository.findPending(tenantId);
    }

    /**
     * 预约一次下个周期生效的降级。
     *
     * @param tenantId 归属租户
     * @param targetRevisionId 目标产品修订版；必须严格低于当前档位
     * @return 新建的、或与本次请求完全一致的既有 PENDING 预约
     * @throws BusinessException 租户/修订版不存在、没有生效订阅、目标不是更低档位或已有冲突预约
     */
    @Transactional
    public SubscriptionPendingChange requestDowngrade(UUID tenantId, UUID targetRevisionId) {
        Objects.requireNonNull(tenantId, "租户 ID 不得为空");
        Objects.requireNonNull(targetRevisionId, "目标产品修订版 ID 不得为空");

        if (!changeRepository.lockTenant(tenantId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "租户不存在");
        }
        ActiveSubscription current = subscriptionLifecycleRepository.lockActiveSubscription(tenantId)
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.NO_ACTIVE_SUBSCRIPTION));
        if (current.endsAt() == null) {
            // 长期 FREE 没有「下个周期从哪开始」，预约降级无从表达；从 FREE 到付费档是首次购买。
            throw new BusinessException(ProjectErrorCode.SUBSCRIPTION_PERIOD_UNBOUNDED);
        }
        PlanPurchase source = orderRepository.findPlanPurchase(current.planRevisionId())
                .orElseThrow(() -> new IllegalStateException(
                        "当前订阅引用的产品修订版不存在: " + current.planRevisionId()));
        PlanPurchase target = orderRepository.findPlanPurchase(targetRevisionId)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.RESOURCE_NOT_FOUND, "套餐修订版不存在"));
        if (target.displayOrder() >= source.displayOrder()) {
            throw new BusinessException(ProjectErrorCode.NOT_A_DOWNGRADE);
        }

        Optional<SubscriptionPendingChange> existing = changeRepository.findPending(tenantId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), current.id(), targetRevisionId);
        }

        UUID changeId;
        try {
            changeId = changeRepository.insertPending(tenantId, current.id(), current.planRevisionId(),
                    targetRevisionId, current.endsAt());
        } catch (DuplicateKeyException conflict) {
            // 并发下另一个请求已落库：按同一规则收敛为幂等重放或显式冲突，绝不静默替换。
            return changeRepository.findPending(tenantId)
                    .map(pending -> replayOrConflict(pending, current.id(), targetRevisionId))
                    .orElseThrow(() -> conflict);
        }
        SubscriptionPendingChange created = changeRepository.findById(changeId)
                .orElseThrow(() -> new IllegalStateException("预约刚写入却读不回来: " + changeId));
        auditLogService.record(new AuditLogEntry(tenantId, null, null,
                "tenant_subscription_pending_change", changeId,
                "commercial.subscription.downgrade.scheduled",
                scheduledDetails(created, source, target, current)));
        return created;
    }

    /**
     * 撤销租户当前的 PENDING 预约降级（周期结束前任意时刻可撤销）。
     *
     * <p>本方法只认状态、不读时钟：周期结束前的正常撤销与「S14-3c 尚未套用」在数据上都是
     * {@code PENDING}。时间边界由 S14-3c 在 {@code effectiveAt} 处推进状态：一旦置为
     * {@code APPLIED}，本方法自然更新不到行并返回 {@code false}。本片刻意不重复实现
     * 时间驱动判定（避免与 3c 的状态机争抢同一行）。
     *
     * @param tenantId 租户 ID
     * @return 本次确实撤销了预约时为 {@code true}；没有待生效预约（或已被并发撤销）时为 {@code false}
     * @throws BusinessException 租户不存在
     */
    @Transactional
    public boolean cancelDowngrade(UUID tenantId) {
        Objects.requireNonNull(tenantId, "租户 ID 不得为空");
        if (!changeRepository.lockTenant(tenantId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "租户不存在");
        }
        Optional<SubscriptionPendingChange> pending = changeRepository.findPending(tenantId);
        if (pending.isEmpty()) {
            // 没有预约可撤销：幂等 no-op，不写审计（没有发生任何事实变化）。
            return false;
        }
        SubscriptionPendingChange change = pending.get();
        if (!changeRepository.cancelPending(change.id())) {
            return false;
        }
        auditLogService.record(new AuditLogEntry(tenantId, null, null,
                "tenant_subscription_pending_change", change.id(),
                "commercial.subscription.downgrade.cancelled",
                cancelledDetails(change, "TENANT_REQUEST", null)));
        return true;
    }

    /**
     * 升级立即生效时清除预约降级（P5：客户向上改主意，旧的降级意图必须作废）。
     *
     * <p><b>调用方必须已持有 {@code sys_tenant} 行锁</b>（即在同一生效事务内、锁已获取之后
     * 调用），否则清除动作可能与并发的预约请求交错。该方法不自己取锁，也不开新事务，
     * 与升级生效共用同一个数据库事务：要么「升级生效 + 降级撤销」一起提交，要么一起回滚。
     *
     * @param tenantId 租户 ID
     * @param upgradeOrderId 触发清除的升级订单 ID（仅用于审计关联）
     * @return 本次确实清除了预约时为 {@code true}
     */
    @Transactional
    public boolean cancelPendingForUpgrade(UUID tenantId, UUID upgradeOrderId) {
        Objects.requireNonNull(tenantId, "租户 ID 不得为空");
        Optional<SubscriptionPendingChange> pending = changeRepository.findPending(tenantId);
        if (pending.isEmpty()) {
            return false;
        }
        SubscriptionPendingChange change = pending.get();
        if (!changeRepository.cancelPending(change.id())) {
            return false;
        }
        auditLogService.record(new AuditLogEntry(tenantId, null, null,
                "tenant_subscription_pending_change", change.id(),
                "commercial.subscription.downgrade.cancelled",
                cancelledDetails(change, "SUPERSEDED_BY_UPGRADE", upgradeOrderId)));
        return true;
    }

    /**
     * 取消订阅时撤销预约，与资金执行原事务共用租户锁，不伪装升级。
     * @param tenantId 已锁定租户 @param operationId 原取消操作 @return 是否撤销已有预约
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean cancelPendingForSubscriptionCancellation(UUID tenantId, UUID operationId) {
        var pending=changeRepository.findPending(Objects.requireNonNull(tenantId));
        Objects.requireNonNull(operationId);
        if (pending.isEmpty()) return false;
        var change=pending.get();
        if (!changeRepository.cancelPending(change.id())) {
            throw new IllegalStateException("已锁租户的预约取消发生状态冲突");
        }
        var details=cancelledDetails(change,"SUBSCRIPTION_CANCELLED",null);
        details.put("cancellationOperationId",operationId.toString());
        auditLogService.record(new AuditLogEntry(tenantId,null,null,"tenant_subscription_pending_change",change.id(),
                "commercial.subscription.downgrade.cancelled",details));
        return true;
    }

    /**
     * 既有 PENDING 与本次请求的关系：完全一致即幂等重放，否则显式冲突。
     *
     * @param pending 既有 PENDING 预约
     * @param currentSubscriptionId 当前 ACTIVE 订阅 ID
     * @param targetRevisionId 本次请求的目标修订版
     * @return 幂等重放时返回既有预约
     * @throws BusinessException 目标或针对订阅不同
     */
    private SubscriptionPendingChange replayOrConflict(SubscriptionPendingChange pending,
                                                       UUID currentSubscriptionId,
                                                       UUID targetRevisionId) {
        if (pending.targetPlanRevisionId().equals(targetRevisionId)
                && pending.subscriptionId().equals(currentSubscriptionId)) {
            return pending;
        }
        throw new BusinessException(ProjectErrorCode.PENDING_CHANGE_CONFLICT);
    }

    /**
     * 预约事件的审计详情：来源与目标档位、生效时刻与状态。
     *
     * @param change 新建的预约
     * @param source 来源修订版投影
     * @param target 目标修订版投影
     * @param current 预约时的当前生效订阅
     * @return 结构化详情
     */
    private Map<String, Object> scheduledDetails(SubscriptionPendingChange change,
                                                 PlanPurchase source, PlanPurchase target,
                                                 ActiveSubscription current) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("pendingChangeId", change.id().toString());
        details.put("subscriptionId", change.subscriptionId().toString());
        details.put("fromPlanRevisionId", source.planRevisionId().toString());
        details.put("fromPlanCode", source.planCode());
        details.put("fromDisplayOrder", source.displayOrder());
        details.put("targetPlanRevisionId", target.planRevisionId().toString());
        details.put("targetPlanCode", target.planCode());
        details.put("targetDisplayOrder", target.displayOrder());
        details.put("effectiveAt", change.effectiveAt().toString());
        details.put("currentStartsAt", current.startsAt().toString());
        details.put("status", change.status().name());
        return details;
    }

    /**
     * 撤销事件的审计详情：撤销原因与被撤销的目标。
     *
     * @param change 被撤销的预约
     * @param reason 撤销原因：{@code TENANT_REQUEST} 或 {@code SUPERSEDED_BY_UPGRADE}
     * @param upgradeOrderId 触发撤销的升级订单 ID；客户主动撤销时为 {@code null}
     * @return 结构化详情
     */
    private Map<String, Object> cancelledDetails(SubscriptionPendingChange change, String reason,
                                                 UUID upgradeOrderId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("pendingChangeId", change.id().toString());
        details.put("subscriptionId", change.subscriptionId().toString());
        details.put("fromPlanRevisionId", change.fromPlanRevisionId().toString());
        details.put("targetPlanRevisionId", change.targetPlanRevisionId().toString());
        details.put("effectiveAt", change.effectiveAt().toString());
        details.put("reason", reason);
        if (upgradeOrderId != null) {
            details.put("upgradeOrderId", upgradeOrderId.toString());
        }
        return details;
    }
}
