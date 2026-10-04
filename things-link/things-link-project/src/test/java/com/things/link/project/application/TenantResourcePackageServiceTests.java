package com.things.link.project.application;

import com.things.link.project.domain.PaymentProvider;
import com.things.link.project.domain.ResourcePackagePurchase;
import com.things.link.project.domain.ResourcePackageSource;
import com.things.link.project.domain.ResourcePackageStatus;
import com.things.link.project.domain.SubscriptionLifecycleState;
import com.things.link.project.domain.SubscriptionStatus;
import com.things.link.project.domain.TenantOrder;
import com.things.link.project.domain.TenantOrderKind;
import com.things.link.project.domain.TenantOrderRepository;
import com.things.link.project.domain.TenantOrderStatus;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S14-6i 验收：资源包生效的「是否未来起算」判定必须只用**数据库时间**，不得拿 JVM 时钟去比较数据库时间。
 *
 * <p>背景（D-180）：旧实现用 {@code clock.instant()} 与 {@code starts_at}（数据库 {@code now()}）比较，
 * 两个时钟相差几毫秒时，刚支付成功的包会被误判成未来起算，于是**跳过一次缓存失效**，
 * 只读/显示面继续用旧策略——真库定向回归里正是以「包已落库但有效额度仍是基础档」的形式间歇失败
 * （{@code expected: 5L but was: 3L}）。S14-6b 已为读面记录过同一类跨时钟缺口（见 {@code DatabaseTime} 类注释），
 * 本片把写入侧的同款比较一并收口。
 *
 * <p>本类用**故意落后于支付时刻**的固定时钟把这个竞态变成确定性断言：时钟落后时旧实现必然跳过失效，
 * 新实现必须仍然推进绑定版本。原子性与缓存可见性由 bootstrap 的真实 PostgreSQL 用例承担，这里不伪造数据库行为。
 */
@DisplayName("S14-6i 资源包生效的窗口判定")
class TenantResourcePackageServiceTests {

    /** 包订单归属租户。 */
    private final UUID tenantId = UUID.randomUUID();
    /** 包订单 ID。 */
    private final UUID orderId = UUID.randomUUID();
    /** 租户当前绑定的配额策略。 */
    private final UUID policyId = UUID.randomUUID();
    /** 支付成功时刻：来自数据库 {@code now()}。 */
    private final Instant paidAt = Instant.parse("2026-09-17T08:30:00Z");
    /** 生效的绑定版本。 */
    private final long assignmentVersion = 7L;

    /** 订单事实替身。 */
    private final TenantOrderRepository orders = mock(TenantOrderRepository.class);
    /** 资源包事实替身。 */
    private final TenantResourcePackageRepository packages = mock(TenantResourcePackageRepository.class);
    /** 订阅生命周期替身（租户行锁、当前状态与策略指针）。 */
    private final TenantSubscriptionLifecycleRepository lifecycle =
            mock(TenantSubscriptionLifecycleRepository.class);
    /** 策略绑定用例替身：本类只关心它是否被调用。 */
    private final QuotaPolicyAssignmentService assignment = mock(QuotaPolicyAssignmentService.class);
    /** 商业审计替身。 */
    private final AuditLogService auditLog = mock(AuditLogService.class);

    /** JVM 时钟落后数据库支付时刻 5 秒时，刚支付成功的包仍必须推进绑定版本（D-180 回归）。 */
    @Test
    void paidPackageInvalidatesPolicyCacheEvenWhenJvmClockLagsDatabase() {
        Instant jvmNowBehindDatabase = paidAt.minusSeconds(5);
        TenantResourcePackageService service = serviceWithClock(Clock.fixed(jvmNowBehindDatabase, ZoneOffset.UTC));
        stubPaidPackageOrder(null);

        service.applySimulatedPackagePaymentSucceeded(orderId, "sim-pay-lagging-clock");

        verify(assignment).assign(eq(tenantId), eq(policyId), eq(assignmentVersion));
    }

    /** 未来起算的包不推进版本（原文的优化语义保留，只是不再依赖 JVM 时钟）。 */
    @Test
    void futureDatedPackageDoesNotChurnAssignmentVersion() {
        Instant requestedStart = paidAt.plusSeconds(3600);
        TenantResourcePackageService service = serviceWithClock(Clock.fixed(paidAt, ZoneOffset.UTC));
        stubPaidPackageOrder(requestedStart);

        service.applySimulatedPackagePaymentSucceeded(orderId, "sim-pay-future-package");

        verify(assignment, never()).assign(any(), any(), anyLong());
    }

    /** 组装被测服务：只替换时间来源，其余依赖均为替身。 */
    private TenantResourcePackageService serviceWithClock(Clock clock) {
        return new TenantResourcePackageService(orders, packages, lifecycle, assignment, auditLog, clock);
    }

    /** 让订单看起来是「已支付成功的包订单」，并把租户置于 ACTIVE 订阅下。 */
    private void stubPaidPackageOrder(Instant requestedStartsAt) {
        TenantOrder order = new TenantOrder(orderId, tenantId, null, TenantOrderKind.PACKAGE, null, null,
                new ResourcePackagePurchase("DEVICES_MAX", 2, "COUNT", "NONE", 12, requestedStartsAt),
                PaymentProvider.SIMULATED, TenantOrderStatus.CREATED, null, 2_000L, "CNY",
                paidAt.minusSeconds(60), null, 0L, 1L);
        when(orders.lockOrder(orderId)).thenReturn(Optional.of(order));
        when(orders.findByProviderEventId(any(), any())).thenReturn(Optional.empty());
        when(orders.markPaid(eq(orderId), any(), any())).thenReturn(Optional.of(paidAt));
        when(lifecycle.lockTenantAndReadAssignmentVersion(tenantId)).thenReturn(assignmentVersion);
        when(lifecycle.findCurrentState(tenantId)).thenReturn(Optional.of(new SubscriptionLifecycleState(
                UUID.randomUUID(), tenantId, UUID.randomUUID(), SubscriptionStatus.ACTIVE,
                paidAt.minusSeconds(3600), null, null, null)));
        when(lifecycle.readPolicyId(tenantId)).thenReturn(policyId);
        when(packages.insert(eq(tenantId), any(), anyLong(), any(), any(), any(), any(),
                eq(ResourcePackageSource.PURCHASE), eq(orderId), isNull(), eq(ResourcePackageStatus.ACTIVE)))
                .thenReturn(UUID.randomUUID());
    }
}
