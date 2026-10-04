package com.things.link.project.application;

import com.things.link.project.domain.FreeSubscriptionSnapshot;
import com.things.link.project.domain.PlanPurchase;
import com.things.link.project.domain.SubscriptionLifecycleState;
import com.things.link.project.domain.SubscriptionNotificationIntentRepository;
import com.things.link.project.domain.SubscriptionNotificationKind;
import com.things.link.project.domain.SubscriptionPendingChange;
import com.things.link.project.domain.SubscriptionStatus;
import com.things.link.project.domain.TenantOrderRepository;
import com.things.link.project.domain.TenantSubscriptionChangeRepository;
import com.things.link.project.domain.TenantSubscriptionLifecycleRepository;
import com.things.link.project.domain.TenantSubscriptionRepository;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 订阅到期宽限与受限切换的时间驱动状态机（S14-3c，S14-0 P4 / P5）。
 *
 * <h2>一条时间轴，四个幂等阶段</h2>
 * 单次推进（{@link #advanceDueTransitions()} 或测试用的 {@link #advance(Instant)}）按固定顺序做四件事：
 * <ol>
 *   <li><b>消费预约降级</b>：捞 {@code PENDING AND effective_at <= now}；套用前<b>复验订阅仍是 ACTIVE</b>
 *       （S14-3b 的硬约束：续费不清除预约，预约行可能已指向被取代的订阅），复验不过则置 CANCELLED 并记原因；</li>
 *   <li><b>ACTIVE → GRACE</b>：{@code ends_at <= now} 的付费订阅写 {@code grace_ends_at = ends_at + 14 自然日}；</li>
 *   <li><b>GRACE → RESTRICTED_FREE</b>：{@code grace_ends_at <= now} 且租户已无 ACTIVE 订阅时置显式受限状态，
 *       经 S7 绑定 FREE 策略，并按 P4 把超额自有项目（保留最早创建的 1 个）置为商业只读；</li>
 *   <li><b>通知意图</b>：五个时间点各自 {@code insertIfAbsent}（唯一键幂等），以及续费/升级后恢复
 *       商业受限项目。</li>
 * </ol>
 *
 * <h2>为什么整轮共用一个事务</h2>
 * 每条状态推进语句自身都带 {@code WHERE status = ...} 的幂等仲裁，整轮一个事务保证
 * 「状态已切 + 审计已写 + 策略已绑 + 项目已受限」不会出现半成品；失败整轮回滚，下一轮重试。
 * 单轮用 {@code LIMIT} 限制规模，避免长事务。
 *
 * <h2>为什么永久 FREE 永不被触碰</h2>
 * 到期扫描只认 {@code ends_at IS NOT NULL}，通知计算也只在有服务期终点时进行；{@code ends_at IS NULL}
 * 的零价长期 FREE 因此不进入任何一步（P4「无终点」语义，不是「额度不限」）。
 *
 * <h2>幂等性证据</h2>
 * 重跑同一时刻：状态推进更新不到行、通知意图撞唯一键返回空、预约已 APPLIED、限制只在状态切换成功时执行 ——
 * 状态、行数、审计、意图全部不变。
 */
@Service
public class SubscriptionLifecycleService {

    /** 单轮每类扫描的上限；把维护事务的规模钉死，避免一次处理全平台。 */
    static final int BATCH_LIMIT = 500;

    /** P4 冻结的宽限时长：14 个自然日 = 精确 336 小时（UTC 精确时刻，不随时区/DST 漂移）。 */
    static final Duration GRACE_PERIOD = Duration.ofDays(14);

    /** 预约被撤销（不再套用）时的稳定原因码：订阅已不是 ACTIVE。 */
    static final String REASON_SUBSCRIPTION_NOT_ACTIVE = "SUBSCRIPTION_NOT_ACTIVE";
    /** 预约被撤销（不再套用）时的稳定原因码：来源修订版已不是当前订阅的修订版。 */
    static final String REASON_SOURCE_REVISION_CHANGED = "SOURCE_REVISION_CHANGED";

    /** 订阅生命周期事实的读写端口（扫描与状态推进）。 */
    private final TenantSubscriptionLifecycleRepository lifecycleRepository;
    /** 预约降级事实的读写端口（消费 PENDING）。 */
    private final TenantSubscriptionChangeRepository changeRepository;
    /** 通知意图的幂等写入端口。 */
    private final SubscriptionNotificationIntentRepository notificationIntentRepository;
    /** 目标修订版的购买投影读取端口（拿配额模板 ID）。 */
    private final TenantOrderRepository orderRepository;
    /** FREE 冻结快照端口（拿 FREE 配额模板 ID）。 */
    private final TenantSubscriptionRepository subscriptionRepository;
    /** 自有项目的商业写状态读写端口（超额只读与恢复）。 */
    private final CommercialProjectAccessService projectAccess;
    /** S7 运行时策略 CAS 绑定用例；到期降级与受限切换都必须复用它。 */
    private final QuotaPolicyAssignmentService quotaPolicyAssignmentService;
    /** 商业审计写入（状态推进 / 预约套用 / 通知意图 / 项目受限）。 */
    private final AuditLogService auditLogService;
    /** 时间来源；生产固定 UTC，测试可注入固定时刻。 */
    private final Clock clock;

    /**
     * 生产构造器：时间取系统 UTC 时钟。
     *
     * @param lifecycleRepository 订阅生命周期读写端口
     * @param changeRepository 预约降级读写端口
     * @param notificationIntentRepository 通知意图写入端口
     * @param orderRepository 修订版购买投影读取端口
     * @param subscriptionRepository FREE 冻结快照端口
     * @param projectAccess 按当前额度限制和恢复商业项目
     * @param quotaPolicyAssignmentService S7 运行时策略 CAS 绑定用例
     * @param auditLogService 商业审计写入
     */
    @Autowired
    public SubscriptionLifecycleService(TenantSubscriptionLifecycleRepository lifecycleRepository,
                                        TenantSubscriptionChangeRepository changeRepository,
                                        SubscriptionNotificationIntentRepository notificationIntentRepository,
                                        TenantOrderRepository orderRepository,
                                        TenantSubscriptionRepository subscriptionRepository,
                                        CommercialProjectAccessService projectAccess,
                                        QuotaPolicyAssignmentService quotaPolicyAssignmentService,
                                        AuditLogService auditLogService) {
        this(lifecycleRepository, changeRepository, notificationIntentRepository, orderRepository,
                subscriptionRepository, projectAccess,
                quotaPolicyAssignmentService, auditLogService, Clock.systemUTC());
    }

    /**
     * 可注入时钟的构造器：真库用例用 {@code Clock.fixed} 把时间钉在到期日/宽限结束日等边界上。
     *
     * @param lifecycleRepository 订阅生命周期读写端口
     * @param changeRepository 预约降级读写端口
     * @param notificationIntentRepository 通知意图写入端口
     * @param orderRepository 修订版购买投影读取端口
     * @param subscriptionRepository FREE 冻结快照端口
     * @param projectAccess 按当前额度限制和恢复商业项目
     * @param quotaPolicyAssignmentService S7 运行时策略 CAS 绑定用例
     * @param auditLogService 商业审计写入
     * @param clock 时间来源
     */
    public SubscriptionLifecycleService(TenantSubscriptionLifecycleRepository lifecycleRepository,
                                        TenantSubscriptionChangeRepository changeRepository,
                                        SubscriptionNotificationIntentRepository notificationIntentRepository,
                                        TenantOrderRepository orderRepository,
                                        TenantSubscriptionRepository subscriptionRepository,
                                        CommercialProjectAccessService projectAccess,
                                        QuotaPolicyAssignmentService quotaPolicyAssignmentService,
                                        AuditLogService auditLogService,
                                        Clock clock) {
        this.lifecycleRepository = lifecycleRepository;
        this.changeRepository = changeRepository;
        this.notificationIntentRepository = notificationIntentRepository;
        this.orderRepository = orderRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.projectAccess = projectAccess;
        this.quotaPolicyAssignmentService = quotaPolicyAssignmentService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    /**
     * 推进一轮时间驱动状态机（生产入口；时间取注入时钟）。
     *
     * @return 本轮各类推进的计数
     */
    @Transactional
    public SubscriptionLifecycleReport advanceDueTransitions() {
        // 数据库 timestamptz 精度为微秒；持久化后立即回读的时刻可能被舍入到 now 之后，
        // 造成同一轮漏掉 RESTRICTED_SWITCH 通知，随后重跑又多插一条。
        return advance(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    /**
     * 在指定时刻推进一轮状态机（测试与运维显式驱动入口）。
     *
     * <p>{@code now} 显式传入而不是内部读时钟，是为了让用例能对「到期日 / 宽限第 7 天 / 宽限结束日」
     * 逐点断言，同时生产入口仍走注入时钟。整轮同一事务，失败全部回滚。
     *
     * @param now 当前时刻（UTC）
     * @return 本轮各类推进的计数
     */
    @Transactional
    public SubscriptionLifecycleReport advance(Instant now) {
        Objects.requireNonNull(now, "推进时刻不得为空");

        var pendingChanges=changeRepository.findDuePending(now,BATCH_LIMIT);
        var dueGrace=lifecycleRepository.findDueForGrace(now,BATCH_LIMIT);
        var dueRestriction=lifecycleRepository.findDueForRestriction(now,BATCH_LIMIT);
        var tenantIds=new java.util.TreeSet<UUID>(projectAccess.pendingTenantIds());
        pendingChanges.forEach(change -> tenantIds.add(change.tenantId()));
        dueGrace.forEach(state -> tenantIds.add(state.tenantId()));
        dueRestriction.forEach(state -> tenantIds.add(state.tenantId()));
        // R7f：所有状态更新前按统一顺序取租户锁，避免订阅行→租户与支付的反向等待。
        tenantIds.forEach(lifecycleRepository::lockTenantAndReadAssignmentVersion);
        var restrictionCandidates=new java.util.ArrayList<>(dueRestriction);
        int downgradesApplied = 0;
        int downgradesNotApplied = 0;
        for (SubscriptionPendingChange change : pendingChanges) {
            if (applyPendingDowngrade(change, now)) {
                downgradesApplied++;
            } else {
                downgradesNotApplied++;
            }
        }

        int graceEntered = 0;
        for (SubscriptionLifecycleState candidate : dueGrace) {
            var state=lifecycleRepository.findState(candidate.id()).orElse(null);
            if (state==null || state.endsAt()==null) continue;
            Instant graceEndsAt = state.endsAt().plus(GRACE_PERIOD);
            if (lifecycleRepository.enterGrace(state.id(), graceEndsAt, now)) {
                graceEntered++;
                lifecycleRepository.findState(state.id()).ifPresent(restrictionCandidates::add);
                auditLogService.record(new AuditLogEntry(state.tenantId(), null, null,
                        "tenant_subscription", state.id(), "commercial.subscription.grace.entered",
                        graceDetails(state, graceEndsAt)));
            }
        }

        int restrictedFree = 0;
        int projectsRestricted = 0;
        var visitedRestrictions=new java.util.HashSet<UUID>();
        for (SubscriptionLifecycleState candidate : restrictionCandidates) {
            if (!visitedRestrictions.add(candidate.id())) continue;
            var state=lifecycleRepository.findState(candidate.id()).orElse(null);
            if (state==null || state.status()!=SubscriptionStatus.GRACE || state.graceEndsAt()==null
                    || state.graceEndsAt().isAfter(now)) continue;
            Optional<RestrictionOutcome> outcome = enterRestrictedFree(state, now);
            if (outcome.isPresent()) {
                restrictedFree++;
                projectsRestricted += outcome.get().restrictedProjectIds().size();
            }
        }

        int notificationIntents = syncNotificationIntents(now);
        int projectsRestored = 0;
        for (UUID tenantId:tenantIds) {
            projectsRestored=Math.addExact(projectsRestored,projectAccess.restoreEligible(tenantId,now));
        }

        return new SubscriptionLifecycleReport(graceEntered, restrictedFree, downgradesApplied,
                downgradesNotApplied, notificationIntents, projectsRestricted, projectsRestored);
    }

    /**
     * 消费一条到期的预约降级。
     *
     * <p>套用前必须复验预约指向的订阅仍是 ACTIVE（S14-3b 硬约束）。复验不过就<b>不套用</b>，
     * 把预约置为 CANCELLED 并写明原因 —— 置终态而不是留在 PENDING，是为了让下一轮不再重复处理、
     * 不再重复写审计；「留在 PENDING 等人工处理」会让每轮都重放同一条拒绝。
     *
     * @param change 到期预约
     * @param now 当前时刻
     * @return 本次确实套用了目标修订版时为 {@code true}
     */
    private boolean applyPendingDowngrade(SubscriptionPendingChange change, Instant now) {
        // 只用普通读做快速判定；真正的原子仲裁在持租户锁后的 changePlanRevision(WHERE status='ACTIVE')。
        SubscriptionLifecycleState snapshot = lifecycleRepository.findState(change.subscriptionId()).orElse(null);
        if (snapshot == null || snapshot.status() != SubscriptionStatus.ACTIVE) {
            return rejectDowngrade(change, REASON_SUBSCRIPTION_NOT_ACTIVE);
        }
        if (!snapshot.planRevisionId().equals(change.fromPlanRevisionId())) {
            return rejectDowngrade(change, REASON_SOURCE_REVISION_CHANGED);
        }
        PlanPurchase target = orderRepository.findPlanPurchase(change.targetPlanRevisionId())
                .orElseThrow(() -> new IllegalStateException(
                        "预约降级的目标修订版不存在: " + change.targetPlanRevisionId()));

        // 与「支付成功 → 关旧开新」共用租户行锁：锁持有期间订阅不会被并发生效换掉，锁后复验才有意义。
        long assignmentVersion = lifecycleRepository.lockTenantAndReadAssignmentVersion(change.tenantId());
        SubscriptionLifecycleState locked = lifecycleRepository.findState(change.subscriptionId()).orElse(null);
        if (locked == null || locked.status() != SubscriptionStatus.ACTIVE
                || !locked.planRevisionId().equals(change.fromPlanRevisionId())) {
            return rejectDowngrade(change, REASON_SUBSCRIPTION_NOT_ACTIVE);
        }
        if (!lifecycleRepository.changePlanRevision(change.subscriptionId(), change.targetPlanRevisionId())) {
            throw new IllegalStateException("预约降级换挡失败，订阅已不在 ACTIVE: " + change.subscriptionId());
        }
        quotaPolicyAssignmentService.assign(change.tenantId(), target.quotaPolicyId(), assignmentVersion);
        if (!changeRepository.markApplied(change.id(), now)) {
            throw new IllegalStateException("预约降级置 APPLIED 失败，预约已不在 PENDING: " + change.id());
        }
        auditLogService.record(new AuditLogEntry(change.tenantId(), null, null,
                "tenant_subscription_pending_change", change.id(),
                "commercial.subscription.downgrade.applied",
                downgradeAppliedDetails(change, locked, target, assignmentVersion)));
        return true;
    }

    /**
     * 把无法套用的预约置为 CANCELLED 并记录原因（幂等：并发下只有一次能更新到行）。
     *
     * @param change 到期预约
     * @param reason 稳定原因码
     * @return 恒为 {@code false}（本次没有套用）
     */
    private boolean rejectDowngrade(SubscriptionPendingChange change, String reason) {
        if (changeRepository.cancelPending(change.id())) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("pendingChangeId", change.id().toString());
            details.put("subscriptionId", change.subscriptionId().toString());
            details.put("fromPlanRevisionId", change.fromPlanRevisionId().toString());
            details.put("targetPlanRevisionId", change.targetPlanRevisionId().toString());
            details.put("effectiveAt", change.effectiveAt().toString());
            details.put("reason", reason);
            auditLogService.record(new AuditLogEntry(change.tenantId(), null, null,
                    "tenant_subscription_pending_change", change.id(),
                    "commercial.subscription.downgrade.not_applied", details));
        }
        return false;
    }

    /**
     * 把一个宽限结束的订阅切换到 {@code RESTRICTED_FREE}，绑 FREE 策略并执行超额项目只读矩阵。
     *
     * <p>超额项目复用既有 {@code ARCHIVED} 只读语义（见迁移与 ProjectRepository 注释）：项目域唯一
     * 写门禁已把 ARCHIVED 统一转为 50017，因此不需要跨模块改动；商业受限事实同时登记进
     * {@code sys_project_commercial_restriction} 台账，供续费/升级恢复时精确区分用户主动归档。
     *
     * @param state 宽限已结束的订阅
     * @param now 切换时刻
     * @return 本次确实切换时为结果；状态已被并发推进时为 {@code Optional.empty()}
     */
    private Optional<RestrictionOutcome> enterRestrictedFree(SubscriptionLifecycleState state, Instant now) {
        long assignmentVersion = lifecycleRepository.lockTenantAndReadAssignmentVersion(state.tenantId());
        if (!lifecycleRepository.findCurrentState(state.tenantId())
                .map(current -> current.id().equals(state.id()) && current.status()==SubscriptionStatus.GRACE).orElse(false)) {
            return Optional.empty();
        }
        if (!lifecycleRepository.enterRestrictedFree(state.id(), now, now)) {
            return Optional.empty();
        }
        FreeSubscriptionSnapshot free = subscriptionRepository.findFreeSubscriptionSnapshot()
                .orElseThrow(() -> new IllegalStateException(
                        "FREE 冻结快照缺失，无法把订阅切换到受限免费: " + state.tenantId()));
        quotaPolicyAssignmentService.assign(state.tenantId(), free.quotaPolicyId(), assignmentVersion);

        var outcome=projectAccess.restrictToCurrentLimit(state.tenantId(),state.id(),now,
                "SUBSCRIPTION_RESTRICTED_FREE_OVER_LIMIT");
        List<UUID> owned=outcome.ownedProjectIds();
        List<UUID> kept=outcome.keptWritableProjectIds();
        List<UUID> restricted=outcome.restrictedProjectIds();
        auditLogService.record(new AuditLogEntry(state.tenantId(), null, null,
                "tenant_subscription", state.id(), "commercial.subscription.restricted_free.entered",
                restrictedDetails(state, now, assignmentVersion, free, owned, kept, restricted)));
        return Optional.of(new RestrictionOutcome(kept, restricted));
    }

    /**
     * 为当前订阅补齐五个时间点里所有已到应发时刻的通知意图。
     *
     * <p>「已到时刻就补」而不是「恰好等于才发」：worker 停摆后重启仍会把漏掉的意图补上，
     * 且唯一键保证每点只补一次；真实发送未交付，这里落的是意图 + 审计。
     *
     * @param now 当前时刻
     * @return 本轮新落库的意图数
     */
    private int syncNotificationIntents(Instant now) {
        int created = 0;
        for (SubscriptionLifecycleState state : lifecycleRepository.findCurrentWithPeriodEnd(BATCH_LIMIT)) {
            for (SubscriptionNotificationKind kind : SubscriptionNotificationKind.values()) {
                Instant fireAt = kind.fireAt(state);
                if (fireAt == null || fireAt.isAfter(now)) {
                    continue;
                }
                Optional<UUID> intentId = notificationIntentRepository.insertIfAbsent(
                        state.tenantId(), state.id(), kind, state.endsAt(), fireAt);
                if (intentId.isPresent()) {
                    created++;
                    auditLogService.record(new AuditLogEntry(state.tenantId(), null, null,
                            "tenant_subscription", state.id(),
                            "commercial.subscription.notification.intent",
                            notificationDetails(state, kind, fireAt, intentId.get())));
                }
            }
        }
        return created;
    }

    /**
     * 进入宽限的审计详情：冻结算法的两个时刻都要能从事件本身重算。
     *
     * @param state 推进前的 ACTIVE 订阅
     * @param graceEndsAt 计算出的宽限终点
     * @return 结构化详情
     */
    private Map<String, Object> graceDetails(SubscriptionLifecycleState state, Instant graceEndsAt) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("subscriptionId", state.id().toString());
        details.put("planRevisionId", state.planRevisionId().toString());
        details.put("expiresAt", state.endsAt().toString());
        details.put("graceEndsAt", graceEndsAt.toString());
        details.put("graceDays", GRACE_PERIOD.toDays());
        details.put("graceFormula", "expiresAt + 14 natural days (336 hours, UTC instant)");
        return details;
    }

    /**
     * 切入受限免费的审计详情：P4 超额矩阵用到的每个事实都留痕。
     *
     * @param state 切换前的 GRACE 订阅
     * @param restrictedAt 受限时刻
     * @param assignmentVersion 切换前的策略绑定版本
     * @param free FREE 冻结快照
     * @param owned 租户全部自有项目（创建时刻正序）
     * @param kept 保留可写的项目
     * @param restricted 转为只读的项目
     * @return 结构化详情
     */
    private Map<String, Object> restrictedDetails(SubscriptionLifecycleState state, Instant restrictedAt,
                                                  long assignmentVersion, FreeSubscriptionSnapshot free,
                                                  List<UUID> owned, List<UUID> kept, List<UUID> restricted) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("subscriptionId", state.id().toString());
        details.put("fromPlanRevisionId", state.planRevisionId().toString());
        details.put("expiresAt", state.endsAt().toString());
        details.put("graceEndsAt", state.graceEndsAt() == null ? null : state.graceEndsAt().toString());
        details.put("restrictedAt", restrictedAt.toString());
        details.put("freePlanRevisionId", free.planRevisionId().toString());
        details.put("freeQuotaPolicyId", free.quotaPolicyId().toString());
        details.put("assignmentVersionBefore", assignmentVersion);
        details.put("ownedProjectIds", owned.stream().map(UUID::toString).toList());
        details.put("keptWritableProjectIds", kept.stream().map(UUID::toString).toList());
        details.put("restrictedProjectIds", restricted.stream().map(UUID::toString).toList());
        details.put("projectRestrictionMechanism", "REUSE_EXISTING_ARCHIVED_READ_ONLY");
        details.put("projectPhysicallyDeleted", false);
        return details;
    }

    /**
     * 预约降级套用的审计详情：来源/目标、生效时刻与推进后的绑定版本。
     *
     * @param change 被套用的预约
     * @param locked 锁下复验的订阅事实
     * @param target 目标修订版购买投影
     * @param assignmentVersion 推进前的绑定版本
     * @return 结构化详情
     */
    private Map<String, Object> downgradeAppliedDetails(SubscriptionPendingChange change,
                                                        SubscriptionLifecycleState locked,
                                                        PlanPurchase target, long assignmentVersion) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("pendingChangeId", change.id().toString());
        details.put("subscriptionId", change.subscriptionId().toString());
        details.put("fromPlanRevisionId", change.fromPlanRevisionId().toString());
        details.put("targetPlanRevisionId", change.targetPlanRevisionId().toString());
        details.put("targetPlanCode", target.planCode());
        details.put("targetQuotaPolicyId", target.quotaPolicyId().toString());
        details.put("effectiveAt", change.effectiveAt().toString());
        details.put("assignmentVersionBefore", assignmentVersion);
        details.put("servicePeriodStartsAt", locked.startsAt().toString());
        details.put("servicePeriodEndsAt", locked.endsAt() == null ? null : locked.endsAt().toString());
        return details;
    }

    /**
     * 通知意图的审计详情：时间点、应发时刻与所属服务期。
     *
     * @param state 订阅事实
     * @param kind 时间点
     * @param fireAt 应发时刻
     * @param intentId 新落库的意图 ID
     * @return 结构化详情
     */
    private Map<String, Object> notificationDetails(SubscriptionLifecycleState state,
                                                    SubscriptionNotificationKind kind,
                                                    Instant fireAt, UUID intentId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("intentId", intentId.toString());
        details.put("subscriptionId", state.id().toString());
        details.put("subscriptionStatus", state.status().name());
        details.put("kind", kind.name());
        details.put("kindDescription", kind.description());
        details.put("periodEndsAt", state.endsAt().toString());
        details.put("fireAt", fireAt.toString());
        details.put("delivery", "INTENT_ONLY_MAIL_LOGS");
        return details;
    }

    /** 一次受限切换的结果投影，用于累计计数。 */
    private record RestrictionOutcome(List<UUID> keptProjectIds, List<UUID> restrictedProjectIds) {
    }
}
