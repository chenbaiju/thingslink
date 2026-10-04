package com.things.link.project.application;

import com.things.link.project.domain.ActiveSubscription;
import com.things.link.project.domain.PaymentProvider;
import com.things.link.project.domain.PlanPurchase;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.SubscriptionUpgradeProration;
import com.things.link.project.domain.TenantOrder;
import com.things.link.project.domain.TenantOrderKind;
import com.things.link.project.domain.TenantOrderRepository;
import com.things.link.project.domain.TenantOrderStatus;
import com.things.link.project.domain.TenantSubscriptionLifecycleRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S14-3a/S14-3b 模拟订单服务的判定单元测试。
 *
 * <p>只钉住服务内的准入、折算与幂等判定：FREE 与缺少参考价的修订版不得下单；同一支付事件重放是
 * 纯读操作；已支付/已取消订单必须拒绝；真实生效时的调用顺序是「关旧 → 开新 → 绑策略 → 审计」。
 * S14-3b 追加：档位方向判定、服务期已结束/无终点的拒绝、折算金额与快照、升级保持原服务期终点、
 * 报价过期拒绝与降级预约清除。原子性与并发收敛由 bootstrap 的真实 PostgreSQL 用例承担，
 * 这里不伪造数据库行为。
 */
@DisplayName("S14-3a/S14-3b 模拟订单服务判定")
class TenantOrderServiceTests {

    /** 订单归属租户。 */
    private final UUID tenantId = UUID.randomUUID();
    /** 整份购买的目标修订版（STANDARD）。 */
    private final UUID revisionId = UUID.randomUUID();
    /** 升级的来源修订版（STANDARD）。 */
    private final UUID standardRevisionId = UUID.randomUUID();
    /** 升级的目标修订版（ENTERPRISE）。 */
    private final UUID enterpriseRevisionId = UUID.randomUUID();
    /** 目标修订版绑定的配额模板。 */
    private final UUID policyId = UUID.randomUUID();
    /** 升级目标修订版绑定的配额模板。 */
    private final UUID enterprisePolicyId = UUID.randomUUID();
    /** 订单 ID。 */
    private final UUID orderId = UUID.randomUUID();
    /** 新建订阅 ID。 */
    private final UUID subscriptionId = UUID.randomUUID();
    /** 模拟支付成功时刻。 */
    private final Instant paidAt = Instant.parse("2026-03-31T08:30:00Z");
    /** 升级折算的冻结报价时刻（S14-3b）。 */
    private final Instant quotedAt = Instant.parse("2026-07-02T00:00:00Z");
    /** 升级前的原服务期终点。 */
    private final Instant periodEndsAt = Instant.parse("2027-01-01T00:00:00Z");

    /** 订单事实替身。 */
    private final TenantOrderRepository orders = mock(TenantOrderRepository.class);
    /** 订阅生命周期写入口替身。 */
    private final TenantSubscriptionLifecycleRepository lifecycle =
            mock(TenantSubscriptionLifecycleRepository.class);
    /** S7 绑定用例替身。 */
    private final QuotaPolicyAssignmentService assignment = mock(QuotaPolicyAssignmentService.class);
    /** 预约降级清除入口替身。 */
    private final TenantSubscriptionChangeService changes = mock(TenantSubscriptionChangeService.class);
    /** 商业审计替身。 */
    private final AuditLogService audit = mock(AuditLogService.class);
    /** 被测生产服务：报价时刻冻结在 {@link #quotedAt}。 */
    private final TenantOrderService service = new TenantOrderService(orders, lifecycle, assignment,
            changes, mock(com.things.link.project.domain.SubscriptionProvenanceRepository.class), audit, Clock.fixed(quotedAt, ZoneOffset.UTC));

    /** FREE 档由注册自动开通，不接受下单，且不写任何订单或审计行。 */
    @Test
    void refusesFreePlanOrder() {
        when(orders.tenantExists(tenantId)).thenReturn(true);
        when(orders.findPlanPurchase(revisionId)).thenReturn(Optional.of(
                new PlanPurchase(revisionId, "FREE", 10, "ON_SALE", "NONE", "CNY", 0L, policyId)));

        BusinessException refusal = catchThrowableOfType(
                () -> service.createSimulatedOrder(tenantId, revisionId), BusinessException.class);

        assertThat(refusal).isNotNull();
        assertThat(refusal.errorCode()).isEqualTo(ProjectErrorCode.FREE_PLAN_NOT_ORDERABLE);
        assertThat(refusal.errorCode().code()).isEqualTo(50021);
        verify(orders, never()).insertOrder(any(), any(), any(), anyLong(), any());
        verify(audit, never()).record(any());
    }

