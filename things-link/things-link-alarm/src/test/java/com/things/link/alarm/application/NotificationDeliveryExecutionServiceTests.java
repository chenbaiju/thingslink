package com.things.link.alarm.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.things.link.alarm.domain.AlarmNotificationDelivery;
import com.things.link.alarm.domain.AlarmNotificationRepository;
import com.things.link.alarm.domain.AlarmInstance;
import com.things.link.alarm.domain.AlarmInstanceRepository;
import com.things.link.alarm.domain.AlarmRule;
import com.things.link.alarm.domain.NotificationChannel;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.message.NotificationDeliveryRequest;
import com.things.link.support.resilience.NotificationExternalGuard;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** 投递状态机验证成功、有限重试、死信与重复消息吸收。 */
class NotificationDeliveryExecutionServiceTests {
    /** 仓储模拟。 */
    private AlarmNotificationRepository repository;
    /** 渠道模拟。 */
    private NotificationChannelSender sender;
    /** PUSH sender 模拟。 */
    private PushNotificationSender pushSender;
    /** enduser 发送前授权模拟。 */
    private AlarmPushDeliveryAuthorizationPort pushAuthorization;
    /** 告警实例仓储模拟。 */
    private AlarmInstanceRepository instanceRepository;
    /** 被测服务。 */
    private NotificationDeliveryExecutionService service;
    /** 固定事实。 */
    private AlarmNotificationDelivery delivery;
    /** 固定信封。 */
    private NotificationDeliveryRequest request;
    /** 固定 dispatch 租约。 */
    private UUID dispatchToken;
    /** 渠道隔离门面；用于证明容量拒绝回滚attempt后按原token退避。 */
    private NotificationExternalGuard externalGuard;

    /** 显式逻辑事务替身，rollbackOnly只断言编排；物理回滚另由Bootstrap真实PG覆盖。 */
    private TransactionTemplate transactions;
    /** 收集每个逻辑事务状态，不能把mock成功当作真实提交证明。 */
    private final java.util.List<SimpleTransactionStatus> transactionStates = new java.util.ArrayList<>();
    /** 许可替身默认明确允许，冻结案例单独拒绝。 */
    private ProjectLifecycleAccessService lifecycle;

