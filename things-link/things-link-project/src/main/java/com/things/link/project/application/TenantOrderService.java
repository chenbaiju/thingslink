package com.things.link.project.application;

import com.things.link.project.domain.ActiveSubscription;
import com.things.link.project.domain.PaymentProvider;
import com.things.link.project.domain.PlanPurchase;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.SubscriptionServicePeriod;
import com.things.link.project.domain.SubscriptionProvenanceRepository;
import com.things.link.project.domain.SubscriptionUpgradeProration;
import com.things.link.project.domain.TenantOrder;
import com.things.link.project.domain.TenantOrderKind;
import com.things.link.project.domain.TenantOrderRepository;
import com.things.link.project.domain.TenantOrderStatus;
import com.things.link.project.domain.TenantSubscriptionLifecycleRepository;
import com.things.link.project.domain.plan.ProductRevision1;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 模拟订单与订阅生效的事务应用服务（S14-3a；S14-3b 增加即时升级与折算）。
 *
 * <h2>为什么是模拟订单</h2>
 * 负责人已确认（S14-0 §7/§9）支付功能暂不开发：微信支付与支付宝只保留渠道，付费档
 * {@code sale_status = NOT_FOR_SALE}、{@code price_cents} 保持 NULL，目录上只有
 * {@code reference_price_cents} 这一份参考快照。因此本服务生成的订单金额**取参考价**
 * （升级订单取由参考日价折算出的补差），并始终以 {@link PaymentProvider#SIMULATED}
 * 标记为模拟：它不改变销售状态、不写成交价，只用来验证「支付成功 → 订阅生效」的状态机。
 * 这是对 V20260913_1050「参考价不得被订单逻辑读取」的一处显式且受限的偏离，
 * S14-5 接真实渠道时订单金额必须改读 {@code price_cents}。
 *
 * <h2>一笔支付成功事件在同一个事务里做的事</h2>
 * <ol>
 *   <li>锁订单行，判定这是重放还是可支付；</li>
 *   <li>标记订单 PAID（{@code WHERE status = 'CREATED'} 自身即幂等仲裁）；</li>
 *   <li>锁租户行，读出当前策略绑定版本并取得串行化锚点；</li>
 *   <li>把当前 ACTIVE 订阅置终态 {@code SUPERSEDED}，再写入新的 ACTIVE 订阅 ——
 *       整份购买用「首次购买 now → now + 周期；续费从上一 {@code endsAt} 延长」，
 *       升级用订单上冻结的「报价时刻 → 原 {@code endsAt}」；</li>
 *   <li>经 S7 的 {@link QuotaPolicyAssignmentService} 绑定新修订版的配额策略并推进分配版本；</li>
 *   <li>升级时同事务清除待生效的预约降级（P5：客户向上改主意，旧降级意图作废）；</li>
 *   <li>写商业审计（下单 / 支付 / 生效三段，系统动作 actor 为空）。</li>
 * </ol>
 * 任何一步失败整体回滚：不会出现「订单已 PAID 但订阅没生效」或「订阅生效了但没有审计」。
 *
 * <h2>幂等与并发</h2>
 * 同一 {@code providerEventId} 的重放直接返回既有订阅，不写第二行、不再推进版本、不重复审计；
 * 同一订单的并发支付由订单行锁串行化，两个并发调用恰好一个 {@code activated = true}；
 * 同租户两个订单的并发支付由租户行锁串行化，任一提交时刻最多一条 ACTIVE 订阅
 * （数据库侧另有部分唯一索引兜底）。
 *
 * <h2>升级的时间口径</h2>
 * 折算基准时刻是**下单报价时刻**（{@code Clock} 注入），不是支付时刻：订单金额在下单时冻结，
 * 支付只负责生效，若生效时用新的 {@code now} 重算，客户看到的价与订阅实际起算点会分叉。
 * 报价时刻同时成为新 ACTIVE 订阅的 {@code startsAt}，终点保持原 {@code endsAt} 不变，
 * 因此升级既不延长也不缩短已付服务期；若下单后当前订阅又变了，生效时以
 * {@link ProjectErrorCode#UPGRADE_QUOTE_STALE} 整体拒绝，而不是按旧价套用。
 */
@Service
public class TenantOrderService {

    /** 本片唯一渠道：模拟。真实渠道属 S14-5，落地时订单金额改读成交价。 */
    private static final PaymentProvider PROVIDER = PaymentProvider.SIMULATED;

    /** 渠道支付事件 ID 的最大长度，与 {@code sys_tenant_order.provider_event_id varchar(128)} 一致。 */
    private static final int MAX_PROVIDER_EVENT_ID_LENGTH = 128;

    /** 订单事实的读写端口。 */
    private final TenantOrderRepository orderRepository;
    /** 订阅生命周期的写入口（关旧、开新、读当前生效）。 */
    private final TenantSubscriptionLifecycleRepository subscriptionLifecycleRepository;
    /** S7 冻结的运行时策略 CAS 绑定用例；订阅生效必须复用它。 */
    private final QuotaPolicyAssignmentService quotaPolicyAssignmentService;
    /** 预约降级的清除入口；升级生效时同事务作废旧降级意图。 */
    private final TenantSubscriptionChangeService subscriptionChangeService;
    /** 商业审计写入（下单 / 支付 / 生效 / 取消）。 */
    private final AuditLogService auditLogService;
    /** 升级折算的基准时刻来源；生产固定 UTC，集成测试可冻结报价时刻。 */
    private final Clock clock;
    /** 与支付原事务共同提交的不可变来源。 */
    private final SubscriptionProvenanceRepository provenance;

    /**
     * 生产构造器：折算基准时刻取系统 UTC 时钟。
     *
     * @param orderRepository 订单事实的读写端口
     * @param subscriptionLifecycleRepository 订阅生命周期写入口
     * @param quotaPolicyAssignmentService S7 运行时策略 CAS 绑定用例
     * @param subscriptionChangeService 预约降级清除入口
     * @param provenance 原始来源快照写入
     * @param auditLogService 商业审计写入
     */
    @Autowired
    public TenantOrderService(TenantOrderRepository orderRepository,
                              TenantSubscriptionLifecycleRepository subscriptionLifecycleRepository,
                              QuotaPolicyAssignmentService quotaPolicyAssignmentService,
                              TenantSubscriptionChangeService subscriptionChangeService,
                              SubscriptionProvenanceRepository provenance,
                              AuditLogService auditLogService) {
        this(orderRepository, subscriptionLifecycleRepository, quotaPolicyAssignmentService,
                subscriptionChangeService, provenance, auditLogService, Clock.systemUTC());
    }

    /**
     * 可注入时钟的构造器：S14-3b 的真库集成测试用它把「升级报价时刻」冻结成固定 Instant，
     * 从而对首日/中点/末日与零补差给出可独立重算的精确金额。生产不使用这条路径。
     *
     * @param orderRepository 订单事实的读写端口
     * @param subscriptionLifecycleRepository 订阅生命周期写入口
     * @param quotaPolicyAssignmentService S7 运行时策略 CAS 绑定用例
     * @param subscriptionChangeService 预约降级清除入口
     * @param provenance 原始来源快照写入
     * @param auditLogService 商业审计写入
     * @param clock 升级折算的基准时刻来源
     */
    public TenantOrderService(TenantOrderRepository orderRepository,
                              TenantSubscriptionLifecycleRepository subscriptionLifecycleRepository,
                              QuotaPolicyAssignmentService quotaPolicyAssignmentService,
                              TenantSubscriptionChangeService subscriptionChangeService,
                              SubscriptionProvenanceRepository provenance,
                              AuditLogService auditLogService,
                              Clock clock) {
        this.orderRepository = orderRepository;
        this.subscriptionLifecycleRepository = subscriptionLifecycleRepository;
        this.quotaPolicyAssignmentService = quotaPolicyAssignmentService;
        this.subscriptionChangeService = subscriptionChangeService;
        this.auditLogService = auditLogService;
        this.clock = clock;
        this.provenance = provenance;
    }

    /**
     * 为指定产品修订版创建一条模拟订单，金额取该修订版的参考价并即刻快照。
     *
     * <p>FREE 被明确拒绝：它由注册自动开通，下单购买它只会把免费订阅换成另一份免费订阅，
     * 没有商业事实却会推进策略版本与审计。付费修订版必须带有参考价、配额模板与付费计费周期，
     * 否则无法表达「买多久、绑什么策略」，同样拒绝。
     *
     * @param tenantId 归属租户
     * @param planRevisionId 目标产品修订版
     * @return 新建的 CREATED 订单事实
     * @throws BusinessException 租户/修订版不存在，或该修订版不可下单
     */
    @Transactional
    public TenantOrder createSimulatedOrder(UUID tenantId, UUID planRevisionId) {
        Objects.requireNonNull(tenantId, "租户 ID 不得为空");
        Objects.requireNonNull(planRevisionId, "产品修订版 ID 不得为空");
        if (!orderRepository.tenantExists(tenantId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "租户不存在");
        }
        PlanPurchase purchase = orderRepository.findPlanPurchase(planRevisionId)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.RESOURCE_NOT_FOUND, "套餐修订版不存在"));
        requireOrderable(purchase);

        UUID orderId = orderRepository.insertOrder(tenantId, planRevisionId, PROVIDER,
                purchase.referencePriceCents(), purchase.currency());
        auditLogService.record(new AuditLogEntry(tenantId, null, null, "tenant_order", orderId,
                "commercial.order.created", purchaseDetails(purchase, orderId, false, null)));
        return orderRepository.findOrder(orderId).orElseThrow(() ->
                new IllegalStateException("订单刚写入却读不回来: " + orderId));
    }

    /**
     * 为一次**即时升级**创建模拟订单：金额是按下单报价时刻折算的补差，折算事实一并冻结。
     *
     * <p>准入与顺序：目标修订版必须可下单（付费、有参考价与配额模板）、必须严格高于当前档位
     * （按 {@code display_order} 判定）；当前必须有带服务期终点的 ACTIVE 订阅，且服务期尚未结束。
     * 长期 FREE（{@code endsAt IS NULL}）不走升级，它是 S14-3a 的首次购买。
     *
     * <p>计算出的补差为 0（甚至因差额为负被 0 截断）时**仍然落单**：档位确实要变、策略确实要
     * 重绑，商业事实必须留下；订单金额为 0 不是「没有发生」，审计里以 {@code zeroDifference}
     * 与 {@code differenceFlooredAtZero} 显式标注。
     *
     * @param tenantId 归属租户
     * @param targetRevisionId 目标（更高档）产品修订版
     * @return 新建的 CREATED 升级订单事实
     * @throws BusinessException 租户/修订版不存在、没有生效订阅、档位不是升级或服务期已结束
     */
    @Transactional
    public TenantOrder createSimulatedUpgradeOrder(UUID tenantId, UUID targetRevisionId) {
        Objects.requireNonNull(tenantId, "租户 ID 不得为空");
        Objects.requireNonNull(targetRevisionId, "目标产品修订版 ID 不得为空");
        if (!orderRepository.tenantExists(tenantId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "租户不存在");
        }
        PlanPurchase target = orderRepository.findPlanPurchase(targetRevisionId)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.RESOURCE_NOT_FOUND, "套餐修订版不存在"));
        requireOrderable(target);

        // 复用订阅生效的那把租户行锁：报价读到的 ACTIVE 订阅不会在报价过程中被并发生效换掉。
        subscriptionLifecycleRepository.lockTenantAndReadAssignmentVersion(tenantId);
        ActiveSubscription current = subscriptionLifecycleRepository.lockActiveSubscription(tenantId)
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.NO_ACTIVE_SUBSCRIPTION));
        if (current.endsAt() == null) {
            throw new BusinessException(ProjectErrorCode.SUBSCRIPTION_PERIOD_UNBOUNDED);
        }
        PlanPurchase source = orderRepository.findPlanPurchase(current.planRevisionId())
                .orElseThrow(() -> new IllegalStateException(
                        "当前订阅引用的产品修订版不存在: " + current.planRevisionId()));
        if (target.displayOrder() <= source.displayOrder()) {
            throw new BusinessException(ProjectErrorCode.NOT_AN_UPGRADE);
        }
        if (source.referencePriceCents() == null || target.referencePriceCents() == null) {
            throw new IllegalStateException("升级折算需要来源与目标修订版的参考价: "
                    + source.planCode() + " / " + target.planCode());
        }
        if (!source.currency().equals(target.currency())) {
            // P5 一律人民币分；跨币种折算需要汇率与舍入口径，不在本片臆造。
            throw new IllegalStateException("跨币种升级无法按分折算: "
                    + source.currency() + " → " + target.currency());
        }
        Instant quotedAt = clock.instant();
        if (!current.endsAt().isAfter(quotedAt)) {
            throw new BusinessException(ProjectErrorCode.SUBSCRIPTION_PERIOD_ENDED);
        }
        SubscriptionUpgradeProration proration = SubscriptionUpgradeProration.between(quotedAt,
                current.endsAt(), source.referencePriceCents(), target.referencePriceCents());
        UUID orderId = orderRepository.insertUpgradeOrder(tenantId, targetRevisionId,
                current.planRevisionId(), proration, PROVIDER, target.currency());
        TenantOrder order = orderRepository.findOrder(orderId).orElseThrow(() ->
                new IllegalStateException("升级订单刚写入却读不回来: " + orderId));
        auditLogService.record(new AuditLogEntry(tenantId, null, null, "tenant_order", orderId,
                "commercial.order.created",
                upgradeDetails(order, source, target, proration, false, null)));
        return order;
    }

    /**
     * 取消一条仍处于 CREATED 的模拟订单（保留行，不留删除）。
     *
     * @param orderId 订单 ID
     * @throws BusinessException 订单不存在或已不是 CREATED
     */
    @Transactional
    public void cancelSimulatedOrder(UUID orderId) {
        Objects.requireNonNull(orderId, "订单 ID 不得为空");
        TenantOrder order = orderRepository.lockOrder(orderId)
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.ORDER_NOT_FOUND));
        if (!orderRepository.cancelOrder(orderId)) {
            throw new BusinessException(ProjectErrorCode.ORDER_NOT_CANCELLABLE);
        }
        auditLogService.record(new AuditLogEntry(order.tenantId(), null, null, "tenant_order", orderId,
                "commercial.order.cancelled", Map.of(
                "provider", order.provider().name(),
                "amountCents", order.amountCents(),
                "currency", order.currency())));
    }

    /**
     * 幂等地应用一次「模拟支付成功」事件。
     *
     * @param orderId 待支付订单 ID
     * @param providerEventId 渠道支付事件 ID（幂等键）
     * @return 本次生效结果；同一事件重放时 {@code activated = false}
     * @throws BusinessException 订单不存在、状态不可支付，或事件已被其他订单占用
     */
    @Transactional
    public SubscriptionActivation applySimulatedPaymentSucceeded(UUID orderId, String providerEventId) {
        Objects.requireNonNull(orderId, "订单 ID 不得为空");
        String eventId = requireProviderEventId(providerEventId);

        TenantOrder order = orderRepository.lockOrder(orderId)
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.ORDER_NOT_FOUND));

        // 资源包订单由 TenantResourcePackageService 的包生效入口处理：那里写包而不是换订阅。
        // 两种订单共用订单表与支付幂等键，但生效目标不同，走错入口必须显式拒绝而不是猜种类。
        if (order.kind() == TenantOrderKind.PACKAGE) {
            throw new BusinessException(ProjectErrorCode.ORDER_KIND_MISMATCH,
                    "资源包订单必须通过资源包支付入口生效");
        }

        if (order.status() == TenantOrderStatus.PAID) {
            if (eventId.equals(order.providerEventId())) {
                // 同一事件的幂等重放：返回该订单此前已经生效出来的订阅，不写第二行、不再推进版本、不重复审计。
                return subscriptionLifecycleRepository.findBySourceOrder(order.id())
                        .map(subscription -> new SubscriptionActivation(subscription.id(),
                                subscription.startsAt(), subscription.endsAt(), false))
                        .orElseThrow(() -> new IllegalStateException(
                                "订单已支付却没有对应订阅，事实不一致: " + order.id()));
            }
            throw new BusinessException(ProjectErrorCode.ORDER_NOT_PAYABLE,
                    "订单已支付，不能再用另一个支付事件重复支付");
        }
        if (order.status() == TenantOrderStatus.CANCELLED) {
            throw new BusinessException(ProjectErrorCode.ORDER_NOT_PAYABLE, "订单已取消，不能再支付");
        }
        // 同一支付事件已经落在别的订单上：这是渠道级幂等冲突，明确拒绝而不是静默丢弃。
        // 先查只是快速失败，并发下的最终仲裁者是 sys_tenant_order_provider_event_uk。
        orderRepository.findByProviderEventId(order.provider(), eventId).ifPresent(existing -> {
            throw new BusinessException(ProjectErrorCode.ORDER_NOT_PAYABLE,
                    "支付事件已被其他订单使用");
        });

        Instant paidAt;
        try {
            paidAt = orderRepository.markPaid(order.id(), order.provider(), eventId)
                    .orElseThrow(() -> new BusinessException(ProjectErrorCode.ORDER_NOT_PAYABLE,
                            "订单状态已被并发修改"));
        } catch (DuplicateKeyException conflict) {
            throw new BusinessException(ProjectErrorCode.ORDER_NOT_PAYABLE, "支付事件已被其他订单使用");
        }

        // 租户行锁：同租户的并发生效在此串行化，保证「关旧 + 建新」不会交错出两条 ACTIVE。
        long assignmentVersion = subscriptionLifecycleRepository
                .lockTenantAndReadAssignmentVersion(order.tenantId());
        PlanPurchase purchase = orderRepository.findPlanPurchase(order.planRevisionId())
                .orElseThrow(() -> new IllegalStateException(
                        "订单引用的产品修订版不存在: " + order.planRevisionId()));
        // 下单时已校验过；支付路径再校验一次，防止目录被越权改动后带着空模板/空参考价继续生效。
        requireOrderable(purchase);
        if (order.kind() == TenantOrderKind.UPGRADE) {
            return applyUpgrade(order, purchase, eventId, assignmentVersion);
        }

        ActiveSubscription current = subscriptionLifecycleRepository
                .lockActiveSubscription(order.tenantId()).orElse(null);
        Instant previousEndsAt = current == null ? null : current.endsAt();
        SubscriptionServicePeriod period = SubscriptionServicePeriod.forOrder(
                paidAt, previousEndsAt, purchase.billingPeriod());

        if (current != null) {
            subscriptionLifecycleRepository.supersede(current.id());
        }
        UUID subscriptionId = subscriptionLifecycleRepository.activate(
                order.tenantId(), order.planRevisionId(), period.startsAt(), period.endsAt(),
                purchase.billingPeriod(), order.amountCents(), order.currency(), order.id());
        // 复用 S7 的 CAS 与提交后缓存失效协议；锁持有期间版本不变，因此每次真实生效恰好推进一次。
        quotaPolicyAssignmentService.assign(order.tenantId(), purchase.quotaPolicyId(), assignmentVersion);

        auditLogService.record(new AuditLogEntry(order.tenantId(), null, null, "tenant_order", order.id(),
                "commercial.order.paid", purchaseDetails(purchase, order.id(), true, eventId)));
        auditLogService.record(new AuditLogEntry(order.tenantId(), null, null, "tenant_subscription",
                subscriptionId, "commercial.subscription.activated",
                activationDetails(order, purchase, subscriptionId, period, current)));
        provenance.recordActivation(order.tenantId(), subscriptionId, current == null ? null : current.id());
        return new SubscriptionActivation(subscriptionId, period.startsAt(), period.endsAt(), true);
    }

    /**
     * 升级生效：只读订单上冻结的折算快照，换档位、绑策略、清除降级预约，但不改服务期终点。
     *
     * <p>先核对「当前 ACTIVE 订阅仍是报价时的那一条、且 {@code endsAt} 未变」：不一致说明
     * 下单之后发生了续费/升级，套用旧快照会覆盖新服务期，必须整体拒绝
     * （{@link ProjectErrorCode#UPGRADE_QUOTE_STALE}），而不是按旧价静默生效。
     *
     * @param order 已标记 PAID 的升级订单
     * @param target 目标修订版购买投影
     * @param providerEventId 本次支付事件 ID
     * @param assignmentVersion 租户行锁下读到的策略绑定版本
     * @return 生效结果；服务期为「报价时刻 → 原服务期终点」
     */
    private SubscriptionActivation applyUpgrade(TenantOrder order, PlanPurchase target,
                                                String providerEventId, long assignmentVersion) {
        SubscriptionUpgradeProration proration = order.proration();
        PlanPurchase source = orderRepository.findPlanPurchase(order.sourcePlanRevisionId())
                .orElseThrow(() -> new IllegalStateException(
                        "升级订单引用的来源修订版不存在: " + order.sourcePlanRevisionId()));
        ActiveSubscription current = subscriptionLifecycleRepository.lockActiveSubscription(order.tenantId())
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.NO_ACTIVE_SUBSCRIPTION));
        if (!current.planRevisionId().equals(order.sourcePlanRevisionId())
                || !proration.periodEndsAt().equals(current.endsAt())) {
            throw new BusinessException(ProjectErrorCode.UPGRADE_QUOTE_STALE);
        }

        subscriptionLifecycleRepository.supersede(current.id());
        UUID subscriptionId = subscriptionLifecycleRepository.activate(order.tenantId(),
                order.planRevisionId(), proration.effectiveAt(), proration.periodEndsAt(),
                target.billingPeriod(), order.amountCents(), order.currency(), order.id());
        quotaPolicyAssignmentService.assign(order.tenantId(), target.quotaPolicyId(), assignmentVersion);
        // 客户向上改主意：任何待生效的降级预约随升级作废，撤销留审计；同事务提交或回滚。
        boolean downgradeCancelled =
                subscriptionChangeService.cancelPendingForUpgrade(order.tenantId(), order.id());

        auditLogService.record(new AuditLogEntry(order.tenantId(), null, null, "tenant_order", order.id(),
                "commercial.order.paid",
                upgradeDetails(order, source, target, proration, true, providerEventId)));
        auditLogService.record(new AuditLogEntry(order.tenantId(), null, null, "tenant_subscription",
                subscriptionId, "commercial.subscription.upgraded",
                upgradeActivationDetails(order, target, subscriptionId, proration, current,
                        downgradeCancelled)));
        provenance.recordActivation(order.tenantId(), subscriptionId, current.id());
        return new SubscriptionActivation(subscriptionId, proration.effectiveAt(),
                proration.periodEndsAt(), true);
    }

    /**
     * 校验修订版可下单：拒绝 FREE，并要求参考价、配额模板与付费计费周期齐备。
     *
     * @param purchase 修订版购买投影
     * @throws BusinessException 档位不可下单
     */
    private void requireOrderable(PlanPurchase purchase) {
        if (ProductRevision1.FREE_PLAN_CODE.equals(purchase.planCode())) {
            throw new BusinessException(ProjectErrorCode.FREE_PLAN_NOT_ORDERABLE);
        }
        if (purchase.referencePriceCents() == null || purchase.quotaPolicyId() == null
                || "NONE".equals(purchase.billingPeriod())) {
            throw new BusinessException(ProjectErrorCode.PLAN_REVISION_NOT_ORDERABLE);
        }
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
     * 整份购买的订单事件审计详情；金额口径写死在 details 里，杜绝把参考价读成真实成交价。
     *
     * @param purchase 修订版购买投影
     * @param orderId 订单 ID
     * @param paid 是否已支付
     * @param providerEventId 支付事件 ID；未支付时为 {@code null}
     * @return 结构化详情
     */
    private Map<String, Object> purchaseDetails(PlanPurchase purchase, UUID orderId, boolean paid,
                                                String providerEventId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("orderId", orderId.toString());
        details.put("orderKind", TenantOrderKind.PURCHASE.name());
        details.put("planRevisionId", purchase.planRevisionId().toString());
        details.put("planCode", purchase.planCode());
        details.put("saleStatus", purchase.saleStatus());
        details.put("provider", PROVIDER.name());
        details.put("amountCents", purchase.referencePriceCents());
        details.put("currency", purchase.currency());
        details.put("amountBasis", "REFERENCE_PRICE_SIMULATED");
        details.put("paid", paid);
        if (providerEventId != null) {
            details.put("providerEventId", providerEventId);
        }
        return details;
    }

    /**
     * 升级订单事件的审计详情：金额口径 + P5 折算的每一步，全部可从年价与剩余天数独立重算。
     *
     * <p>{@code amountBasis} 与整份购买保持同一标记（模拟金额取参考价系），另以
     * {@code amountKind = PRORATION_DIFFERENCE} 说明这是补差而不是整份报价。
     *
     * @param order 升级订单
     * @param source 来源修订版购买投影
     * @param target 目标修订版购买投影
     * @param proration 折算快照
     * @param paid 是否已支付
     * @param providerEventId 支付事件 ID；未支付时为 {@code null}
     * @return 结构化详情
     */
    private Map<String, Object> upgradeDetails(TenantOrder order, PlanPurchase source,
                                               PlanPurchase target, SubscriptionUpgradeProration proration,
                                               boolean paid, String providerEventId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("orderId", order.id().toString());
        details.put("orderKind", TenantOrderKind.UPGRADE.name());
        details.put("planRevisionId", target.planRevisionId().toString());
        details.put("planCode", target.planCode());
        details.put("saleStatus", target.saleStatus());
        details.put("fromPlanRevisionId", source.planRevisionId().toString());
        details.put("fromPlanCode", source.planCode());
        details.put("fromDisplayOrder", source.displayOrder());
        details.put("targetDisplayOrder", target.displayOrder());
        details.put("provider", PROVIDER.name());
        details.put("amountCents", order.amountCents());
        details.put("currency", order.currency());
        details.put("amountBasis", "REFERENCE_PRICE_SIMULATED");
        details.put("amountKind", "PRORATION_DIFFERENCE");
        details.put("dailyPriceBasis", "ANNUAL_DIV_365_HALF_UP_CENTS");
        details.put("oldAnnualReferencePriceCents", source.referencePriceCents());
        details.put("newAnnualReferencePriceCents", target.referencePriceCents());
        details.put("prorationEffectiveAt", proration.effectiveAt().toString());
        details.put("prorationPeriodEndsAt", proration.periodEndsAt().toString());
        details.put("remainingDays", proration.remainingDays());
        details.put("oldDailyPriceCents", proration.oldDailyPriceCents());
        details.put("newDailyPriceCents", proration.newDailyPriceCents());
        details.put("creditCents", proration.creditCents());
        details.put("chargeCents", proration.chargeCents());
        details.put("differenceCents", proration.differenceCents());
        details.put("zeroDifference", proration.differenceCents() == 0L);
        details.put("differenceFlooredAtZero", proration.chargeCents() < proration.creditCents());
        details.put("paid", paid);
        if (providerEventId != null) {
            details.put("providerEventId", providerEventId);
        }
        return details;
    }

    /**
     * 生效事件的审计详情：服务期与「关旧开新」的对应关系。
     *
     * @param order 已支付订单
     * @param purchase 修订版购买投影
     * @param subscriptionId 新订阅 ID
     * @param period 计算结果
     * @param superseded 被取代的旧 ACTIVE 订阅；没有时为 {@code null}
     * @return 结构化详情
     */
    private Map<String, Object> activationDetails(TenantOrder order, PlanPurchase purchase,
                                                  UUID subscriptionId, SubscriptionServicePeriod period,
                                                  ActiveSubscription superseded) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("orderId", order.id().toString());
        details.put("orderKind", TenantOrderKind.PURCHASE.name());
        details.put("subscriptionId", subscriptionId.toString());
        details.put("planRevisionId", order.planRevisionId().toString());
        details.put("planCode", purchase.planCode());
        details.put("startsAt", period.startsAt().toString());
        details.put("endsAt", period.endsAt().toString());
        details.put("renewalFromPreviousEndsAt", superseded != null && superseded.endsAt() != null);
        details.put("supersededSubscriptionId",
                superseded == null ? null : superseded.id().toString());
        details.put("paidAt", order.paidAt() == null ? null : order.paidAt().toString());
        return details;
    }

    /**
     * 升级生效事件的审计详情：折算事实 + 「服务期终点未变」的证据 + 降级预约是否被清除。
     *
     * @param order 已支付升级订单
     * @param target 目标修订版购买投影
     * @param subscriptionId 新订阅 ID
     * @param proration 折算快照
     * @param superseded 被取代的旧 ACTIVE 订阅
     * @param downgradeCancelled 本次是否清除了待生效的降级预约
     * @return 结构化详情
     */
    private Map<String, Object> upgradeActivationDetails(TenantOrder order, PlanPurchase target,
                                                         UUID subscriptionId,
                                                         SubscriptionUpgradeProration proration,
                                                         ActiveSubscription superseded,
                                                         boolean downgradeCancelled) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("orderId", order.id().toString());
        details.put("orderKind", TenantOrderKind.UPGRADE.name());
        details.put("subscriptionId", subscriptionId.toString());
        details.put("planRevisionId", order.planRevisionId().toString());
        details.put("planCode", target.planCode());
        details.put("fromPlanRevisionId", superseded.planRevisionId().toString());
        details.put("supersededSubscriptionId", superseded.id().toString());
        details.put("startsAt", proration.effectiveAt().toString());
        details.put("endsAt", proration.periodEndsAt().toString());
        details.put("periodEndUnchanged", proration.periodEndsAt().equals(superseded.endsAt()));
        details.put("remainingDays", proration.remainingDays());
        details.put("oldDailyPriceCents", proration.oldDailyPriceCents());
        details.put("newDailyPriceCents", proration.newDailyPriceCents());
        details.put("creditCents", proration.creditCents());
        details.put("chargeCents", proration.chargeCents());
        details.put("differenceCents", proration.differenceCents());
        details.put("zeroDifference", proration.differenceCents() == 0L);
        details.put("differenceFlooredAtZero", proration.chargeCents() < proration.creditCents());
        details.put("pendingDowngradeCancelled", downgradeCancelled);
        details.put("paidAt", order.paidAt() == null ? null : order.paidAt().toString());
        return details;
    }
}