    /** 没有参考价/配额模板/付费周期的修订版无法表达订单，必须拒绝而不是编造金额。 */
    @Test
    void refusesRevisionWithoutReferencePriceOrQuotaTemplate() {
        when(orders.tenantExists(tenantId)).thenReturn(true);
        when(orders.findPlanPurchase(revisionId)).thenReturn(Optional.of(
                new PlanPurchase(revisionId, "STANDARD", 20, "NOT_FOR_SALE", "YEAR", "CNY", null, policyId)));

        BusinessException refusal = catchThrowableOfType(
                () -> service.createSimulatedOrder(tenantId, revisionId), BusinessException.class);

        assertThat(refusal).isNotNull();
        assertThat(refusal.errorCode()).isEqualTo(ProjectErrorCode.PLAN_REVISION_NOT_ORDERABLE);
        assertThat(refusal.errorCode().code()).isEqualTo(50022);
        verify(orders, never()).insertOrder(any(), any(), any(), anyLong(), any());
    }

    /** 付费档模拟订单的金额快照必须等于修订版参考价，并明确以 SIMULATED 渠道落库。 */
    @Test
    void createsSimulatedOrderWithReferencePriceSnapshot() {
        when(orders.tenantExists(tenantId)).thenReturn(true);
        when(orders.findPlanPurchase(revisionId)).thenReturn(Optional.of(standardPurchase()));
        when(orders.insertOrder(tenantId, revisionId, PaymentProvider.SIMULATED, 298000L, "CNY"))
                .thenReturn(orderId);
        when(orders.findOrder(orderId)).thenReturn(Optional.of(
                order(TenantOrderStatus.CREATED, null, null)));

        TenantOrder created = service.createSimulatedOrder(tenantId, revisionId);

        assertThat(created.id()).isEqualTo(orderId);
        assertThat(created.amountCents()).isEqualTo(298000L);
        assertThat(created.currency()).isEqualTo("CNY");
        verify(orders).insertOrder(tenantId, revisionId, PaymentProvider.SIMULATED, 298000L, "CNY");

        ArgumentCaptor<AuditLogEntry> entry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo("commercial.order.created");
        assertThat(entry.getValue().actorAccountId())
                .as("系统动作没有人类 actor")
                .isNull();
        assertThat(entry.getValue().details().get("amountBasis"))
                .as("审计必须写明金额取模拟参考价，不能被读成真实成交价")
                .isEqualTo("REFERENCE_PRICE_SIMULATED");
    }

    /** 同一支付事件的重放是纯读：不标记支付、不关旧、不开新、不绑策略、不重复审计。 */
    @Test
    void replaysSameProviderEventWithoutTouchingState() {
        TenantOrder paidOrder = order(TenantOrderStatus.PAID, "evt-1", paidAt);
        when(orders.lockOrder(orderId)).thenReturn(Optional.of(paidOrder));
        when(lifecycle.findBySourceOrder(orderId)).thenReturn(Optional.of(
                new ActiveSubscription(subscriptionId, revisionId, paidAt,
                        Instant.parse("2027-03-31T08:30:00Z"))));

        SubscriptionActivation outcome = service.applySimulatedPaymentSucceeded(orderId, "evt-1");

        assertThat(outcome.activated()).isFalse();
        assertThat(outcome.subscriptionId()).isEqualTo(subscriptionId);
        verify(orders, never()).markPaid(any(), any(), any());
        verify(lifecycle, never()).supersede(any());
        verify(lifecycle, never()).activate(any(), any(), any(), any(), any(), anyLong(), any(), any());
        verify(assignment, never()).assign(any(), any(), anyLong());
        verify(audit, never()).record(any());
    }

    /** 已支付订单收到另一个支付事件必须拒绝，避免同一订单被重复生效。 */
    @Test
    void refusesSecondProviderEventOnPaidOrder() {
        when(orders.lockOrder(orderId)).thenReturn(Optional.of(
                order(TenantOrderStatus.PAID, "evt-1", paidAt)));

        BusinessException refusal = catchThrowableOfType(
                () -> service.applySimulatedPaymentSucceeded(orderId, "evt-2"), BusinessException.class);

        assertThat(refusal).isNotNull();
        assertThat(refusal.errorCode()).isEqualTo(ProjectErrorCode.ORDER_NOT_PAYABLE);
        verify(orders, never()).markPaid(any(), any(), any());
    }

