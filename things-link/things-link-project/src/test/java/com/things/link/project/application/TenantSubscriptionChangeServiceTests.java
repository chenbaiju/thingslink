package com.things.link.project.application;

import com.things.link.project.domain.ActiveSubscription;
import com.things.link.project.domain.PlanPurchase;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.SubscriptionPendingChange;
import com.things.link.project.domain.SubscriptionPendingChangeStatus;
import com.things.link.project.domain.TenantOrderRepository;
import com.things.link.project.domain.TenantSubscriptionChangeRepository;
import com.things.link.project.domain.TenantSubscriptionLifecycleRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S14-3b 预约降级服务的判定单元测试。
 *
 * <p>只钉服务内的判定与幂等：没有生效订阅/长期 FREE 不得预约、目标必须严格更低档、
 * 同目标重复预约是幂等重放、不同目标必须显式冲突、撤销是 no-op 幂等且只在真的改了状态时审计。
 * 真实 PostgreSQL 上的唯一 PENDING 仲裁、外键与时间线由 bootstrap 集成测试承担。
 */
@DisplayName("S14-3b 预约降级服务判定")
class TenantSubscriptionChangeServiceTests {

    /** 归属租户。 */
    private final UUID tenantId = UUID.randomUUID();
    /** 当前 ACTIVE 订阅 ID。 */
    private final UUID subscriptionId = UUID.randomUUID();
    /** 当前档位（STANDARD，展示顺序 20）。 */
    private final UUID standardRevisionId = UUID.randomUUID();
    /** 免费档（FREE，展示顺序 10）。 */
    private final UUID freeRevisionId = UUID.randomUUID();
    /** 升级目标档（ENTERPRISE，展示顺序 30）。 */
    private final UUID enterpriseRevisionId = UUID.randomUUID();
    /** 另一条更低档修订版（用于「目标不同」的冲突用例）。 */
    private final UUID otherLowerRevisionId = UUID.randomUUID();
    /** 预约 ID。 */
    private final UUID changeId = UUID.randomUUID();
    /** 当前服务期终点。 */
    private final Instant periodEndsAt = Instant.parse("2027-01-01T00:00:00Z");

    /** 预约事实替身。 */
    private final TenantSubscriptionChangeRepository changes =
            mock(TenantSubscriptionChangeRepository.class);
    /** 订阅生命周期读入口替身。 */
    private final TenantSubscriptionLifecycleRepository lifecycle =
            mock(TenantSubscriptionLifecycleRepository.class);
    /** 修订版投影替身。 */
    private final TenantOrderRepository orders = mock(TenantOrderRepository.class);
    /** 审计替身。 */
    private final AuditLogService audit = mock(AuditLogService.class);
    /** 被测服务。 */
    private final TenantSubscriptionChangeService service =
            new TenantSubscriptionChangeService(changes, lifecycle, orders, audit);

