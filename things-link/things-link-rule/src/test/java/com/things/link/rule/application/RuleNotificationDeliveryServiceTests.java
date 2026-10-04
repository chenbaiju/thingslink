package com.things.link.rule.application;

import com.things.link.shared.message.RuleNotificationDeliveryRequest;
import com.things.link.project.application.ProjectLifecycleAccessService;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.things.link.support.resilience.NotificationExternalGuard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 规则通知状态机验证真实成功、有限重试、死信和 Kafka 重投吸收。 */
class RuleNotificationDeliveryServiceTests {

    /** 固定测试时刻。 */
    private static final Instant NOW = Instant.parse("2026-08-14T02:00:00Z");

    /** 投递状态仓储替身。 */
    private RuleNotificationDeliveryStore store;

    /** 渠道发送器替身。 */
    private RuleNotificationSender sender;

    /** 被测状态机。 */
    private RuleNotificationDeliveryService service;

    /** 完整首次请求。 */
    private RuleNotificationDeliveryRequest request;
    /** 固定 dispatch 租约。 */
    private UUID dispatchToken;
    /** 渠道隔离门面；测试拒绝不能提前消耗 attempt。 */
    private NotificationExternalGuard externalGuard;

    /** 单元层只检查事务决策，真实提交和回滚由PG类验收。 */
    private TransactionTemplate transactions;
    /** 最后一次短事务状态用于证明guard拒绝标记回滚。 */
    private SimpleTransactionStatus transactionStatus;
    /** 项目公开许可替身。 */
    private ProjectLifecycleAccessService lifecycle;

