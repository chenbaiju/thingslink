package com.things.link.project.application;

import com.things.link.project.domain.PaymentProvider;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.ResourcePackagePurchase;
import com.things.link.project.domain.ResourcePackageSource;
import com.things.link.project.domain.ResourcePackageStatus;
import com.things.link.project.domain.SubscriptionLifecycleState;
import com.things.link.project.domain.SubscriptionStatus;
import com.things.link.project.domain.TenantOrder;
import com.things.link.project.domain.TenantOrderKind;
import com.things.link.project.domain.TenantOrderRepository;
import com.things.link.project.domain.TenantOrderStatus;
import com.things.link.project.domain.TenantResourcePackage;
import com.things.link.project.domain.TenantResourcePackageRepository;
import com.things.link.project.domain.TenantSubscriptionLifecycleRepository;
import com.things.link.project.domain.plan.ResourcePackageDimension;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 资源包购买与时间驱动的到期/待生效状态机（S14-4a，S14-0 P6）。
 *
 * <h2>与订阅完全解耦</h2>
 * 资源包是**独立权益**：本服务从不改 {@code sys_tenant_subscription} 的任何列。买包不会延长
 * 订阅的 {@code ends_at}，订阅续费/升级也不会延长任何包的 {@code ends_at}；每个包只有自己的
 * {@code starts_at/ends_at}（P6「不自动随基础订阅续期」「禁止隐式跟随当前订阅指针」）。
 *
 * <h2>模拟订单金额：目录没有包价格时的最小诚实约定</h2>
 * 负责人已确认支付不开发、目录只冻结了四档套餐的参考年价，**没有资源包价格**。因此包订单金额 =
 * 包额度 × {@link #PACKAGE_UNIT_REFERENCE_PRICE_CENTS}（冻结在本类的每单位参考价常量，1000 分），
 * 并始终以 {@code provider = SIMULATED} 与审计里的
 * {@code amountBasis = REFERENCE_PRICE_SIMULATED} / {@code priceBasis = PACKAGE_UNIT_REFERENCE_PRICE}
 * 标记为模拟：它不写任何 {@code price_cents}、不改销售状态。S14-5 接真实渠道时必须改读包的真实售价。
 *
 * <h2>支付成功在同一个事务里做的事</h2>
 * <ol>
 *   <li>锁订单行，判定重放还是可支付；</li>
 *   <li>标记订单 PAID（{@code WHERE status = 'CREATED'} 自身即幂等仲裁）；</li>
 *   <li>锁租户行（与订阅生效共用同一把锁与同一个策略绑定版本轴）；</li>
 *   <li>按订单上冻结的包快照写入一条资源包：订阅处于 {@code ACTIVE/GRACE} 则 {@code ACTIVE}，
 *       否则 {@code PENDING}（P6：订阅未生效时包待生效）；</li>
 *   <li>若包**此刻**就参与合成，复用既有 {@link QuotaPolicyAssignmentService} 的 CAS 与提交后
 *       缓存失效协议失效有效权益缓存——不新增第二套缓存或版本方案；</li>
 *   <li>写商业审计（下单 / 支付 / 生效或待生效三段）。</li>
 * </ol>
 * 任何一步失败整体回滚：不会出现「订单已 PAID 但没有包」或「包生效了但没有审计」。
 *
 * <h2>时间推进（advance）</h2>
 * 与 S14-3c 同一模式：{@link #advance(Instant)} 显式接收时刻，生产另留
 * {@link #advanceDueTransitions()} 走注入时钟，由 {@link SubscriptionLifecycleWorker} 在订阅推进成功后
 * 调用；沿用该维护任务的开关、调度与非商用模式围栏，不新增独立后台线程。测试 profile 显式关闭
 * 自动触发，避免与用例的受控时间轴争抢。两段推进都靠
 * {@code UPDATE ... WHERE status = ...} 自身幂等：重跑同一时刻三段计数全为 0。
 */
@Service
public class TenantResourcePackageService {

    /** 本片唯一渠道：模拟。真实渠道属 S14-5，落地时订单金额改读包的真实售价。 */
    private static final PaymentProvider PROVIDER = PaymentProvider.SIMULATED;

    /**
     * 模拟定价基准：每单位（每台设备/每条日消息/每个席位……）的冻结参考价，人民币分。
     *
     * <p>目录没有资源包价格，真实售价与销售策略最迟在 S14-5 前另行确认；在此之前只用一个
     * 可独立重算的常量，绝不从套餐年价里「折算」出一个看似真实的包价。
     */
    public static final long PACKAGE_UNIT_REFERENCE_PRICE_CENTS = 1_000L;

    /** P6 默认包周期：12 个 UTC 日历月。 */
    public static final int DEFAULT_PERIOD_MONTHS = 12;

    /** 单轮每类扫描的上限；把维护事务的规模钉死，避免一次处理全平台。 */
    static final int BATCH_LIMIT = 500;

    /** 渠道支付事件 ID 的最大长度，与 {@code sys_tenant_order.provider_event_id varchar(128)} 一致。 */
    private static final int MAX_PROVIDER_EVENT_ID_LENGTH = 128;

    /** 订单事实的读写端口（与套餐订单共用同一张表与同一套幂等键）。 */
    private final TenantOrderRepository orderRepository;
    /** 资源包事实的读写端口。 */
    private final TenantResourcePackageRepository packageRepository;
    /** 订阅生命周期读取入口（判定包 ACTIVE 还是 PENDING）与租户行锁。 */
    private final TenantSubscriptionLifecycleRepository subscriptionLifecycleRepository;
    /** S7 冻结的运行时策略 CAS 绑定用例；包变化必须复用它失效缓存。 */
    private final QuotaPolicyAssignmentService quotaPolicyAssignmentService;
    /** 商业审计写入（下单 / 支付 / 生效 / 待生效 / 到期）。 */
    private final AuditLogService auditLogService;
    /** 时间来源；生产固定 UTC，测试可注入固定时刻。 */
    private final Clock clock;

    /**
     * 生产构造器：时间取系统 UTC 时钟。
     *
     * @param orderRepository 订单事实读写端口
     * @param packageRepository 资源包事实读写端口
     * @param subscriptionLifecycleRepository 订阅生命周期读取与租户行锁
     * @param quotaPolicyAssignmentService S7 运行时策略 CAS 绑定用例
     * @param auditLogService 商业审计写入
     */
    @Autowired
    public TenantResourcePackageService(TenantOrderRepository orderRepository,
                                        TenantResourcePackageRepository packageRepository,
                                        TenantSubscriptionLifecycleRepository subscriptionLifecycleRepository,
                                        QuotaPolicyAssignmentService quotaPolicyAssignmentService,
                                        AuditLogService auditLogService) {
        this(orderRepository, packageRepository, subscriptionLifecycleRepository,
                quotaPolicyAssignmentService, auditLogService, Clock.systemUTC());
    }

    /**
     * 可注入时钟的构造器：真库用例用它把「支付时刻 / 到期时刻」冻结成固定 Instant。
     *
     * @param orderRepository 订单事实读写端口
     * @param packageRepository 资源包事实读写端口
     * @param subscriptionLifecycleRepository 订阅生命周期读取与租户行锁
     * @param quotaPolicyAssignmentService S7 运行时策略 CAS 绑定用例
     * @param auditLogService 商业审计写入
     * @param clock 时间来源
     */
    public TenantResourcePackageService(TenantOrderRepository orderRepository,
                                        TenantResourcePackageRepository packageRepository,
                                        TenantSubscriptionLifecycleRepository subscriptionLifecycleRepository,
                                        QuotaPolicyAssignmentService quotaPolicyAssignmentService,
                                        AuditLogService auditLogService,
                                        Clock clock) {
        this.orderRepository = orderRepository;
        this.packageRepository = packageRepository;
        this.subscriptionLifecycleRepository = subscriptionLifecycleRepository;
        this.quotaPolicyAssignmentService = quotaPolicyAssignmentService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    /**
     * 为一次资源包购买创建模拟订单：金额取每单位参考价基准，包快照一并冻结。
     *
     * <p>只接受 {@link ResourcePackageDimension} 登记且单位/窗口确定的维度；历史窗口的单位随
     * 档位冻结值（DAY/MONTH）变化，本片没有可用的无上下文读面来解析它，因此明确拒绝而不是猜一个单位。
     *
     * @param tenantId 归属租户
     * @param dimensionCode 目标冻结维度编码
     * @param amount 该包为维度增加的额度，必须为正
     * @param requestedStartsAt 指定未来起算时刻；{@code null} 表示支付成功时起算
     * @return 新建的 CREATED 包订单事实
     * @throws BusinessException 租户不存在、维度不可扩容、额度非正或起算时刻不是未来
     */
    @Transactional
    public TenantOrder createSimulatedPackageOrder(UUID tenantId, String dimensionCode, long amount,
                                                   Instant requestedStartsAt) {
        Objects.requireNonNull(tenantId, "租户 ID 不得为空");
        Objects.requireNonNull(dimensionCode, "资源包维度编码不得为空");
        if (!orderRepository.tenantExists(tenantId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "租户不存在");
        }
        ResourcePackageDimension dimension = ResourcePackageDimension.fromCode(dimensionCode)
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PACKAGE_DIMENSION_NOT_SUPPORTED));
        if (amount <= 0) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "资源包额度必须为正整数");
        }
        if (requestedStartsAt != null && !requestedStartsAt.isAfter(clock.instant())) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "资源包起算时刻必须是未来时刻");
        }
        if (dimension.fixedUnit() == null) {
            // 历史窗口的单位随基础档冻结值取 DAY 或 MONTH，本片无法在无租户上下文时安全解析。
            throw new BusinessException(ProjectErrorCode.PACKAGE_DIMENSION_NOT_SUPPORTED,
                    "历史窗口资源包暂不开放：其单位随档位冻结值变化");
        }
        ResourcePackagePurchase purchase = new ResourcePackagePurchase(dimensionCode, amount,
                dimension.fixedUnit(), dimension.window(), DEFAULT_PERIOD_MONTHS, requestedStartsAt);
        long amountCents;
        try {
            amountCents = Math.multiplyExact(amount, PACKAGE_UNIT_REFERENCE_PRICE_CENTS);
        } catch (ArithmeticException overflow) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "资源包参考金额超出64位整数范围");
        }
        UUID orderId = orderRepository.insertPackageOrder(tenantId, purchase, PROVIDER, amountCents, "CNY");
        TenantOrder order = orderRepository.findOrder(orderId).orElseThrow(() ->
                new IllegalStateException("资源包订单刚写入却读不回来: " + orderId));
        auditLogService.record(new AuditLogEntry(tenantId, null, null, "tenant_order", orderId,
                "commercial.order.created", orderDetails(order, false, null)));
        return order;
    }

    /**
     * 幂等地应用一次「资源包模拟支付成功」事件：同一事务写入包并在需要时失效有效权益缓存。
     *
     * @param orderId 待支付的资源包订单 ID
     * @param providerEventId 渠道支付事件 ID（幂等键）
     * @return 本次生效结果；同一事件重放时 {@code activated = false}
     * @throws BusinessException 订单不存在、不是包订单、状态不可支付，或事件已被其他订单占用
     */
    @Transactional
    public ResourcePackageActivation applySimulatedPackagePaymentSucceeded(UUID orderId,
                                                                           String providerEventId) {
        Objects.requireNonNull(orderId, "订单 ID 不得为空");
        String eventId = requireProviderEventId(providerEventId);

        TenantOrder order = orderRepository.lockOrder(orderId)
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.ORDER_NOT_FOUND));
        if (order.kind() != TenantOrderKind.PACKAGE) {
            throw new BusinessException(ProjectErrorCode.ORDER_KIND_MISMATCH);
        }
        if (order.status() == TenantOrderStatus.PAID) {
            if (eventId.equals(order.providerEventId())) {
                return packageRepository.findBySourceOrder(order.id())
                        .map(existing -> new ResourcePackageActivation(existing.id(), existing.startsAt(),
                                existing.endsAt(), existing.status(), false))
                        .orElseThrow(() -> new IllegalStateException(
                                "包订单已支付却没有对应资源包，事实不一致: " + order.id()));
            }
            throw new BusinessException(ProjectErrorCode.ORDER_NOT_PAYABLE,
                    "订单已支付，不能再用另一个支付事件重复支付");
        }
        if (order.status() == TenantOrderStatus.CANCELLED) {
            throw new BusinessException(ProjectErrorCode.ORDER_NOT_PAYABLE, "订单已取消，不能再支付");
        }
        // 同一支付事件已经落在别的订单上：渠道级幂等冲突，明确拒绝而不是静默丢弃。
        orderRepository.findByProviderEventId(order.provider(), eventId).ifPresent(existing -> {
            throw new BusinessException(ProjectErrorCode.ORDER_NOT_PAYABLE, "支付事件已被其他订单使用");
        });

        Instant paidAt;
        try {
            paidAt = orderRepository.markPaid(order.id(), order.provider(), eventId)
                    .orElseThrow(() -> new BusinessException(ProjectErrorCode.ORDER_NOT_PAYABLE,
                            "订单状态已被并发修改"));
        } catch (DuplicateKeyException conflict) {
            throw new BusinessException(ProjectErrorCode.ORDER_NOT_PAYABLE, "支付事件已被其他订单使用");
        }

        ResourcePackagePurchase purchase = order.packagePurchase();
        // 租户行锁与策略绑定版本：与订阅生效共用同一把锁、同一个版本轴，包的变化不会绕过既有失效协议。
        long assignmentVersion = subscriptionLifecycleRepository
                .lockTenantAndReadAssignmentVersion(order.tenantId());
        // 「是否未来起算」只比较**两个数据库时间**（购买快照 vs 支付时刻 paid_at），不拿 JVM 时钟去比较数据库时间。
        // 旧实现用 clock.instant() 与 starts_at（数据库 now()）比较：两个时钟相差几毫秒时，刚支付成功的包会被
        // 误判成「未来起算」，从而跳过一次缓存失效，只读/显示面继续用旧策略（D-180；S14-6b 已为读面记录过
        // 同一类跨时钟缺口，见 DatabaseTime 的类注释）。starts_at 要么显式取未来的购买快照，要么就是支付时刻本身，
        // 没有第三态，因此这里的布尔量与原判定的语义完全一致。
        boolean futureDated = purchase.startsAt() != null && purchase.startsAt().isAfter(paidAt);
        Instant startsAt = futureDated ? purchase.startsAt() : paidAt;
        Instant endsAt = startsAt.atZone(ZoneOffset.UTC)
                .plusMonths(purchase.periodMonths()).toInstant();
        boolean subscriptionEntitled = currentlyEntitled(order.tenantId());
        ResourcePackageStatus status = subscriptionEntitled
                ? ResourcePackageStatus.ACTIVE : ResourcePackageStatus.PENDING;

        UUID packageId = packageRepository.insert(order.tenantId(), purchase.dimensionCode(),
                purchase.amount(), purchase.unit(), purchase.window(), startsAt, endsAt,
                ResourcePackageSource.PURCHASE, order.id(), null, status);

        auditLogService.record(new AuditLogEntry(order.tenantId(), null, null, "tenant_order", order.id(),
                "commercial.order.paid", orderDetails(order, true, eventId)));
        auditLogService.record(new AuditLogEntry(order.tenantId(), null, null, "tenant_resource_package",
                packageId, subscriptionEntitled
                        ? "commercial.package.activated" : "commercial.package.pending",
                packageDetails(order, packageId, startsAt, endsAt, status, subscriptionEntitled, false)));

        // 只有「此刻已在窗口内」的 ACTIVE 包才改变当前有效值；未来起算的包不推进版本，避免无谓失效。
        if (status == ResourcePackageStatus.ACTIVE && !futureDated) {
            quotaPolicyAssignmentService.assign(order.tenantId(),
                    subscriptionLifecycleRepository.readPolicyId(order.tenantId()), assignmentVersion);
        }
        return new ResourcePackageActivation(packageId, startsAt, endsAt, status, true);
    }

    /**
     * 推进一轮资源包时间驱动状态机（生产入口；时间取注入时钟）。
     *
     * @return 本轮各类推进的计数
     */
    @Transactional
    public ResourcePackageAdvanceReport advanceDueTransitions() {
        return advance(clock.instant());
    }

    /**
     * 在指定时刻推进一轮资源包状态机（测试与运维显式驱动入口）。
     *
     * <p>两段：① 到期 {@code ACTIVE → EXPIRED}；② 待生效 {@code PENDING → ACTIVE}（仅当租户
     * 订阅处于 ACTIVE/GRACE 且包已到起点）。每条推进语句自身幂等；受影响租户去重后各复用一次
     * 既有缓存失效协议（不新增第二套版本方案）。候选只读扫描后，按 UUID 全序先锁全部关联租户，
     * 再以状态/时间 CAS 推进包；等待后重新确认待激活包的订阅资格，与退款及调整撤销统一租户→包锁序。
     *
     * @param now 当前时刻（UTC）
     * @return 本轮各类推进的计数
     */
    @Transactional
    public ResourcePackageAdvanceReport advance(Instant now) {
        Objects.requireNonNull(now, "推进时刻不得为空");
        var dueExpiry = packageRepository.findDueForExpiry(now, BATCH_LIMIT);
        var dueActivation = packageRepository.findDueForActivation(now, BATCH_LIMIT);
        var orderedTenants = new java.util.TreeSet<UUID>();
        dueExpiry.forEach(candidate -> orderedTenants.add(candidate.tenantId()));
        dueActivation.forEach(candidate -> orderedTenants.add(candidate.tenantId()));
        Map<UUID, Long> assignmentVersions = new LinkedHashMap<>();
        for (UUID tenantId : orderedTenants) {
            assignmentVersions.put(tenantId,
                    subscriptionLifecycleRepository.lockTenantAndReadAssignmentVersion(tenantId));
        }
        Set<UUID> affectedTenants = new LinkedHashSet<>();

        int expired = 0;
        for (TenantResourcePackage candidate : dueExpiry) {
            if (packageRepository.markExpired(candidate.id(), now)) {
                expired++;
                affectedTenants.add(candidate.tenantId());
                auditLogService.record(new AuditLogEntry(candidate.tenantId(), null, null,
                        "tenant_resource_package", candidate.id(), "commercial.package.expired",
                        packageLifecycleDetails(candidate, now)));
            }
        }

        int activatedPending = 0;
        for (TenantResourcePackage candidate : dueActivation) {
            if (currentlyEntitled(candidate.tenantId()) && packageRepository.activatePending(candidate.id(), now)) {
                activatedPending++;
                affectedTenants.add(candidate.tenantId());
                auditLogService.record(new AuditLogEntry(candidate.tenantId(), null, null,
                        "tenant_resource_package", candidate.id(), "commercial.package.activated",
                        packageLifecycleDetails(candidate, now)));
            }
        }

        for (UUID tenantId : affectedTenants) {
            quotaPolicyAssignmentService.assign(tenantId,
                    subscriptionLifecycleRepository.readPolicyId(tenantId), assignmentVersions.get(tenantId));
        }
        return new ResourcePackageAdvanceReport(expired, activatedPending, affectedTenants.size());
    }

    /**
     * 判断租户当前订阅是否允许包生效（P6：ACTIVE 或 GRACE）。
     *
     * @param tenantId 租户 ID
     * @return 当前订阅状态为 ACTIVE 或 GRACE 时返回 {@code true}
     */
    private boolean currentlyEntitled(UUID tenantId) {
        return subscriptionLifecycleRepository.findCurrentState(tenantId)
                .map(SubscriptionLifecycleState::status)
                .filter(status -> status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.GRACE)
                .isPresent();
    }

    /**
     * 校验并归一化支付事件 ID。
     *
     * @param providerEventId 原始事件 ID
     * @return 去空白后的事件 ID
     * @throws BusinessException 为空或超过列长度
     */
    private String requireProviderEventId(String providerEventId) {
        if (providerEventId == null || providerEventId.isBlank()) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "支付事件 ID 不得为空");
        }
        String normalized = providerEventId.trim();
        if (normalized.length() > MAX_PROVIDER_EVENT_ID_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "支付事件 ID 过长");
        }
        return normalized;
    }

    /**
     * 包订单事件的审计详情；金额口径写死在 details 里，杜绝把参考价基准读成真实成交价。
     *
     * @param order 包订单
     * @param paid 是否已支付
     * @param providerEventId 支付事件 ID；未支付时为 {@code null}
     * @return 结构化详情
     */
    private Map<String, Object> orderDetails(TenantOrder order, boolean paid, String providerEventId) {
        ResourcePackagePurchase purchase = order.packagePurchase();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("orderId", order.id().toString());
        details.put("orderKind", TenantOrderKind.PACKAGE.name());
        details.put("dimensionCode", purchase.dimensionCode());
        details.put("packageAmount", purchase.amount());
        details.put("unit", purchase.unit());
        details.put("window", purchase.window());
        details.put("periodMonths", purchase.periodMonths());
        details.put("requestedStartsAt", purchase.startsAt() == null ? null : purchase.startsAt().toString());
        details.put("provider", PROVIDER.name());
        details.put("amountCents", order.amountCents());
        details.put("currency", order.currency());
        details.put("amountBasis", "REFERENCE_PRICE_SIMULATED");
        details.put("priceBasis", "PACKAGE_UNIT_REFERENCE_PRICE");
        details.put("unitReferencePriceCents", PACKAGE_UNIT_REFERENCE_PRICE_CENTS);
        details.put("paid", paid);
        if (providerEventId != null) {
            details.put("providerEventId", providerEventId);
        }
        return details;
    }

    /**
     * 包生效/待生效事件的审计详情。
     *
     * @param order 已支付包订单
     * @param packageId 新包 ID
     * @param startsAt 包起点
     * @param endsAt 包终点
     * @param status 落库状态
     * @param subscriptionEntitled 订阅是否处于 ACTIVE/GRACE
     * @param promoted 是否由 PENDING 推进而来
     * @return 结构化详情
     */
    private Map<String, Object> packageDetails(TenantOrder order, UUID packageId, Instant startsAt,
                                               Instant endsAt, ResourcePackageStatus status,
                                               boolean subscriptionEntitled, boolean promoted) {
        ResourcePackagePurchase purchase = order.packagePurchase();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("packageId", packageId.toString());
        details.put("orderId", order.id().toString());
        details.put("dimensionCode", purchase.dimensionCode());
        details.put("amount", purchase.amount());
        details.put("unit", purchase.unit());
        details.put("window", purchase.window());
        details.put("startsAt", startsAt.toString());
        details.put("endsAt", endsAt.toString());
        details.put("status", status.name());
        details.put("source", ResourcePackageSource.PURCHASE.name());
        details.put("subscriptionEntitled", subscriptionEntitled);
        details.put("promotedFromPending", promoted);
        return details;
    }

    /**
     * 时间推进事件的审计详情。
     *
     * @param candidate 被推进的包
     * @param now 推进时刻
     * @return 结构化详情
     */
    private Map<String, Object> packageLifecycleDetails(TenantResourcePackage candidate, Instant now) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("packageId", candidate.id().toString());
        details.put("sourceOrderId", candidate.sourceOrderId() == null
                ? null : candidate.sourceOrderId().toString());
        details.put("dimensionCode", candidate.dimensionCode());
        details.put("amount", candidate.amount());
        details.put("unit", candidate.unit());
        details.put("window", candidate.window());
        details.put("startsAt", candidate.startsAt().toString());
        details.put("endsAt", candidate.endsAt().toString());
        details.put("advancedAt", now.toString());
        return details;
    }
}