    /** 已取消订单不可支付。 */
    @Test
    void refusesCancelledOrderPayment() {
        when(orders.lockOrder(orderId)).thenReturn(Optional.of(
                order(TenantOrderStatus.CANCELLED, null, null)));

        BusinessException refusal = catchThrowableOfType(
                () -> service.applySimulatedPaymentSucceeded(orderId, "evt-1"), BusinessException.class);

        assertThat(refusal).isNotNull();
        assertThat(refusal.errorCode()).isEqualTo(ProjectErrorCode.ORDER_NOT_PAYABLE);
    }

    /** 未知订单返回已登记的 50024。 */
    @Test
    void refusesUnknownOrder() {
        when(orders.lockOrder(orderId)).thenReturn(Optional.empty());

        BusinessException refusal = catchThrowableOfType(
                () -> service.applySimulatedPaymentSucceeded(orderId, "evt-1"), BusinessException.class);

        assertThat(refusal).isNotNull();
        assertThat(refusal.errorCode()).isEqualTo(ProjectErrorCode.ORDER_NOT_FOUND);
        assertThat(refusal.errorCode().code()).isEqualTo(50024);
    }

    /** 真实生效的调用顺序：先取租户版本与当前订阅，再关旧、开新、绑策略，最后写支付与生效审计。 */
    @Test
    void activationSupersedesCurrentSubscriptionAndBindsRevisionPolicy() {
        UUID freeSubscriptionId = UUID.randomUUID();
        when(orders.lockOrder(orderId)).thenReturn(Optional.of(
                order(TenantOrderStatus.CREATED, null, null)));
        when(orders.markPaid(orderId, PaymentProvider.SIMULATED, "evt-1")).thenReturn(Optional.of(paidAt));
        when(lifecycle.lockTenantAndReadAssignmentVersion(tenantId)).thenReturn(2L);
        when(orders.findPlanPurchase(revisionId)).thenReturn(Optional.of(standardPurchase()));
        when(lifecycle.lockActiveSubscription(tenantId)).thenReturn(Optional.of(
                new ActiveSubscription(freeSubscriptionId, UUID.randomUUID(),
                        Instant.parse("2026-01-01T00:00:00Z"), null)));
        when(lifecycle.activate(tenantId, revisionId, paidAt,
                Instant.parse("2027-03-31T08:30:00Z"), "YEAR", 298000L, "CNY", orderId))
                .thenReturn(subscriptionId);

        SubscriptionActivation outcome = service.applySimulatedPaymentSucceeded(orderId, "evt-1");

        assertThat(outcome.activated()).isTrue();
        assertThat(outcome.subscriptionId()).isEqualTo(subscriptionId);
        assertThat(outcome.startsAt()).isEqualTo(paidAt);
        assertThat(outcome.endsAt()).isEqualTo(Instant.parse("2027-03-31T08:30:00Z"));
        verify(lifecycle).supersede(freeSubscriptionId);
        verify(assignment).assign(tenantId, policyId, 2L);

        ArgumentCaptor<AuditLogEntry> entries = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(audit, org.mockito.Mockito.times(2)).record(entries.capture());
        assertThat(entries.getAllValues()).extracting(AuditLogEntry::action)
                .containsExactly("commercial.order.paid", "commercial.subscription.activated");
    }

    /** 续费从上一到期日延长：起止时刻都由上一订阅决定，与支付时刻无关。 */
    @Test
    void renewalExtendsFromPreviousEndsAtNotFromPaidAt() {
        UUID previousSubscriptionId = UUID.randomUUID();
        Instant previousEndsAt = Instant.parse("2027-01-15T00:00:00Z");
        when(orders.lockOrder(orderId)).thenReturn(Optional.of(
                order(TenantOrderStatus.CREATED, null, null)));
        when(orders.markPaid(orderId, PaymentProvider.SIMULATED, "evt-1")).thenReturn(Optional.of(paidAt));
        when(lifecycle.lockTenantAndReadAssignmentVersion(tenantId)).thenReturn(3L);
        when(orders.findPlanPurchase(revisionId)).thenReturn(Optional.of(standardPurchase()));
        when(lifecycle.lockActiveSubscription(tenantId)).thenReturn(Optional.of(
                new ActiveSubscription(previousSubscriptionId, revisionId,
                        Instant.parse("2026-01-15T00:00:00Z"), previousEndsAt)));
        when(lifecycle.activate(tenantId, revisionId, previousEndsAt,
                Instant.parse("2028-01-15T00:00:00Z"), "YEAR", 298000L, "CNY", orderId))
                .thenReturn(subscriptionId);

        SubscriptionActivation outcome = service.applySimulatedPaymentSucceeded(orderId, "evt-1");

        assertThat(outcome.startsAt()).isEqualTo(previousEndsAt);
        assertThat(outcome.endsAt()).isEqualTo(Instant.parse("2028-01-15T00:00:00Z"));
        verify(lifecycle).supersede(previousSubscriptionId);
    }