    /** 预约降级：生效时刻固定等于当前服务期终点，并写预约审计。 */
    @Test
    void schedulesDowngradeAtCurrentPeriodEnd() {
        stubActiveStandardSubscription(periodEndsAt);
        stubPurchase(freeRevisionId, "FREE", 10, 0L);
        when(changes.findPending(tenantId)).thenReturn(Optional.empty());
        when(changes.insertPending(tenantId, subscriptionId, standardRevisionId, freeRevisionId,
                periodEndsAt)).thenReturn(changeId);
        SubscriptionPendingChange stored = pending(changeId, freeRevisionId,
                SubscriptionPendingChangeStatus.PENDING);
        when(changes.findById(changeId)).thenReturn(Optional.of(stored));

        SubscriptionPendingChange result = service.requestDowngrade(tenantId, freeRevisionId);

        assertThat(result).isEqualTo(stored);
        assertThat(result.effectiveAt())
                .as("降级在下个周期生效，生效时刻就是当前服务期终点")
                .isEqualTo(periodEndsAt);
        assertThat(result.status()).isEqualTo(SubscriptionPendingChangeStatus.PENDING);

        ArgumentCaptor<AuditLogEntry> entry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().action())
                .isEqualTo("commercial.subscription.downgrade.scheduled");
        assertThat(details(entry.getValue()))
                .containsEntry("effectiveAt", periodEndsAt.toString())
                .containsEntry("fromPlanCode", "STANDARD")
                .containsEntry("targetPlanCode", "FREE")
                .containsEntry("status", "PENDING");
    }

    /** 同目标重复预约是幂等重放：不写第二行、不重复审计。 */
    @Test
    void replayingSameTargetDowngradeIsIdempotent() {
        stubActiveStandardSubscription(periodEndsAt);
        stubPurchase(freeRevisionId, "FREE", 10, 0L);
        SubscriptionPendingChange existing = pending(changeId, freeRevisionId,
                SubscriptionPendingChangeStatus.PENDING);
        when(changes.findPending(tenantId)).thenReturn(Optional.of(existing));

        SubscriptionPendingChange result = service.requestDowngrade(tenantId, freeRevisionId);

        assertThat(result).isEqualTo(existing);
        verify(changes, never()).insertPending(any(), any(), any(), any(), any());
        verify(audit, never()).record(any());
    }

    /** 已有目标不同的 PENDING：显式冲突，要求先撤销，绝不静默替换。 */
    @Test
    void refusesDifferentTargetWhileAnotherPending() {
        stubActiveStandardSubscription(periodEndsAt);
        stubPurchase(freeRevisionId, "FREE", 10, 0L);
        stubPurchase(otherLowerRevisionId, "FREE_R2", 10, 0L);
        when(changes.findPending(tenantId)).thenReturn(Optional.of(pending(changeId, freeRevisionId,
                SubscriptionPendingChangeStatus.PENDING)));

        BusinessException refusal = catchThrowableOfType(
                () -> service.requestDowngrade(tenantId, otherLowerRevisionId), BusinessException.class);

        assertThat(refusal).isNotNull();
        assertThat(refusal.errorCode()).isEqualTo(ProjectErrorCode.PENDING_CHANGE_CONFLICT);
        assertThat(refusal.errorCode().code()).isEqualTo(50029);
        verify(changes, never()).insertPending(any(), any(), any(), any(), any());
    }

    /** 同档或更高档不得当降级预约。 */
    @Test
    void refusesNotADowngrade() {
        stubActiveStandardSubscription(periodEndsAt);
        stubPurchase(enterpriseRevisionId, "ENTERPRISE", 30, 598000L);

        BusinessException refusal = catchThrowableOfType(
                () -> service.requestDowngrade(tenantId, enterpriseRevisionId), BusinessException.class);

        assertThat(refusal).isNotNull();
        assertThat(refusal.errorCode()).isEqualTo(ProjectErrorCode.NOT_A_DOWNGRADE);
        assertThat(refusal.errorCode().code()).isEqualTo(50027);
        verify(changes, never()).insertPending(any(), any(), any(), any(), any());
    }

    /** 没有 ACTIVE 订阅时无法表达预约。 */
    @Test
    void refusesWhenNoActiveSubscription() {
        when(changes.lockTenant(tenantId)).thenReturn(true);
        when(lifecycle.lockActiveSubscription(tenantId)).thenReturn(Optional.empty());

        BusinessException refusal = catchThrowableOfType(
                () -> service.requestDowngrade(tenantId, freeRevisionId), BusinessException.class);

        assertThat(refusal).isNotNull();
        assertThat(refusal.errorCode()).isEqualTo(ProjectErrorCode.NO_ACTIVE_SUBSCRIPTION);
        assertThat(refusal.errorCode().code()).isEqualTo(50028);
    }

    /** 长期 FREE 没有服务期终点，预约降级无从表达。 */
    @Test
    void refusesUnboundedPeriod() {
        stubActiveStandardSubscription(null);

        BusinessException refusal = catchThrowableOfType(
                () -> service.requestDowngrade(tenantId, freeRevisionId), BusinessException.class);

        assertThat(refusal).isNotNull();
        assertThat(refusal.errorCode()).isEqualTo(ProjectErrorCode.SUBSCRIPTION_PERIOD_UNBOUNDED);
        assertThat(refusal.errorCode().code()).isEqualTo(50030);
        verify(changes, never()).insertPending(any(), any(), any(), any(), any());
    }

    /** 撤销预约：状态推进一次并审计；再次撤销是 no-op 且不重复审计。 */
    @Test
    void cancelsPendingDowngradeOnce() {
        when(changes.lockTenant(tenantId)).thenReturn(true);
        SubscriptionPendingChange existing = pending(changeId, freeRevisionId,
                SubscriptionPendingChangeStatus.PENDING);
        when(changes.findPending(tenantId)).thenReturn(Optional.of(existing));
        when(changes.cancelPending(changeId)).thenReturn(true);

        assertThat(service.cancelDowngrade(tenantId)).isTrue();

        ArgumentCaptor<AuditLogEntry> entry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().action())
                .isEqualTo("commercial.subscription.downgrade.cancelled");
        assertThat(details(entry.getValue())).containsEntry("reason", "TENANT_REQUEST");

        when(changes.findPending(tenantId)).thenReturn(Optional.empty());
        assertThat(service.cancelDowngrade(tenantId)).isFalse();
        verify(audit, org.mockito.Mockito.times(1)).record(any());
    }

    /** 升级生效时清除预约降级：审计写明原因与触发的升级订单。 */
    @Test
    void cancellingForUpgradeAuditsUpgradeReason() {
        UUID upgradeOrderId = UUID.randomUUID();
        SubscriptionPendingChange existing = pending(changeId, freeRevisionId,
                SubscriptionPendingChangeStatus.PENDING);
        when(changes.findPending(tenantId)).thenReturn(Optional.of(existing));
        when(changes.cancelPending(changeId)).thenReturn(true);

        assertThat(service.cancelPendingForUpgrade(tenantId, upgradeOrderId)).isTrue();

        ArgumentCaptor<AuditLogEntry> entry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(audit).record(entry.capture());
        assertThat(details(entry.getValue()))
                .containsEntry("reason", "SUPERSEDED_BY_UPGRADE")
                .containsEntry("upgradeOrderId", upgradeOrderId.toString());
    }

    /** 没有待生效预约时清除是纯 no-op。 */
    @Test
    void cancellingWithoutPendingChangeIsNoOp() {
        when(changes.findPending(tenantId)).thenReturn(Optional.empty());

        assertThat(service.cancelPendingForUpgrade(tenantId, UUID.randomUUID())).isFalse();
        verify(audit, never()).record(any());
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
     * 打桩租户存在、当前 ACTIVE 为标准档。
     *
     * @param endsAt 当前服务期终点；{@code null} 表示长期 FREE
     */
    private void stubActiveStandardSubscription(Instant endsAt) {
        when(changes.lockTenant(tenantId)).thenReturn(true);
        when(lifecycle.lockActiveSubscription(tenantId)).thenReturn(Optional.of(
                new ActiveSubscription(subscriptionId, standardRevisionId,
                        Instant.parse("2026-01-01T00:00:00Z"), endsAt)));
        stubPurchase(standardRevisionId, "STANDARD", 20, 298000L);
    }

    /**
     * 打桩一个修订版购买投影。
     *
     * @param revisionId 修订版 ID
     * @param planCode 档位编码
     * @param displayOrder 展示顺序
     * @param referencePriceCents 参考年价
     */
    private void stubPurchase(UUID revisionId, String planCode, int displayOrder,
                             Long referencePriceCents) {
        when(orders.findPlanPurchase(revisionId)).thenReturn(Optional.of(new PlanPurchase(
                revisionId, planCode, displayOrder, "NOT_FOR_SALE", "YEAR", "CNY",
                referencePriceCents, UUID.randomUUID())));
    }

    /**
     * 构造一条预约事实。
     *
     * @param id 预约 ID
     * @param targetRevisionId 目标修订版
     * @param status 状态
     * @return 预约事实
     */
    private SubscriptionPendingChange pending(UUID id, UUID targetRevisionId,
                                              SubscriptionPendingChangeStatus status) {
        return new SubscriptionPendingChange(id, tenantId, subscriptionId, standardRevisionId,
                targetRevisionId, periodEndsAt, status, Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-01T00:00:00Z"), 1L);
    }
}
