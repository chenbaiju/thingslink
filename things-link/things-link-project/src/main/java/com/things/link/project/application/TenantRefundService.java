package com.things.link.project.application;

import com.things.link.project.domain.PaymentProvider;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.RefundStatus;
import com.things.link.project.domain.ResourcePackageSource;
import com.things.link.project.domain.TenantOrder;
import com.things.link.project.domain.TenantOrderKind;
import com.things.link.project.domain.TenantOrderRepository;
import com.things.link.project.domain.TenantOrderStatus;
import com.things.link.project.domain.TenantRefund;
import com.things.link.project.domain.TenantRefundRepository;
import com.things.link.project.domain.TenantResourcePackage;
import com.things.link.project.domain.TenantResourcePackageRepository;
import com.things.link.project.domain.TenantSubscriptionLifecycleRepository;
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
import java.util.Optional;
import java.util.UUID;

/**
 * 资源包订单退款（S14-5a）：把「钱退回去了」写成资金事实，并同步收回来源权益。
 *
 * <h2>ADR0165：资源包分次结算、整包终止</h2>
 * <ul>
 *   <li>每笔金额不超过剩余可退金额；首次成功退款收回整包，后续补退只结算资金。
 *       不按退款比例猜测整数权益，订阅由ADR0166专用报价与执行器处理；</li>
 *   <li><b>只对资源包</b>：套餐订单的资金已经变成已生效的服务期，回滚订阅会与期间的续费/升级/
 *       预约降级冲突（{@link ProjectErrorCode#SUBSCRIPTION_ORDER_REFUND_NOT_SUPPORTED}，须走ADR0166报价执行器）。
 *       资源包的权益是独立窗口的加数，收回就是置 {@code REFUNDED}，语义干净。</li>
 * </ul>
 *
 * <h2>退款成功后同步发生什么</h2>
 * <ol>
 *   <li>持订单行锁，判定订单归属/种类/状态，并校验退款金额；</li>
 *   <li>按渠道退款流水号查重：同一流水号的相同重放直接返回既有事实，参数不同则显式冲突；</li>
 *   <li>CAS 累加订单退款金额（本次金额不得超过剩余额度），并发不能超退；</li>
 *   <li>写入 {@code sys_tenant_refund}（{@code SUCCEEDED}）；</li>
 *   <li>把来源包置 {@code REFUNDED}（不再参与有效权益合成），并复用既有
 *       {@link QuotaPolicyAssignmentService} CAS 失效有效权益缓存；</li>
 *   <li>写商业审计（金额、币种、渠道、流水号、原因、包前后状态）。</li>
 * </ol>
 * 任何一步失败整体回滚：不会出现「钱退了但权益还在」或「权益收了但没记退款」。
 *
 * <h2>订阅绝不被触碰</h2>
 * 本服务从不读写 {@code sys_tenant_subscription}：退款不是订阅事件，续费/升级/到期状态机不受影响。
 *
 * <h2>与缓存失效的关系</h2>
 * 只有「退款前确实在窗口内贡献额度」的包才需要失效缓存；{@code EXPIRED}/{@code CANCELLED} 的包
 * 本来就不贡献，退款只留资金事实，不推进策略版本（避免无谓失效）。
 */
@Service
public class TenantRefundService {

    /** 本片唯一渠道：模拟。真实渠道落地时新增取值并补齐渠道对账语义（D-172）。 */
    private static final PaymentProvider PROVIDER = PaymentProvider.SIMULATED;

    /** 渠道退款流水号最大长度，与 {@code sys_tenant_refund.provider_refund_id varchar(128)} 一致。 */
    private static final int MAX_PROVIDER_REFUND_ID_LENGTH = 128;

    /** 退款原因最大长度，与 {@code sys_tenant_refund.reason varchar(512)} 一致。 */
    private static final int MAX_REASON_LENGTH = 512;