    /** 同档或更低档位不得走即时升级：档位方向按 display_order 判定，不按名称或价格。 */
    @Test
    void refusesUpgradeToSameOrLowerTier() {
        stubUpgradeQuote(20, periodEndsAt);

        BusinessException refusal = catchThrowableOfType(
                () -> service.createSimulatedUpgradeOrder(tenantId, enterpriseRevisionId),
                BusinessException.class);

        assertThat(refusal).isNotNull();
        assertThat(refusal.errorCode()).isEqualTo(ProjectErrorCode.NOT_AN_UPGRADE);
        assertThat(refusal.errorCode().code()).isEqualTo(50026);
        verify(orders, never()).insertUpgradeOrder(any(), any(), any(), any(), any(), any());
    }

    /** 当前订阅没有服务期终点（长期 FREE）：升级无从折算，必须拒绝并指向首次购买。 */
    @Test
    void refusesUpgradeWithoutBoundedPeriod() {
        when(orders.tenantExists(tenantId)).thenReturn(true);
        when(orders.findPlanPurchase(enterpriseRevisionId)).thenReturn(Optional.of(enterprisePurchase()));
        when(lifecycle.lockActiveSubscription(tenantId)).thenReturn(Optional.of(
                new ActiveSubscription(UUID.randomUUID(), standardRevisionId,
                        Instant.parse("2026-01-01T00:00:00Z"), null)));

        BusinessException refusal = catchThrowableOfType(
                () -> service.createSimulatedUpgradeOrder(tenantId, enterpriseRevisionId),
                BusinessException.class);

        assertThat(refusal).isNotNull();
        assertThat(refusal.errorCode()).isEqualTo(ProjectErrorCode.SUBSCRIPTION_PERIOD_UNBOUNDED);
        assertThat(refusal.errorCode().code()).isEqualTo(50030);
    }

    /** 服务期已结束（或恰好此刻结束）不能按整日折算升级。 */
    @Test
    void refusesUpgradeWhenPeriodAlreadyEnded() {
        stubUpgradeQuote(30, quotedAt);

        BusinessException refusal = catchThrowableOfType(
                () -> service.createSimulatedUpgradeOrder(tenantId, enterpriseRevisionId),
                BusinessException.class);

        assertThat(refusal).isNotNull();
        assertThat(refusal.errorCode()).isEqualTo(ProjectErrorCode.SUBSCRIPTION_PERIOD_ENDED);
        assertThat(refusal.errorCode().code()).isEqualTo(50031);
        verify(orders, never()).insertUpgradeOrder(any(), any(), any(), any(), any(), any());
    }

    /**
     * 升级报价冻结 P5 折算结果：中点 183 天，STANDARD 日价 816、ENTERPRISE 日价 1638，
     * 抵扣 149328、应收 299754、补差 150426。
     */
    @Test
    void createsUpgradeOrderWithFrozenProrationDifference() {
        stubUpgradeQuote(30, periodEndsAt);
        SubscriptionUpgradeProration expected = SubscriptionUpgradeProration.between(
                quotedAt, periodEndsAt, 298000L, 598000L);
        when(orders.insertUpgradeOrder(tenantId, enterpriseRevisionId, standardRevisionId,
                expected, PaymentProvider.SIMULATED, "CNY")).thenReturn(orderId);
        when(orders.findOrder(orderId)).thenReturn(Optional.of(upgradeOrder(
                TenantOrderStatus.CREATED, null, null)));

        TenantOrder created = service.createSimulatedUpgradeOrder(tenantId, enterpriseRevisionId);

        assertThat(created.kind()).isEqualTo(TenantOrderKind.UPGRADE);
        assertThat(created.amountCents())
                .as("补差 = 1638 x 183 - 816 x 183 = 150426 分")
                .isEqualTo(150426L);
        assertThat(created.proration().remainingDays()).isEqualTo(183);
        assertThat(created.proration().periodEndsAt())
                .as("升级不得改动已付服务期终点")
                .isEqualTo(periodEndsAt);

        ArgumentCaptor<AuditLogEntry> entry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo("commercial.order.created");
        assertThat(details(entry.getValue()))
                .containsEntry("amountKind", "PRORATION_DIFFERENCE")
                .containsEntry("remainingDays", 183)
                .containsEntry("oldDailyPriceCents", 816L)
                .containsEntry("newDailyPriceCents", 1638L)
                .containsEntry("creditCents", 149328L)
                .containsEntry("chargeCents", 299754L)
                .containsEntry("zeroDifference", false);
    }

