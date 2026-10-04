package com.things.link.project.application;

import com.things.link.project.domain.OrderPaymentFailure;
import com.things.link.project.domain.OrderPaymentFailureRepository;
import com.things.link.project.domain.PaymentProvider;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.TenantOrder;
import com.things.link.project.domain.TenantOrderRepository;
import com.things.link.project.domain.TenantOrderStatus;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 支付失败隔离（S14-5b）：记录失败事实，且**不产生任何权益或状态后果**。
 *
 * <h2>失败是什么，不是什么</h2>
 * 失败是**一次尝试**的结果，不是订单的终态。因此本服务：
 * <ul>
 *   <li><b>不改订单状态</b>：订单保持 {@code CREATED}，客户可以换渠道/换卡重试，成功时仍走
 *       {@link TenantOrderService#applySimulatedPaymentSucceeded} 的同一条生效路径；</li>
 *   <li><b>不创建权益</b>：不建订阅、不建资源包、不推进配额绑定版本、不失效任何缓存；</li>
 *   <li><b>不触碰订阅</b>：不读不写 {@code sys_tenant_subscription}。</li>
 * </ul>
 *
 * <h2>与已结算订单矛盾的迟到失败：fail-closed</h2>
 * 已支付（或已取消）的订单收到失败回调说明渠道事件与本平台事实冲突。本片**显式拒绝**（50046）
 * 而不是静默记下：静默会让「订单 PAID」与「失败事实存在」同时成立，对账时无法回答哪一次是真的。
 * 拒绝本身不改变任何事实，也不影响已生效的订阅与权益——这正是「支付故障不得破坏既有订阅」。
 *
 * <h2>幂等</h2>
 * 按 {@code (provider, providerEventId)} 回读并比较完整候选（租户/订单/失败分类）：完全相同的重放
 * 返回既有事实，参数不同的复用同一事件 ID 显式冲突（50046）。数据库唯一索引是最终仲裁者。
 */
@Service
public class TenantPaymentFailureService {

    /** 本片唯一渠道：模拟。真实渠道落地时新增取值并补齐回调签名语义（D-172）。 */
    private static final PaymentProvider PROVIDER = PaymentProvider.SIMULATED;

    /** 渠道失败事件 ID 最大长度，与 {@code provider_event_id varchar(128)} 一致。 */
    private static final int MAX_EVENT_ID_LENGTH = 128;

    /** 失败分类形状，与 {@code sys_tenant_order_payment_failure_code_ck} 逐字一致。 */
    private static final Pattern FAILURE_CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");

    /** 允许的渠道时钟偏移：渠道时刻最多可比本平台时间早/晚这么多，超出即认为事件不可信。 */
    private static final Duration CLOCK_SKEW_TOLERANCE = Duration.ofMinutes(5);

    /** 失败事实读写端口。 */
    private final OrderPaymentFailureRepository failureRepository;
    /** 订单事实端口（只读锁定，用于确权与状态判定；失败绝不改订单）。 */
    private final TenantOrderRepository orderRepository;
    /** 商业审计写入。 */
    private final AuditLogService auditLogService;
    /** 时间来源；生产固定 UTC，测试可注入固定时刻。 */
    private final Clock clock;

    /**
     * 生产构造器：时间取系统 UTC 时钟。
     *
     * @param failureRepository 失败事实端口
     * @param orderRepository 订单事实端口
     * @param auditLogService 商业审计写入
     */
    @Autowired
    public TenantPaymentFailureService(OrderPaymentFailureRepository failureRepository,
                                       TenantOrderRepository orderRepository,
                                       AuditLogService auditLogService) {
        this(failureRepository, orderRepository, auditLogService, Clock.systemUTC());
    }

    /**
     * 可注入时钟的构造器：真库用例用它把渠道时刻冻结成固定 Instant。
     *
     * @param failureRepository 失败事实端口
     * @param orderRepository 订单事实端口
     * @param auditLogService 商业审计写入
     * @param clock 时间来源
     */
    public TenantPaymentFailureService(OrderPaymentFailureRepository failureRepository,
                                       TenantOrderRepository orderRepository,
                                       AuditLogService auditLogService,
                                       Clock clock) {
        this.failureRepository = failureRepository;
        this.orderRepository = orderRepository;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    /**
     * 记录一次模拟渠道的支付失败回调；只写失败事实，不改变订单、订阅或任何权益。
     *
     * @param tenantId 订单归属租户
     * @param orderId 失败订单 ID
     * @param failureCode 渠道失败分类（大写枚举风格）
     * @param providerEventId 渠道失败事件 ID（幂等键）
     * @param occurredAt 渠道报告的发生时刻；{@code null} 表示取平台当前时刻
     * @return 已写入或已存在的失败事实
     * @throws BusinessException 订单不存在或不属于该租户（404）、失败分类/事件 ID/时刻不合法（400）、
     *         与已结算订单矛盾或事件 ID 参数冲突（50046）
     */
    @Transactional
    public OrderPaymentFailure recordSimulatedPaymentFailure(UUID tenantId, UUID orderId,
                                                            String failureCode, String providerEventId,
                                                            Instant occurredAt) {
        Objects.requireNonNull(tenantId, "租户 ID 不得为空");
        Objects.requireNonNull(orderId, "订单 ID 不得为空");
        String code = requireFailureCode(failureCode);
        String eventId = requireEventId(providerEventId);
        Instant reportedAt = requireOccurredAt(occurredAt);

        // 渠道级幂等：同一事件 ID 的相同重放返回既有事实；参数不同则显式冲突。
        Optional<OrderPaymentFailure> existing =
                failureRepository.findByProviderEventId(PROVIDER, eventId);
        if (existing.isPresent()) {
            return replay(existing.get(), tenantId, orderId, code);
        }

        // 只读锁定订单即可：失败不改订单，无需与支付成功争抢状态；跨租户与未知订单同码。
        TenantOrder order = orderRepository.lockOrder(orderId)
                .filter(candidate -> candidate.tenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.ORDER_NOT_FOUND));
        if (order.status() != TenantOrderStatus.CREATED) {
            throw new BusinessException(ProjectErrorCode.PAYMENT_FAILURE_CONFLICT,
                    order.status() == TenantOrderStatus.PAID
                            ? "订单已支付成功，失败回调与之矛盾"
                            : "订单已取消，失败回调与之矛盾");
        }

        UUID failureId;
        try {
            failureId = failureRepository.insert(tenantId, order.id(), PROVIDER, eventId, code,
                    order.amountCents(), order.currency(), reportedAt);
        } catch (DuplicateKeyException conflict) {
            // 并发同事件：数据库是最终仲裁者；本事务已失败无法回读，明确返回冲突让调用方重试。
            throw new BusinessException(ProjectErrorCode.PAYMENT_FAILURE_CONFLICT,
                    "支付失败事件已被并发写入，请重试读取既有事实");
        }

        auditLogService.record(new AuditLogEntry(tenantId, null, null, "tenant_order_payment_failure",
                failureId, "commercial.payment.failed",
                failureDetails(order, failureId, code, eventId, reportedAt)));
        // 注意：这里刻意没有配额绑定、没有缓存失效、没有订阅写入——失败不产生任何权益后果。
        return new OrderPaymentFailure(failureId, tenantId, order.id(), PROVIDER, eventId, code,
                order.amountCents(), order.currency(), reportedAt, clock.instant());
    }

    /**
     * 处理失败事件 ID 的幂等重放：完全相同返回既有事实，参数不同显式冲突。
     *
     * @param existing 已存在的失败事实
     * @param tenantId 本次请求的租户 ID
     * @param orderId 本次请求的订单 ID
     * @param failureCode 本次请求的失败分类
     * @return 既有失败事实
     * @throws BusinessException 同一事件 ID 对应另一次不同的失败（50046）
     */
    private OrderPaymentFailure replay(OrderPaymentFailure existing, UUID tenantId, UUID orderId,
                                       String failureCode) {
        boolean identical = existing.tenantId().equals(tenantId)
                && existing.orderId().equals(orderId)
                && existing.failureCode().equals(failureCode);
        if (!identical) {
            throw new BusinessException(ProjectErrorCode.PAYMENT_FAILURE_CONFLICT);
        }
        return existing;
    }

    /**
     * 校验渠道失败分类。
     *
     * @param failureCode 渠道失败分类
     * @return 校验通过的分类
     * @throws BusinessException 缺失或形状不合法
     */
    private String requireFailureCode(String failureCode) {
        if (failureCode == null || !FAILURE_CODE_PATTERN.matcher(failureCode).matches()) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER,
                    "失败分类必须是大写枚举风格且长度 3～64");
        }
        return failureCode;
    }

    /**
     * 校验渠道失败事件 ID。
     *
     * @param providerEventId 渠道事件 ID
     * @return 去空白后的事件 ID
     * @throws BusinessException 为空或过长
     */
    private String requireEventId(String providerEventId) {
        if (providerEventId == null || providerEventId.isBlank()) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "支付失败事件 ID 不得为空");
        }
        String normalized = providerEventId.trim();
        if (normalized.length() > MAX_EVENT_ID_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "支付失败事件 ID 过长");
        }
        return normalized;
    }

    /**
     * 校验渠道报告的发生时刻：为空取平台当前时刻，明显未来的时刻直接拒绝。
     *
     * @param occurredAt 渠道报告时刻；可为 {@code null}
     * @return 用于落库的时刻
     * @throws BusinessException 时刻晚于平台时间超过允许偏移
     */
    private Instant requireOccurredAt(Instant occurredAt) {
        Instant now = clock.instant();
        if (occurredAt == null) {
            return now;
        }
        if (occurredAt.isAfter(now.plus(CLOCK_SKEW_TOLERANCE))) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER,
                    "支付失败发生时刻不得晚于平台时间超过 " + CLOCK_SKEW_TOLERANCE.toMinutes() + " 分钟");
        }
        return occurredAt;
    }

    /**
     * 失败事件的审计详情；明确写下「本次没有任何权益后果」。
     *
     * @param order 失败订单（状态必为 {@code CREATED}）
     * @param failureId 失败行 ID
     * @param failureCode 渠道失败分类
     * @param providerEventId 渠道事件 ID
     * @param occurredAt 渠道报告时刻
     * @return 结构化详情
     */
    private Map<String, Object> failureDetails(TenantOrder order, UUID failureId, String failureCode,
                                              String providerEventId, Instant occurredAt) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("failureId", failureId.toString());
        details.put("orderId", order.id().toString());
        details.put("orderKind", order.kind().name());
        details.put("orderStatus", order.status().name());
        details.put("provider", PROVIDER.name());
        details.put("providerEventId", providerEventId);
        details.put("failureCode", failureCode);
        details.put("amountCents", order.amountCents());
        details.put("currency", order.currency());
        details.put("occurredAt", occurredAt.toString());
        details.put("entitlementEffect", "NONE");
        details.put("orderStateChanged", false);
        return details;
    }
}