    /** 订单事实读写端口（行锁、CAS 占金额）。 */
    private final TenantOrderRepository orderRepository;
    /** 退款事实读写端口。 */
    private final TenantRefundRepository refundRepository;
    /** 资源包事实端口（按来源订单定位包、置 {@code REFUNDED}）。 */
    private final TenantResourcePackageRepository packageRepository;
    /** 订阅生命周期读取（租户行锁与策略绑定版本轴）。 */
    private final TenantSubscriptionLifecycleRepository subscriptionLifecycleRepository;
    /** S7 冻结的运行时策略 CAS 绑定用例；权益收回必须复用它失效缓存。 */
    private final QuotaPolicyAssignmentService quotaPolicyAssignmentService;
    /** 商业审计写入。 */
    private final AuditLogService auditLogService;
    /** 时间来源；生产固定 UTC，测试可注入固定时刻。 */
    private final Clock clock;

    /**
     * 生产构造器：时间取系统 UTC 时钟。
     *
     * @param orderRepository 订单事实端口
     * @param refundRepository 退款事实端口
     * @param packageRepository 资源包事实端口
     * @param subscriptionLifecycleRepository 订阅读取与租户行锁
     * @param quotaPolicyAssignmentService S7 运行时策略 CAS 绑定用例
     * @param auditLogService 商业审计写入
     */
    @Autowired
    public TenantRefundService(TenantOrderRepository orderRepository,
                               TenantRefundRepository refundRepository,
                               TenantResourcePackageRepository packageRepository,
                               TenantSubscriptionLifecycleRepository subscriptionLifecycleRepository,
                               QuotaPolicyAssignmentService quotaPolicyAssignmentService,
                               AuditLogService auditLogService) {
        this(orderRepository, refundRepository, packageRepository, subscriptionLifecycleRepository,
                quotaPolicyAssignmentService, auditLogService, Clock.systemUTC());
    }

