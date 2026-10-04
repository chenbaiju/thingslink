package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmNotificationDelivery;
import com.things.link.alarm.domain.AlarmNotificationRepository;
import com.things.link.alarm.domain.NotificationChannel;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ADR0069：重排队依赖可信持久身份与最终CAS，单测只验证编排，物理原子性另有PG验收。 */
class NotificationRetrySchedulerTests {
    /** 原领取与状态仓储替身。 */
    private AlarmNotificationRepository repository;
    /** 不允许冻结候选新增通知意图。 */
    private TransactionalOutboxRepository outbox;
    /** 显式项目许可替身。 */
    private ProjectLifecycleAccessService lifecycle;
    /** 原单轮重试服务。 */
    private NotificationRetryScheduler scheduler;
    /** 原始退避事实，attempt由持久记录决定。 */
    private AlarmNotificationDelivery delivery;
    /** 真实领取函数形状的候选。 */
    private AlarmNotificationRepository.RetryCandidate candidate;
    /** 只允许当前轮token影响该行。 */
    private final UUID retryToken = UUID.randomUUID();

    /** 逻辑事务同步执行，不设置真实事务标志或声称mock已经提交。 */
    @BeforeEach
    void prepare() {
        repository = mock(AlarmNotificationRepository.class);
        outbox = mock(TransactionalOutboxRepository.class);
        lifecycle = mock(ProjectLifecycleAccessService.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(new SimpleTransactionStatus());
            return null;
        }).when(transactions).executeWithoutResult(any());
        Instant now = Instant.parse("2026-09-04T12:00:00Z");
        delivery = new AlarmNotificationDelivery(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null,
                NotificationChannel.EMAIL, "ops@example.com", "主题", "正文", 1,
                AlarmNotificationDelivery.Status.RETRY_SCHEDULED, 1, 3, now.minusSeconds(1),
                UUID.randomUUID(), null, "INVALID_DELIVERY", now.minusSeconds(60), now.minusSeconds(60), null);
        candidate = new AlarmNotificationRepository.RetryCandidate(delivery.id(), delivery.tenantId(), delivery.projectId(),
                delivery.instanceId(), delivery.alarmEventId(), 2);
        when(repository.claimRetries(anyInt(), any())).thenReturn(new AlarmNotificationRepository.RetryClaim(retryToken, List.of(candidate)));
        when(repository.findDelivery(delivery.projectId(), delivery.id())).thenReturn(Optional.of(delivery));
        when(lifecycle.lockActiveForWrite(any(), any())).thenReturn(true);
        NotificationDeliveryProperties properties = new NotificationDeliveryProperties(Duration.ofSeconds(10),
                Duration.ofMinutes(1), Duration.ofMinutes(5), 10, Duration.ofSeconds(30),
                new NotificationDeliveryProperties.Webhook("test-webhook-signing-secret-at-least-32-bytes"));
        scheduler = new NotificationRetryScheduler(repository, outbox, transactions, new ObjectMapper(),
                Clock.fixed(now, ZoneOffset.UTC), properties, lifecycle);
    }

    /** 许可后仍以真实CAS成功作为追加Outbox的必要条件。 */
    @Test
    void activePermissionPrecedesRequeueAndOutbox() {
        when(repository.requeueClaimedRetry(any(), any(), eq(retryToken), any(), eq(2), any())).thenReturn(true);
        scheduler.enqueueDueRetries();
        var order = inOrder(repository, lifecycle, outbox);
        order.verify(repository).findDelivery(delivery.projectId(), delivery.id());
        order.verify(lifecycle).lockActiveForWrite(delivery.tenantId(), delivery.projectId());
        order.verify(repository).requeueClaimedRetry(any(), any(), eq(retryToken), any(), eq(2), any());
        order.verify(outbox).append(any());
        assertThat(TenantContext.current()).isEmpty();
    }

    /** 冻结走原token窄CAS而不生成新意图，false同样不能伪造重排队。 */
    @Test
    void frozenCandidateStopsWithoutOutboxEvenWhenItsCasIsStale() {
        when(lifecycle.lockActiveForWrite(any(), any())).thenReturn(false);
        scheduler.enqueueDueRetries();
        verify(repository).stopClaimedRetryForProjectFreeze(eq(delivery.tenantId()), eq(delivery.projectId()),
                eq(delivery.id()), eq(retryToken), eq(2), any());
        verify(repository, never()).requeueClaimedRetry(any(), any(), any(), any(), anyInt(), any());
        verify(outbox, never()).append(any());
    }

    /** 生命周期检查与最终CAS之间可能丢租约；拒绝追加Outbox。 */
    @Test
    void permissionDoesNotOverrideFinalRetryCas() {
        scheduler.enqueueDueRetries();
        verify(lifecycle).lockActiveForWrite(any(), any());
        verify(outbox, never()).append(any());
    }

    /** SQL错误保留原异常并清理范围，不能误判项目冻结或推进状态。 */
    @Test
    void permissionFailurePropagatesWithoutStateMutation() {
        RuntimeException failure = new IllegalStateException("real SQL boundary");
        when(lifecycle.lockActiveForWrite(any(), any())).thenThrow(failure);
        assertThatThrownBy(scheduler::enqueueDueRetries).isSameAs(failure);
        assertThat(TenantContext.current()).isEmpty();
        verify(repository, never()).requeueClaimedRetry(any(), any(), any(), any(), anyInt(), any());
        verify(outbox, never()).append(any());
    }

    /** 返回的project正确不足以证明tenant真实，持久二元组冲突必须失败。 */
    @Test
    void wrongTenantCandidateFailsBeforePermissionAndMutation() {
        var wrong = new AlarmNotificationRepository.RetryCandidate(delivery.id(), UUID.randomUUID(), delivery.projectId(),
                delivery.instanceId(), delivery.alarmEventId(), 2);
        when(repository.claimRetries(anyInt(), any())).thenReturn(new AlarmNotificationRepository.RetryClaim(retryToken, List.of(wrong)));
        assertThatThrownBy(scheduler::enqueueDueRetries).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("通知重试候选与持久身份不匹配");
        verify(lifecycle, never()).lockActiveForWrite(any(), any());
        verify(outbox, never()).append(any());
        assertThat(TenantContext.current()).isEmpty();
    }
    /** 不允许调用方事务扩大项目锁至整批；拒绝发生于全局领取之前。 */
    @Test
    void rejectsAmbientTransactionBeforeClaim() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(scheduler::enqueueDueRetries).isInstanceOf(IllegalStateException.class)
                    .hasMessage("通知重排队不得加入调用方事务");
            verify(repository, never()).claimRetries(anyInt(), any());
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }

    /** 维护调用者可能已有只读范围，处理当前项目后恢复原范围而不是静默擦除。 */
    @Test
    void restoresPreviousScopeAfterPermissionFailure() {
        TenantScope previous = new TenantScope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        TenantContext.set(previous);
        when(lifecycle.lockActiveForWrite(any(), any())).thenThrow(new IllegalStateException("permission failed"));
        try {
            assertThatThrownBy(scheduler::enqueueDueRetries).hasMessage("permission failed");
            assertThat(TenantContext.current()).contains(previous);
        } finally { TenantContext.clear(); }
    }

}