    /**
     * 升级生效：服务期起点取冻结报价时刻、终点保持原到期日，绑目标修订版策略，
     * 并同事务清除待生效的降级预约。
     */
    @Test
    void upgradeActivationKeepsOriginalPeriodEndAndClearsPendingDowngrade() {
        UUID previousSubscriptionId = UUID.randomUUID();
        when(orders.lockOrder(orderId)).thenReturn(Optional.of(
                upgradeOrder(TenantOrderStatus.CREATED, null, null)));
        when(orders.markPaid(orderId, PaymentProvider.SIMULATED, "evt-upgrade"))
                .thenReturn(Optional.of(paidAt));
        when(lifecycle.lockTenantAndReadAssignmentVersion(tenantId)).thenReturn(4L);
        when(orders.findPlanPurchase(enterpriseRevisionId)).thenReturn(Optional.of(enterprisePurchase()));
        when(orders.findPlanPurchase(standardRevisionId)).thenReturn(Optional.of(standardPurchase()));
        when(lifecycle.lockActiveSubscription(tenantId)).thenReturn(Optional.of(
                new ActiveSubscription(previousSubscriptionId, standardRevisionId,
                        Instant.parse("2026-01-01T00:00:00Z"), periodEndsAt)));
        when(lifecycle.activate(tenantId, enterpriseRevisionId, quotedAt, periodEndsAt,
                "YEAR", 150426L, "CNY", orderId)).thenReturn(subscriptionId);
        when(changes.cancelPendingForUpgrade(tenantId, orderId)).thenReturn(true);

        SubscriptionActivation outcome =
                service.applySimulatedPaymentSucceeded(orderId, "evt-upgrade");

        assertThat(outcome.activated()).isTrue();
        assertThat(outcome.startsAt()).isEqualTo(quotedAt);
        assertThat(outcome.endsAt())
                .as("升级后服务期终点必须仍是原到期日")
                .isEqualTo(periodEndsAt);
        verify(lifecycle).supersede(previousSubscriptionId);
        verify(assignment).assign(tenantId, enterprisePolicyId, 4L);
        verify(changes).cancelPendingForUpgrade(tenantId, orderId);

        ArgumentCaptor<AuditLogEntry> entries = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(audit, org.mockito.Mockito.times(2)).record(entries.capture());
        assertThat(entries.getAllValues()).extracting(AuditLogEntry::action)
                .containsExactly("commercial.order.paid", "commercial.subscription.upgraded");
        assertThat(details(entries.getAllValues().get(1)))
                .containsEntry("periodEndUnchanged", true)
                .containsEntry("pendingDowngradeCancelled", true)
                .containsEntry("differenceCents", 150426L);
    }

