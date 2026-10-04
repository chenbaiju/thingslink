package com.things.link.project.application;

import com.things.link.project.domain.PaymentProvider;
import com.things.link.project.domain.ResourcePackagePurchase;
import com.things.link.project.domain.ResourcePackageSource;
import com.things.link.project.domain.ResourcePackageStatus;
import com.things.link.project.domain.TenantOrder;
import com.things.link.project.domain.TenantOrderKind;
import com.things.link.project.domain.TenantOrderRepository;
import com.things.link.project.domain.TenantOrderStatus;
import com.things.link.project.domain.TenantRefund;
import com.things.link.project.domain.TenantRefundRepository;
import com.things.link.project.domain.TenantResourcePackage;
import com.things.link.project.domain.TenantResourcePackageRepository;
import com.things.link.project.domain.TenantSubscriptionLifecycleRepository;
import com.things.link.support.audit.AuditLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S14-6j 验收：退款是否「收回了一份正在贡献的额度」必须由**数据库时间**判定，并按该判据失效策略缓存。
 *
 * <p>背景（D-180 同类缺口）：旧实现拿 JVM 时钟（{@code clock.instant()}）去比较数据库写入的
 * {@code starts_at/ends_at}。两者相差几毫秒时，一份**确实在窗口内**的包会被判成「还没起算」，
 * 于是包被置 {@code REFUNDED}（权益在合成函数里已经不再贡献）却**跳过了缓存失效**，
 * 运行时继续按已收回的额度放行，直到 TTL 或下一次版本事件。
 *
 * <p>本类用「窗口故意与 JVM 时钟相反」的替身把这个判定钉死：判据只能来自
 * {@link TenantResourcePackageRepository#isCurrentlyEffective(UUID)}，不来自 Java 侧的窗口算术。
 */
@DisplayName("S14-6j 退款收回额度的判定与缓存失效")
class TenantRefundServiceTests {

    /** 订单归属租户。 */
    private final UUID tenantId = UUID.randomUUID();
    /** 待退款订单 ID。 */
    private final UUID orderId = UUID.randomUUID();
    /** 来源资源包 ID。 */
    private final UUID packageId = UUID.randomUUID();
    /** 租户当前绑定的配额策略。 */
    private final UUID policyId = UUID.randomUUID();
    /** 订单金额（人民币分）。 */
    private final long amountCents = 2_000L;
    /** 退款时刻。 */
    private final Instant refundedAt = Instant.parse("2026-09-17T09:00:00Z");
    /** 绑定版本。 */
    private final long assignmentVersion = 11L;

    /** 订单事实替身。 */
    private final TenantOrderRepository orders = mock(TenantOrderRepository.class);
    /** 退款事实替身。 */
    private final TenantRefundRepository refunds = mock(TenantRefundRepository.class);
    /** 资源包事实替身：本类只关心「是否正在贡献」这一判据来自它。 */
    private final TenantResourcePackageRepository packages = mock(TenantResourcePackageRepository.class);
    /** 订阅生命周期替身。 */
    private final TenantSubscriptionLifecycleRepository lifecycle =
            mock(TenantSubscriptionLifecycleRepository.class);
    /** 策略绑定用例替身。 */
    private final QuotaPolicyAssignmentService assignment = mock(QuotaPolicyAssignmentService.class);
    /** 商业审计替身。 */
    private final AuditLogService auditLog = mock(AuditLogService.class);

    /** 数据库判定为「正在贡献」时必须失效缓存——即使 JVM 时钟认为这个包还没起算。 */
    @Test
    void refundingEffectivePackageInvalidatesPolicyCache() {
        // JVM 时钟被固定在包窗口之前：旧实现会据此判成「未生效」并跳过失效。
        TenantRefundService service = serviceWithClock(Clock.fixed(refundedAt.minusSeconds(3600), ZoneOffset.UTC));
        stubRefundableOrderAndPackage();
        when(packages.isCurrentlyEffective(packageId)).thenReturn(true);

        TenantRefund refund = service.refundPackageOrder(tenantId, orderId, amountCents, "客户申请", "sim-refund-1");

        verify(packages).markRefunded(eq(packageId), any());
        verify(assignment).assign(eq(tenantId), eq(policyId), eq(assignmentVersion));
        assertThat(refund.amountCents()).isEqualTo(amountCents);
    }

    /** 数据库判定为「尚未贡献」时不推进版本：未来起算的包被退款不需要无谓失效。 */
    @Test
    void refundingNotYetEffectivePackageDoesNotChurnAssignmentVersion() {
        TenantRefundService service = serviceWithClock(Clock.fixed(refundedAt, ZoneOffset.UTC));
        stubRefundableOrderAndPackage();
        when(packages.isCurrentlyEffective(packageId)).thenReturn(false);

        service.refundPackageOrder(tenantId, orderId, amountCents, "客户申请", "sim-refund-2");

        verify(packages).markRefunded(eq(packageId), any());
        verify(assignment, never()).assign(any(), any(), anyLong());
    }

    /** 组装被测服务：只替换时间来源。 */
    private TenantRefundService serviceWithClock(Clock clock) {
        return new TenantRefundService(orders, refunds, packages, lifecycle, assignment, auditLog, clock);
    }

    /** 让订单是「已支付、未退款的包订单」，来源包处于 ACTIVE。 */
    private void stubRefundableOrderAndPackage() {
        TenantOrder order = new TenantOrder(orderId, tenantId, null, TenantOrderKind.PACKAGE, null, null,
                new ResourcePackagePurchase("DEVICES_MAX", 2, "COUNT", "NONE", 12, null),
                PaymentProvider.SIMULATED, TenantOrderStatus.PAID, "sim-pay-1", amountCents, "CNY",
                refundedAt.minusSeconds(600), refundedAt.minusSeconds(600), 0L, 1L);
        when(refunds.findByProviderRefundId(any(), any())).thenReturn(Optional.empty());
        when(orders.lockOrder(orderId)).thenReturn(Optional.of(order));
        when(orders.reserveRefund(orderId, amountCents)).thenReturn(true);
        TenantResourcePackage sourcePackage = new TenantResourcePackage(packageId, tenantId, "DEVICES_MAX",
                2, "COUNT", "NONE", refundedAt.minusSeconds(600), refundedAt.plusSeconds(30_000_000),
                ResourcePackageSource.PURCHASE, orderId, null, ResourcePackageStatus.ACTIVE, 1L);
        when(packages.findBySourceOrder(orderId)).thenReturn(Optional.of(sourcePackage));
        when(packages.markRefunded(eq(packageId), any())).thenReturn(true);
        when(refunds.insert(any(), any(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());
        when(lifecycle.lockTenantAndReadAssignmentVersion(tenantId)).thenReturn(assignmentVersion);
        when(lifecycle.readPolicyId(tenantId)).thenReturn(policyId);
    }
}