    /**
     * 可注入时钟的构造器：真库用例用它把退款时刻冻结成固定 Instant。
     *
     * @param orderRepository 订单事实端口
     * @param refundRepository 退款事实端口
     * @param packageRepository 资源包事实端口
     * @param subscriptionLifecycleRepository 订阅读取与租户行锁
     * @param quotaPolicyAssignmentService S7 运行时策略 CAS 绑定用例
     * @param auditLogService 商业审计写入
     * @param clock 时间来源
     */
    public TenantRefundService(TenantOrderRepository orderRepository,
                               TenantRefundRepository refundRepository,
                               TenantResourcePackageRepository packageRepository,
                               TenantSubscriptionLifecycleRepository subscriptionLifecycleRepository,
                               QuotaPolicyAssignmentService quotaPolicyAssignmentService,
                               AuditLogService auditLogService,
                               Clock clock) {
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
        this.packageRepository = packageRepository;
        this.subscriptionLifecycleRepository = subscriptionLifecycleRepository;
        this.quotaPolicyAssignmentService = quotaPolicyAssignmentService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    /**
     * 对已支付的资源包订单执行模拟结算，首次退款同步收回整包权益。
     *
     * @param tenantId 订单归属租户
     * @param orderId 待退款订单 ID
     * @param amountCents 退款金额（人民币分），必须为正且不超过剩余可退金额
     * @param reason 退款原因
     * @param providerRefundId 渠道退款流水号（幂等键）
     * @return 已写入或已存在的退款事实
     * @throws BusinessException 订单不存在或不属于该租户（404）、订单不可退款（50042）、
     *         金额非法或超剩余额度（50043）、订阅订单不支持退款（50044）、流水号冲突（50045）
     */
    @Transactional
    public TenantRefund refundPackageOrder(UUID tenantId, UUID orderId, long amountCents,
                                           String reason, String providerRefundId) {
        Objects.requireNonNull(tenantId, "租户 ID 不得为空");
        Objects.requireNonNull(orderId, "订单 ID 不得为空");
        String refundReason = requireReason(reason);
        String refundEventId = requireProviderRefundId(providerRefundId);
        if (amountCents <= 0) {
            throw new BusinessException(ProjectErrorCode.REFUND_AMOUNT_INVALID);
        }

        // 渠道级幂等：同一流水号的相同重放返回既有事实；参数不同则显式冲突（不静默换一笔）。
        Optional<TenantRefund> existing =
                refundRepository.findByProviderRefundId(PROVIDER, refundEventId);
        if (existing.isPresent()) {
            return replay(existing.get(), tenantId, orderId, amountCents);
        }

        // 订单行锁串行化同一订单的并发退款；跨租户传别人的订单 ID 与「不存在」同码，不做存在性探测。
        TenantOrder order = orderRepository.lockOrder(orderId)
                .filter(candidate -> candidate.tenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.ORDER_NOT_FOUND));
        // 等待订单锁期间同事件可能已提交，必须先恢复原事实再判断累计是否已满。
        existing = refundRepository.findByProviderRefundId(PROVIDER, refundEventId);
        if (existing.isPresent()) return replay(existing.get(), tenantId, orderId, amountCents);
        if (order.kind() != TenantOrderKind.PACKAGE) {
            throw new BusinessException(ProjectErrorCode.SUBSCRIPTION_ORDER_REFUND_NOT_SUPPORTED);
        }
        if (order.status() != TenantOrderStatus.PAID || order.refundedCents() == order.amountCents()) {
            throw new BusinessException(ProjectErrorCode.ORDER_NOT_REFUNDABLE);
        }
        if (amountCents > order.amountCents() - order.refundedCents()) {
            throw new BusinessException(ProjectErrorCode.REFUND_AMOUNT_INVALID);
        }

        // 订单→租户→包，与支付/生命周期串行；不得先改包后等待租户形成逆序。
        long assignmentVersion = subscriptionLifecycleRepository.lockTenantAndReadAssignmentVersion(tenantId);
        if (!orderRepository.reserveRefund(order.id(), amountCents)) {
            throw new BusinessException(ProjectErrorCode.ORDER_NOT_REFUNDABLE,
                    "订单已被并发退款占住金额");
        }

        // 已支付却找不到来源包属于事实损坏：资金事实与权益事实必须同生共死，明确失败而不是补一个包。
        TenantResourcePackage sourcePackage = packageRepository.findBySourceOrder(order.id())
                .orElseThrow(() -> new IllegalStateException(
                        "资源包订单已支付却没有对应资源包，事实不一致: " + order.id()));
        Instant now = clock.instant();
        // 「退款前它是否真的在贡献额度」必须用**数据库时间**判定（与合成函数同一基准）：拿 JVM 时钟比较
        // 数据库写入的窗口，两者相差几毫秒就会把在窗口内的包判成「还没起算」，于是包被置 REFUNDED
        // 却**跳过了缓存失效**，运行时继续按已收回的额度放行（D-180 的同类缺口）。
        boolean wasEffective = packageRepository.isCurrentlyEffective(sourcePackage.id());

        UUID refundId;
        try {
            refundId = refundRepository.insert(tenantId, order.id(), amountCents, order.currency(),
                    PROVIDER, refundEventId, RefundStatus.SUCCEEDED, refundReason);
        } catch (DuplicateKeyException conflict) {
            // 并发同流水号：数据库是最终仲裁者；本事务已失败无法回读，明确返回冲突让调用方重试，
            // 重试会走上面的幂等分支并收敛为「返回既有事实」。
            throw new BusinessException(ProjectErrorCode.REFUND_EVENT_CONFLICT,
                    "渠道退款流水号已被并发占用，请重试读取既有退款");
        }

        boolean packageRevoked = packageRepository.markRefunded(sourcePackage.id(), now);
        auditLogService.record(new AuditLogEntry(tenantId, null, null, "tenant_refund", refundId,
                "commercial.refund.succeeded",
                refundDetails(order, sourcePackage, refundId, amountCents, refundEventId, refundReason,
                        packageRevoked, wasEffective, now)));

        if (wasEffective) {
            // 复用既有 CAS 失效协议：包不再贡献，必须让运行时策略指针推进一次版本并失效缓存。
            quotaPolicyAssignmentService.assign(tenantId,
                    subscriptionLifecycleRepository.readPolicyId(tenantId), assignmentVersion);
        }
        return new TenantRefund(refundId, tenantId, order.id(), amountCents, order.currency(),
                PROVIDER, refundEventId, RefundStatus.SUCCEEDED, refundReason, now);
    }