    /** 报价之后当前订阅又变了：必须以 50032 整体拒绝，不得按旧快照覆盖新服务期。 */
    @Test
    void refusesStaleUpgradeQuote() {
        UUID previousSubscriptionId = UUID.randomUUID();
        when(orders.lockOrder(orderId)).thenReturn(Optional.of(
                upgradeOrder(TenantOrderStatus.CREATED, null, null)));
        when(orders.markPaid(orderId, PaymentProvider.SIMULATED, "evt-stale"))
                .thenReturn(Optional.of(paidAt));
        when(lifecycle.lockTenantAndReadAssignmentVersion(tenantId)).thenReturn(4L);
        when(orders.findPlanPurchase(enterpriseRevisionId)).thenReturn(Optional.of(enterprisePurchase()));
        when(orders.findPlanPurchase(standardRevisionId)).thenReturn(Optional.of(standardPurchase()));
        when(lifecycle.lockActiveSubscription(tenantId)).thenReturn(Optional.of(
                new ActiveSubscription(previousSubscriptionId, standardRevisionId,
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2028-01-01T00:00:00Z"))));

        BusinessException refusal = catchThrowableOfType(
                () -> service.applySimulatedPaymentSucceeded(orderId, "evt-stale"),
                BusinessException.class);

        assertThat(refusal).isNotNull();
        assertThat(refusal.errorCode()).isEqualTo(ProjectErrorCode.UPGRADE_QUOTE_STALE);
        assertThat(refusal.errorCode().code()).isEqualTo(50032);
        verify(lifecycle, never()).supersede(any());
        verify(lifecycle, never()).activate(any(), any(), any(), any(), any(), anyLong(), any(), any());
    }

    /**
     * 把 {@code Map<String, ?>} 形态的审计详情取成断言可用的值类型。
     *
     * @param entry 审计事件
     * @return 结构化详情
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(AuditLogEntry entry) {
        return (Map<String, Object>) entry.details();
    }

    /**
     * 打桩一次升级报价读取：来源固定 STANDARD（展示顺序 20），目标档位顺序与服务期终点由调用方给定。
     *
     * @param targetDisplayOrder 目标修订版的展示顺序；20 表示同档（不是升级）
     * @param endsAt 当前订阅服务期终点
     */
    private void stubUpgradeQuote(int targetDisplayOrder, Instant endsAt) {
        when(orders.tenantExists(tenantId)).thenReturn(true);
        when(orders.findPlanPurchase(enterpriseRevisionId)).thenReturn(Optional.of(
                targetDisplayOrder == 20
                        ? new PlanPurchase(enterpriseRevisionId, "STANDARD", 20, "NOT_FOR_SALE",
                        "YEAR", "CNY", 298000L, enterprisePolicyId)
                        : enterprisePurchase()));
        when(orders.findPlanPurchase(standardRevisionId)).thenReturn(Optional.of(standardPurchase()));
        when(lifecycle.lockActiveSubscription(tenantId)).thenReturn(Optional.of(
                new ActiveSubscription(UUID.randomUUID(), standardRevisionId,
                        Instant.parse("2026-01-01T00:00:00Z"), endsAt)));
    }

    /** 标准档购买投影：未开售、按年、参考价 ￥2,980、展示顺序 20、已绑定配额模板。 */
    private PlanPurchase standardPurchase() {
        return new PlanPurchase(standardRevisionId, "STANDARD", 20, "NOT_FOR_SALE", "YEAR",
                "CNY", 298000L, policyId);
    }

    /** 企业档购买投影：未开售、按年、参考价 ￥5,980、展示顺序 30、已绑定配额模板。 */
    private PlanPurchase enterprisePurchase() {
        return new PlanPurchase(enterpriseRevisionId, "ENTERPRISE", 30, "NOT_FOR_SALE", "YEAR",
                "CNY", 598000L, enterprisePolicyId);
    }

    /**
     * 构造整份购买订单事实。
     *
     * @param status 订单状态
     * @param providerEventId 支付事件 ID；未支付时为 null
     * @param paid 支付时刻；未支付时为 null
     * @return 订单事实
     */
    private TenantOrder order(TenantOrderStatus status, String providerEventId, Instant paid) {
        return new TenantOrder(orderId, tenantId, revisionId, TenantOrderKind.PURCHASE, null, null, null,
                PaymentProvider.SIMULATED, status, providerEventId, 298000L, "CNY",
                Instant.parse("2026-03-31T08:00:00Z"), paid, 0L, 1L);
    }

    /**
     * 构造升级订单事实（STANDARD → ENTERPRISE，中点折算）。
     *
     * @param status 订单状态
     * @param providerEventId 支付事件 ID；未支付时为 null
     * @param paid 支付时刻；未支付时为 null
     * @return 升级订单事实
     */
    private TenantOrder upgradeOrder(TenantOrderStatus status, String providerEventId, Instant paid) {
        SubscriptionUpgradeProration proration = SubscriptionUpgradeProration.between(
                quotedAt, periodEndsAt, 298000L, 598000L);
        return new TenantOrder(orderId, tenantId, enterpriseRevisionId, TenantOrderKind.UPGRADE,
                standardRevisionId, proration, null, PaymentProvider.SIMULATED, status, providerEventId,
                proration.differenceCents(), "CNY", Instant.parse("2026-07-01T00:00:00Z"), paid, 0L, 1L);
    }
}