    /** 每个用例建立固定时钟和 EMAIL 渠道。 */
    @BeforeEach
    void setUp() {
        store = mock(RuleNotificationDeliveryStore.class);
        sender = mock(RuleNotificationSender.class);
        when(sender.channel()).thenReturn("EMAIL");
        externalGuard = new NotificationExternalGuard();
        lifecycle = mock(ProjectLifecycleAccessService.class);
        when(lifecycle.lockActiveForWrite(any(), any())).thenReturn(true);
        when(store.accept(any())).thenReturn(RuleNotificationDeliveryStore.Acceptance.IDEMPOTENT_REPLAY);
        transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            transactionStatus = new SimpleTransactionStatus();
            return callback.doInTransaction(transactionStatus);
        }).when(transactions).execute(any());
        service = new RuleNotificationDeliveryService(
                store, List.of(sender), Clock.fixed(NOW, ZoneOffset.UTC), externalGuard, transactions, lifecycle);
        request = request("email");
        dispatchToken = UUID.randomUUID();
        when(store.startClaimed(
                eq(request.projectId()), eq(request.eventId()), eq(1), eq(dispatchToken), eq(NOW), any()))
                .thenReturn(true);
    }

    /** 真实渠道成功才推进 DELIVERED，并保存低敏供应商 ID。 */
    @Test
    void marksRealChannelSuccess() {
        when(sender.send(any())).thenReturn("email:" + request.eventId());

        service.deliverClaimed(request, dispatchToken);

        verify(store).markDelivered(
                request.projectId(), request.eventId(), 1,
                "email:" + request.eventId(), NOW);
    }

    /** 相同 eventId 的 Kafka 重投无法再次领取，不得重复触达外部渠道。 */
    @Test
    void absorbsDuplicateKafkaDelivery() {
        when(store.startClaimed(
                eq(request.projectId()), eq(request.eventId()), eq(1), eq(dispatchToken), eq(NOW), any()))
                .thenReturn(false);

        service.deliverClaimed(request, dispatchToken);

        verify(sender, never()).send(any());
    }

    /** 已被相同不可变事实接管的 Kafka 重放必须正常提交 offset，不能进入通用 DLQ。 */
    @Test
    void acceptsIdempotentKafkaReplay() {
        when(store.accept(request)).thenReturn(RuleNotificationDeliveryStore.Acceptance.IDEMPOTENT_REPLAY);

        assertThatCode(() -> service.accept(request)).doesNotThrowAnyException();
    }

    /** eventId 碰撞或越级尝试必须保留永久失败信号，不能被幂等分支吞掉。 */
    @Test
    void rejectsConflictingKafkaReplay() {
        when(store.accept(request)).thenReturn(RuleNotificationDeliveryStore.Acceptance.REJECTED);

        assertThatThrownBy(() -> service.accept(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("持久接管事实冲突");
    }

    /** 第一次 SMTP 可恢复失败登记一分钟退避，不提前死信。 */
    @Test
    void schedulesBoundedRetry() {
        when(sender.send(any())).thenThrow(new RuleNotificationSendException(
                RuleNotificationSendException.Reason.SMTP_FAILURE, true, null));

        service.deliverClaimed(request, dispatchToken);

        verify(store).markRetry(
                request.projectId(), request.eventId(), 1,
                NOW.plusSeconds(60), "SMTP_FAILURE", NOW);
        verify(store, never()).markDeadLetter(any(), any(), any(Integer.class), any(), any());
    }

    /** Webhook 普通 4xx 是永久失败，首次即进入 DEAD_LETTER。 */
    @Test
    void deadLettersPermanentFailure() {
        when(sender.channel()).thenReturn("WEBHOOK");
        service = new RuleNotificationDeliveryService(
                store, List.of(sender), Clock.fixed(NOW, ZoneOffset.UTC), new NotificationExternalGuard(), transactions, lifecycle);
        request = request("webhook");
        when(store.startClaimed(
                eq(request.projectId()), eq(request.eventId()), eq(1), eq(dispatchToken), eq(NOW), any()))
                .thenReturn(true);
        when(sender.send(any())).thenThrow(new RuleNotificationSendException(
                RuleNotificationSendException.Reason.WEBHOOK_CLIENT_ERROR, false, null));

        service.deliverClaimed(request, dispatchToken);

        verify(store).markDeadLetter(
                request.projectId(), request.eventId(), 1,
                "WEBHOOK_CLIENT_ERROR", NOW);
    }

    /** 第三次即使仍是可恢复故障也必须死信，保证重试次数最终有界。 */
    @Test
    void deadLettersExhaustedRetry() {
        request = RuleNotificationDeliveryRequest.rule(
                request.eventId(), request.tenantId(), request.projectId(), request.ruleId(),
                request.ruleVersionId(), request.messageId(), request.deviceId(), "EMAIL",
                request.recipient(), request.subject(), request.body(), request.traceId(), 3, NOW);
        when(store.startClaimed(
                eq(request.projectId()), eq(request.eventId()), eq(3), eq(dispatchToken), eq(NOW), any()))
                .thenReturn(true);
        when(sender.send(any())).thenThrow(new RuleNotificationSendException(
                RuleNotificationSendException.Reason.SMTP_FAILURE, true, null));

        service.deliverClaimed(request, dispatchToken);

        verify(store).markDeadLetter(
                request.projectId(), request.eventId(), 3, "SMTP_FAILURE", NOW);
    }

    /** ADR0069：bulkhead满仍先核持久资格，随后回滚start并退避释放旧token，PG类验证attempt实际恢复。 */
    @Test
    void bulkheadRejectionDoesNotConsumeAttempt() {
        List<NotificationExternalGuard.Guard> occupied = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> externalGuard.tryAcquire("EMAIL", "occupied@example.com"))
                .toList();
        try {
            service.deliverClaimed(request, dispatchToken);

            verify(store).releaseDispatch(
                    request.projectId(), request.eventId(), dispatchToken, java.time.Duration.ofSeconds(1));
            verify(store).startClaimed(any(), any(), eq(1), any(), any(), any());
            assertThat(transactionStatus.isRollbackOnly()).isTrue();
            verify(sender, never()).send(any());
        } finally {
            occupied.forEach(NotificationExternalGuard.Guard::close);
        }
    }

    /** 冻结必须优先于渠道资源竞争收束，不计供应商失败。 */
    @Test
    void frozenProjectStopsAfterStartedIdentityCheck() {
        when(lifecycle.lockActiveForWrite(request.tenantId(), request.projectId())).thenReturn(false);
        when(store.markDeadLetter(request.projectId(), request.eventId(), 1, "PROJECT_FROZEN", NOW)).thenReturn(true);
        service.deliverClaimed(request, dispatchToken);
        verify(store).markDeadLetter(request.projectId(), request.eventId(), 1, "PROJECT_FROZEN", NOW);
        verify(sender, never()).send(any());
    }

    /** 错误不可变快照必须回滚且早于项目许可，不将错误tenant伪装冻结。 */
    @Test
    void conflictingStartedSnapshotFailsBeforeProjectPermit() {
        when(store.accept(request)).thenReturn(RuleNotificationDeliveryStore.Acceptance.REJECTED);
        assertThatThrownBy(() -> service.deliverClaimed(request, dispatchToken)).isInstanceOf(IllegalArgumentException.class);
        verify(lifecycle, never()).lockActiveForWrite(any(), any());
        verify(sender, never()).send(any());
    }

    /** start失败不能由accept创建新通知。 */
    @Test
    void staleTokenNeverAcceptsOrAcquiresPermit() {
        when(store.startClaimed(any(), any(), eq(1), any(), any(), any())).thenReturn(false);
        service.deliverClaimed(request, dispatchToken);
        verify(store, never()).accept(any());
        verify(lifecycle, never()).lockActiveForWrite(any(), any());
    }

    /** 已有外层事务必须拒绝，不能把外部I/O偷偷并入调用方事务。 */
    @Test
    void ambientTransactionFailsBeforeDatabaseOrSender() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(() -> service.deliverClaimed(request, dispatchToken)).isInstanceOf(IllegalStateException.class); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        verify(store, never()).startClaimed(any(), any(), eq(1), any(), any(), any());
        verify(sender, never()).send(any());
    }

    /** 获得guard后提交失败必须取消资格，保留全部渠道槽供后续请求使用。 */
    @Test
    void commitFailureCancelsGuardWithoutCallingSender() {
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            callback.doInTransaction(new SimpleTransactionStatus());
            throw new IllegalStateException("提交失败");
        }).when(transactions).execute(any());
        assertThatThrownBy(() -> service.deliverClaimed(request, dispatchToken)).hasMessage("提交失败");
        verify(sender, never()).send(any());
        List<NotificationExternalGuard.Guard> occupied = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> externalGuard.tryAcquire("EMAIL", "occupied@example.com")).toList();
        try { assertThat(occupied).doesNotContainNull(); }
        finally { occupied.stream().filter(java.util.Objects::nonNull).forEach(NotificationExternalGuard.Guard::close); }
    }

    /** @return 使用随机事实 ID 的完整规则来源请求 */
    private static RuleNotificationDeliveryRequest request(String channel) {
        return RuleNotificationDeliveryRequest.rule(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), channel,
                channel.equals("webhook") ? "https://example.com/hook" : "ops@example.com",
                "设备告警", "温度超过阈值", "trace-s9-3", 1, NOW);
    }
}