    /**
     * 处理渠道退款流水号的幂等重放：同一笔退款的重复回调返回既有事实，参数不同则显式冲突。
     *
     * @param existing 已存在的退款事实
     * @param tenantId 本次请求的租户 ID
     * @param orderId 本次请求的订单 ID
     * @param amountCents 本次请求的金额
     * @return 既有退款事实
     * @throws BusinessException 同一流水号对应另一笔不同退款（50045）
     */
    private TenantRefund replay(TenantRefund existing, UUID tenantId, UUID orderId, long amountCents) {
        boolean identical = existing.tenantId().equals(tenantId)
                && existing.orderId().equals(orderId)
                && existing.amountCents() == amountCents;
        if (!identical) {
            throw new BusinessException(ProjectErrorCode.REFUND_EVENT_CONFLICT);
        }
        return existing;
    }

    /**
     * 校验退款原因。
     *
     * @param reason 原始原因
     * @return 去首尾空白后的原因
     * @throws BusinessException 为空或过长
     */
    private String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "退款原因不得为空");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "退款原因过长");
        }
        return trimmed;
    }

    /**
     * 校验渠道退款流水号。
     *
     * @param providerRefundId 原始流水号
     * @return 去空白后的流水号
     * @throws BusinessException 为空或过长
     */
    private String requireProviderRefundId(String providerRefundId) {
        if (providerRefundId == null || providerRefundId.isBlank()) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "渠道退款流水号不得为空");
        }
        String normalized = providerRefundId.trim();
        if (normalized.length() > MAX_PROVIDER_REFUND_ID_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "渠道退款流水号过长");
        }
        return normalized;
    }

    /**
     * 退款事件的审计详情；金额口径与权益后果都写死在 details 里。
     *
     * @param order 被退款订单
     * @param sourcePackage 被收回的来源包
     * @param refundId 退款行 ID
     * @param amountCents 退款金额
     * @param providerRefundId 渠道退款流水号
     * @param reason 退款原因
     * @param packageRevoked 本次是否确实把包置为 {@code REFUNDED}
     * @param wasEffective 退款前该包是否正在贡献额度
     * @param now 退款时刻
     * @return 结构化详情
     */
    private Map<String, Object> refundDetails(TenantOrder order, TenantResourcePackage sourcePackage,
                                              UUID refundId, long amountCents, String providerRefundId,
                                              String reason, boolean packageRevoked,
                                              boolean wasEffective, Instant now) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("refundId", refundId.toString());
        details.put("orderId", order.id().toString());
        details.put("orderKind", order.kind().name());
        details.put("amountCents", amountCents);
        details.put("orderAmountCents", order.amountCents());
        details.put("currency", order.currency());
        details.put("provider", PROVIDER.name());
        details.put("providerRefundId", providerRefundId);
        details.put("reason", reason);
        details.put("refundBasis", amountCents == order.amountCents()
                ? "FULL_REFUND_SIMULATED" : "PACKAGE_TERMINATION_SETTLEMENT_SIMULATED");
        long totalRefunded = Math.addExact(order.refundedCents(), amountCents);
        details.put("totalRefundedCents", totalRefunded);
        details.put("remainingRefundableCents", order.amountCents() - totalRefunded);
        details.put("packageId", sourcePackage.id().toString());
        details.put("packageDimensionCode", sourcePackage.dimensionCode());
        details.put("packageAmount", sourcePackage.amount());
        details.put("packageStatusBefore", sourcePackage.status().name());
        details.put("packageRevoked", packageRevoked);
        details.put("packageWasEffective", wasEffective);
        details.put("source", ResourcePackageSource.PURCHASE.name());
        details.put("refundedAt", now.toString());
        return details;
    }
}
