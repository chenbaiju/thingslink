package com.things.link.project.application;

import com.things.link.project.domain.SubscriptionLifecycleState;
import com.things.link.project.domain.SubscriptionNotificationIntentRepository;
import com.things.link.project.domain.SubscriptionStatus;
import com.things.link.project.domain.TenantOrderRepository;
import com.things.link.project.domain.TenantSubscriptionChangeRepository;
import com.things.link.project.domain.TenantSubscriptionLifecycleRepository;
import com.things.link.project.domain.TenantSubscriptionRepository;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S14-3c：状态机使用注入 Clock 的纯单测。
 *
 * <p>真库用例用显式时刻驱动；本类补上「生产构造注入的 Clock 确实被 {@code advanceDueTransitions}
 * 使用」这一证据，并对宽限终点的冻结算法（到期日 + 14 自然日）与审计字段做独立断言。
 * 真实扫描 SQL、行锁与幂等性由 PG 用例证明，不在此模拟成功事务。
 */
class SubscriptionLifecycleServiceTests {

    /** 固定时刻：既作为到期日，也作为注入时钟的读数。 */
    private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");

    /** 领域端口替身。 */
    private final TenantSubscriptionLifecycleRepository lifecycleRepository =
            mock(TenantSubscriptionLifecycleRepository.class);
    private final TenantSubscriptionChangeRepository changeRepository =
            mock(TenantSubscriptionChangeRepository.class);
    private final SubscriptionNotificationIntentRepository intentRepository =
            mock(SubscriptionNotificationIntentRepository.class);
    private final TenantOrderRepository orderRepository = mock(TenantOrderRepository.class);
    private final TenantSubscriptionRepository subscriptionRepository =
            mock(TenantSubscriptionRepository.class);
    private final QuotaPolicyAssignmentService quotaPolicyAssignmentService =
            mock(QuotaPolicyAssignmentService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);

    /** 使用固定时钟的被测服务；生产构造器同样注入 Clock。 */
    private final SubscriptionLifecycleService service = new SubscriptionLifecycleService(
            lifecycleRepository, changeRepository, intentRepository, orderRepository,
            subscriptionRepository, mock(CommercialProjectAccessService.class),
            quotaPolicyAssignmentService, auditLogService, Clock.fixed(NOW, ZoneOffset.UTC));

    /** 注入的固定时钟被读取，宽限终点按「到期日 + 14 自然日」写回，并留下审计事实。 */
    @Test
    void advanceDueTransitionsUsesInjectedClockAndFreezesGraceEnd() {
        SubscriptionLifecycleState expired = new SubscriptionLifecycleState(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), SubscriptionStatus.ACTIVE,
                NOW.minus(Duration.ofDays(365)), NOW, null, null);
        Instant graceEndsAt = NOW.plus(Duration.ofDays(14));
        when(changeRepository.findDuePending(NOW, SubscriptionLifecycleService.BATCH_LIMIT))
                .thenReturn(List.of());
        when(lifecycleRepository.findDueForGrace(NOW, SubscriptionLifecycleService.BATCH_LIMIT))
                .thenReturn(List.of(expired));
        when(lifecycleRepository.enterGrace(expired.id(), graceEndsAt, NOW)).thenReturn(true);
        when(lifecycleRepository.findState(expired.id())).thenReturn(java.util.Optional.of(expired));
        when(lifecycleRepository.findDueForRestriction(NOW, SubscriptionLifecycleService.BATCH_LIMIT))
                .thenReturn(List.of());
        when(lifecycleRepository.findCurrentWithPeriodEnd(SubscriptionLifecycleService.BATCH_LIMIT))
                .thenReturn(List.of());

        SubscriptionLifecycleReport report = service.advanceDueTransitions();

        assertThat(report.graceEntered()).isEqualTo(1);
        verify(lifecycleRepository).enterGrace(expired.id(), graceEndsAt, NOW);

        ArgumentCaptor<AuditLogEntry> audit = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogService).record(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo("commercial.subscription.grace.entered");
        assertThat(audit.getValue().targetId()).isEqualTo(expired.id());
        assertThat(audit.getValue().details().get("expiresAt")).isEqualTo(NOW.toString());
        assertThat(audit.getValue().details().get("graceEndsAt")).isEqualTo(graceEndsAt.toString());
        assertThat(audit.getValue().details().get("graceDays")).isEqualTo(14L);
    }
}