    /** 建立同步执行的短事务替身。 */
    @BeforeEach
    void setUp() {
        repository = mock(AlarmNotificationRepository.class);
        sender = mock(NotificationChannelSender.class);
        pushSender = mock(PushNotificationSender.class);
        pushAuthorization = mock(AlarmPushDeliveryAuthorizationPort.class);
        instanceRepository = mock(AlarmInstanceRepository.class);
        when(sender.channel()).thenReturn(NotificationChannel.EMAIL);
        lifecycle = mock(ProjectLifecycleAccessService.class);
        when(lifecycle.lockActiveForWrite(any(), any())).thenReturn(true);
        transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            SimpleTransactionStatus state = new SimpleTransactionStatus();
            transactionStates.add(state);
            return callback.doInTransaction(state);
        });
        doAnswer(invocation -> {
                    Consumer<TransactionStatus> callback = invocation.getArgument(0);
                    callback.accept(new SimpleTransactionStatus());
                    return null;
                })
                .when(transactions)
                .executeWithoutResult(any());
        Instant now = Instant.parse("2026-08-09T12:00:00Z");
        delivery = new AlarmNotificationDelivery(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null, NotificationChannel.EMAIL,
                "a@example.com", "主题", "正文", 1, AlarmNotificationDelivery.Status.QUEUED,
                0, 3, null, UUID.randomUUID(), null, null, now, now, null);
        request = new NotificationDeliveryRequest(
                delivery.lastOutboxEventId(), delivery.tenantId(), delivery.projectId(), delivery.id(),
                delivery.instanceId(), delivery.alarmEventId(), 1, now, "trace");
        dispatchToken = UUID.randomUUID();
        NotificationDeliveryProperties properties = new NotificationDeliveryProperties(
                Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofMinutes(5), 10,
                Duration.ofSeconds(30),
                new NotificationDeliveryProperties.Webhook(
                        "test-webhook-signing-secret-at-least-32-bytes"));
        externalGuard = new NotificationExternalGuard();
        service = new NotificationDeliveryExecutionService(
                repository, List.of(sender), List.of(pushSender), pushAuthorization, instanceRepository,
                transactions, Clock.fixed(now, ZoneOffset.UTC),
                properties, new AlarmMetrics(new SimpleMeterRegistry()), externalGuard, lifecycle);
        when(repository.findDelivery(delivery.projectId(), delivery.id()))
                .thenReturn(Optional.of(delivery));
        when(repository.startDelivery(
                        eq(delivery.projectId()), eq(delivery.id()), eq(1), eq(dispatchToken), any(), any()))
                .thenReturn(true);
    }

    /** 供应商成功后推进 SUCCEEDED。 */
    @Test
    void marksSuccessfulProviderAttempt() {
        when(sender.send(delivery)).thenReturn("provider-1");
        service.deliverClaimed(request, dispatchToken);
        verify(repository).markDeliverySucceeded(
                eq(delivery.projectId()), eq(delivery.id()), eq(1), eq("provider-1"), any());
    }

    /** 第一次可恢复错误只登记下一次，不提前死信。 */
    @Test
    void schedulesBoundedRetry() {
        when(sender.send(delivery)).thenThrow(new NotificationSendException(
                NotificationSendException.Reason.SMTP_FAILURE, true, null));
        service.deliverClaimed(request, dispatchToken);
        verify(repository).markDeliveryRetry(
                eq(delivery.projectId()), eq(delivery.id()), eq(1), any(),
                eq("SMTP_FAILURE"), any());
        verify(repository, never()).markDeliveryDeadLetter(any(), any(), any(Integer.class), any(), any());
    }

    /** 永久错误直接进入 DEAD_LETTER，Kafka 不再承担业务重试。 */
    @Test
    void deadLettersPermanentFailure() {
        when(sender.send(delivery)).thenThrow(new NotificationSendException(
                NotificationSendException.Reason.WEBHOOK_CLIENT_ERROR, false, null));
        service.deliverClaimed(request, dispatchToken);
        verify(repository).markDeliveryDeadLetter(
                eq(delivery.projectId()), eq(delivery.id()), eq(1),
                eq("WEBHOOK_CLIENT_ERROR"), any());
    }

    /** 相同 Kafka 消息在 CAS 失败时不得再次触达外部渠道。 */
    @Test
    void absorbsDuplicateAttempt() {
        when(repository.startDelivery(
                        eq(delivery.projectId()), eq(delivery.id()), eq(1), eq(dispatchToken), any(), any()))
                .thenReturn(false);
        service.deliverClaimed(request, dispatchToken);
        verify(sender, never()).send(any());
    }

    /** EMAIL bulkhead 已满时只释放 dispatch 租约，不增加业务 attempt，也不触达供应商。 */
    @Test
    void bulkheadRejectionDoesNotConsumeAttempt() {
        List<NotificationExternalGuard.Guard> occupied = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> externalGuard.tryAcquire("EMAIL", "occupied@example.com"))
                .toList();
        try {
            service.deliverClaimed(request, dispatchToken);

            verify(repository).releaseDispatch(
                    eq(delivery.projectId()), eq(delivery.id()), eq(dispatchToken), eq(Duration.ofSeconds(1)));
            verify(repository).startDelivery(any(), any(), any(Integer.class), any(), any(), any());
            assertThat(transactionStates).anyMatch(SimpleTransactionStatus::isRollbackOnly);
            verify(sender, never()).send(any());
        } finally {
            occupied.forEach(NotificationExternalGuard.Guard::close);
        }
    }

    /** 解绑先于发送前复核提交时进入显式跳过终态，不触达厂商。 */
    @Test
    void skipsPushWhenAuthorizationWasRevoked() {
        AlarmInstance instance = usePushDelivery();
        when(instanceRepository.findById(delivery.projectId(), delivery.instanceId()))
                .thenReturn(Optional.of(instance));
        when(pushAuthorization.authorize(
                        delivery.tenantId(), delivery.projectId(), instance.originatorId(),
                        delivery.appUserId(), delivery.pushTokenId()))
                .thenReturn(Optional.empty());
        when(repository.markDeliverySkippedAuthorization(
                        eq(delivery.projectId()), eq(delivery.id()), eq(1), any()))
                .thenReturn(true);

        service.deliverClaimed(request, dispatchToken);

        verify(repository).markDeliverySkippedAuthorization(
                eq(delivery.projectId()), eq(delivery.id()), eq(1), any());
        verify(pushSender, never()).send(any(), any());
        verify(repository, never()).markDeliveryRetry(any(), any(), any(Integer.class), any(), any(), any());
    }

    /** 复核命中后才把短生命周期明文目标交给 PUSH sender。 */
    @Test
    void sendsPushOnlyAfterAuthorizationRecheck() {
        AlarmInstance instance = usePushDelivery();
        AlarmPushDeliveryAuthorizationPort.AuthorizedPushTarget target =
                new AlarmPushDeliveryAuthorizationPort.AuthorizedPushTarget("MOCK", "mock:success");
        when(instanceRepository.findById(delivery.projectId(), delivery.instanceId()))
                .thenReturn(Optional.of(instance));
        when(pushAuthorization.authorize(
                        delivery.tenantId(), delivery.projectId(), instance.originatorId(),
                        delivery.appUserId(), delivery.pushTokenId()))
                .thenReturn(Optional.of(target));
        when(pushSender.send(delivery, target)).thenReturn("mock-push-id");

        service.deliverClaimed(request, dispatchToken);

        verify(pushSender).send(delivery, target);
        verify(repository).markDeliverySucceeded(
                eq(delivery.projectId()), eq(delivery.id()), eq(1), eq("mock-push-id"), any());
    }

    /** 单个安装实例的 PUSH 暂态失败只推进自己的有限重试事实。 */
    @Test
    void schedulesRetryForOneAuthorizedPushInstallation() {
        AlarmInstance instance = usePushDelivery();
        AlarmPushDeliveryAuthorizationPort.AuthorizedPushTarget target =
                new AlarmPushDeliveryAuthorizationPort.AuthorizedPushTarget("MOCK", "mock:retryable");
        when(instanceRepository.findById(delivery.projectId(), delivery.instanceId()))
                .thenReturn(Optional.of(instance));
        when(pushAuthorization.authorize(
                        delivery.tenantId(), delivery.projectId(), instance.originatorId(),
                        delivery.appUserId(), delivery.pushTokenId()))
                .thenReturn(Optional.of(target));
        when(pushSender.send(delivery, target)).thenThrow(new NotificationSendException(
                NotificationSendException.Reason.PUSH_PROVIDER_TRANSIENT, true, null));

        service.deliverClaimed(request, dispatchToken);

        verify(repository).markDeliveryRetry(
                eq(delivery.projectId()), eq(delivery.id()), eq(1), any(),
                eq("PUSH_PROVIDER_TRANSIENT"), any());
        verify(repository, never()).markDeliveryDeadLetter(
                any(), any(), any(Integer.class), any(), any());
    }

    /** 冻结必须先于容量门禁，渠道已满也不能让QUEUED永久释放循环。 */
    @Test
    void freezesBeforeSaturatedGuardWithoutSenderOrChannelRetry() {
        when(lifecycle.lockActiveForWrite(delivery.tenantId(), delivery.projectId())).thenReturn(false);
        when(repository.markDeliveryDeadLetter(any(), any(), eq(1), eq("PROJECT_FROZEN"), any())).thenReturn(true);
        List<NotificationExternalGuard.Guard> occupied = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> externalGuard.tryAcquire("EMAIL", "occupied@example.com")).toList();
        try {
            service.deliverClaimed(request, dispatchToken);
            var order = inOrder(repository, lifecycle);
            order.verify(repository).startDelivery(any(), any(), eq(1), eq(dispatchToken), any(), any());
            order.verify(lifecycle).lockActiveForWrite(delivery.tenantId(), delivery.projectId());
            order.verify(repository).markDeliveryDeadLetter(any(), any(), eq(1), eq("PROJECT_FROZEN"), any());
            verify(repository, never()).releaseDispatch(any(), any(), any(), any());
            verify(repository, never()).markDeliveryRetry(any(), any(), any(Integer.class), any(), any(), any());
            verify(sender, never()).send(any());
        } finally { occupied.forEach(NotificationExternalGuard.Guard::close); }
    }

    /** 终态CAS失败不能伪称收束成功，也不能降级为渠道重试。 */
    @Test
    void falseFrozenCasPropagatesInsteadOfSchedulingChannelRetry() {
        when(lifecycle.lockActiveForWrite(any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.deliverClaimed(request, dispatchToken))
                .isInstanceOf(IllegalStateException.class).hasMessage("冻结通知终态CAS失败");
        verify(sender, never()).send(any());
        verify(repository, never()).markDeliveryRetry(any(), any(), any(Integer.class), any(), any(), any());
    }

    /** 基础设施错误必须穿透启动事务，不产生INVALID_DELIVERY渠道状态。 */
    @Test
    void permissionSqlFailurePropagatesUnchanged() {
        IllegalStateException sqlFailure = new IllegalStateException("permission SQL failed");
        when(lifecycle.lockActiveForWrite(any(), any())).thenThrow(sqlFailure);
        assertThatThrownBy(() -> service.deliverClaimed(request, dispatchToken)).isSameAs(sqlFailure);
        verify(sender, never()).send(any());
        verify(repository, never()).markDeliveryRetry(any(), any(), any(Integer.class), any(), any(), any());
    }

    /** 当前token可以start但eventID错误时回滚；旧tokenstart失败仍保持幂等no-op。 */
    @Test
    void eventMismatchRejectsOnlyAfterSuccessfulStart() {
        NotificationDeliveryRequest wrong = new NotificationDeliveryRequest(UUID.randomUUID(), request.tenantId(),
                request.projectId(), request.deliveryId(), request.alarmInstanceId(), request.alarmEventId(),
                request.attemptNo(), request.requestedAt(), request.traceId());
        assertThatThrownBy(() -> service.deliverClaimed(wrong, dispatchToken))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("通知请求与投递事实不匹配");
        when(repository.startDelivery(any(), any(), eq(1), eq(dispatchToken), any(), any())).thenReturn(false);
        service.deliverClaimed(wrong, dispatchToken);
        verify(lifecycle, never()).lockActiveForWrite(any(), any());
        verify(sender, never()).send(any());
    }

    /** 调用方事务可能在sender之后才回滚，非PUSH明确拒绝这种组合。 */
    @Test
    void rejectsAmbientTransactionBeforeMutatingAttempt() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.deliverClaimed(request, dispatchToken))
                    .isInstanceOf(IllegalStateException.class).hasMessage("非PUSH发送不得加入调用方事务");
            verify(repository, never()).startDelivery(any(), any(), any(Integer.class), any(), any(), any());
            verify(sender, never()).send(any());
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }

    /** 模拟回调后提交失败，资源必须全部可重新取得，且异常不能进入发送结果catch。 */
    @Test
    void commitFailureReleasesAllUnsentCapacityAndPropagates() {
        IllegalStateException commitFailure = new IllegalStateException("commit failed");
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            Object result = callback.doInTransaction(new SimpleTransactionStatus());
            if (result != null && !(result instanceof AlarmNotificationDelivery)) throw commitFailure;
            return result;
        }).when(transactions).execute(any());
        assertThatThrownBy(() -> service.deliverClaimed(request, dispatchToken)).isSameAs(commitFailure);
        List<NotificationExternalGuard.Guard> recovered = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> externalGuard.tryAcquire("EMAIL", "recovered@example.com")).toList();
        try {
            assertThat(recovered).doesNotContainNull();
            verify(sender, never()).send(any());
        } finally { recovered.stream().filter(java.util.Objects::nonNull).forEach(NotificationExternalGuard.Guard::close); }
    }

    /** 将固定事实切换为 PUSH，并返回同一实例对应的设备来源。 */
    private AlarmInstance usePushDelivery() {
        Instant now = delivery.createdAt();
        delivery = new AlarmNotificationDelivery(
                delivery.id(), delivery.tenantId(), delivery.projectId(), delivery.instanceId(),
                delivery.alarmEventId(), delivery.bindingId(), null, UUID.randomUUID(), UUID.randomUUID(),
                NotificationChannel.PUSH, "PUSH", delivery.subjectSnapshot(), delivery.bodySnapshot(),
                delivery.templateVersion(), AlarmNotificationDelivery.Status.QUEUED, 0, 3, null,
                delivery.lastOutboxEventId(), null, null, now, now, null);
        request = new NotificationDeliveryRequest(
                request.eventId(), delivery.tenantId(), delivery.projectId(), delivery.id(),
                delivery.instanceId(), delivery.alarmEventId(), 1, now, request.traceId());
        when(repository.findDelivery(delivery.projectId(), delivery.id())).thenReturn(Optional.of(delivery));
        when(repository.startDelivery(
                        eq(delivery.projectId()), eq(delivery.id()), eq(1), eq(dispatchToken), any(), any()))
                .thenReturn(true);
        return new AlarmInstance(
                delivery.instanceId(), delivery.tenantId(), delivery.projectId(), UUID.randomUUID(),
                AlarmRule.OriginatorType.DEVICE, UUID.randomUUID(), "TEMPERATURE",
                AlarmRule.Severity.WARNING, AlarmInstance.ConditionState.ACTIVE,
                AlarmInstance.AckState.UNACKNOWLEDGED, null, now, null, now, null, null, null,
                now, now, 42.0, 0, now, now);
    }
}
