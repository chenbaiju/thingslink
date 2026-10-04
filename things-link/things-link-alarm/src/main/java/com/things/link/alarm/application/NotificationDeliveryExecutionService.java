package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmNotificationDelivery;
import com.things.link.alarm.domain.AlarmNotificationRepository;
import com.things.link.alarm.domain.AlarmInstance;
import com.things.link.alarm.domain.AlarmInstanceRepository;
import com.things.link.alarm.domain.AlarmRule;
import com.things.link.alarm.domain.NotificationChannel;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.message.NotificationDeliveryRequest;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.resilience.NotificationExternalGuard;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 消费通知请求并在事务边界之外执行真实外部调用。 */
@Service
public class NotificationDeliveryExecutionService {
    /** 投递事实仓储。 */
    private final AlarmNotificationRepository repository;
    /** 固定渠道到真实适配器的白名单。 */
    private final Map<com.things.link.alarm.domain.NotificationChannel, NotificationChannelSender>
            senders;
    /** PUSH 专用 sender；生产未装配真实 provider 时为空且 fail-closed。 */
    private final PushNotificationSender pushSender;
    /** enduser 实现的发送前四项授权复核。 */
    private final AlarmPushDeliveryAuthorizationPort pushAuthorization;
    /** 从可信告警实例解析不可变来源设备，禁止让 enduser 反查 alarm 表。 */
    private final AlarmInstanceRepository instanceRepository;
    /** 每次状态 CAS 使用独立短事务，外部网络调用不占用数据库事务。 */
    private final TransactionTemplate transactions;
    /** 重试和恢复时钟。 */
    private final Clock clock;
    /** 有界请求与重试参数。 */
    private final NotificationDeliveryProperties properties;
    /** 低基数投递指标。 */
    private final AlarmMetrics metrics;
    /** 渠道 bulkhead 与按 provider/origin 隔离的有界断路器。 */
    private final NotificationExternalGuard externalGuard;

    /** ADR0069：非PUSH在原启动短事务取得项目许可，外部调用不持项目锁。 */
    private final ProjectLifecycleAccessService lifecycle;

    /** 装配所有固定渠道发送器。 */
    public NotificationDeliveryExecutionService(
            AlarmNotificationRepository repository,
            List<NotificationChannelSender> senders,
            List<PushNotificationSender> pushSenders,
            AlarmPushDeliveryAuthorizationPort pushAuthorization,
            AlarmInstanceRepository instanceRepository,
            TransactionTemplate transactions,
            Clock clock,
            NotificationDeliveryProperties properties,
            AlarmMetrics metrics,
            NotificationExternalGuard externalGuard,
            ProjectLifecycleAccessService lifecycle) {
        this.repository = repository;
        this.senders = new EnumMap<>(com.things.link.alarm.domain.NotificationChannel.class);
        for (NotificationChannelSender sender : senders) {
            if (this.senders.put(sender.channel(), sender) != null) {
                throw new IllegalStateException("同一通知渠道只能装配一个发送器");
            }
        }
        if (pushSenders.size() > 1) {
            throw new IllegalStateException("PUSH 只能装配一个发送器");
        }
        this.pushSender = pushSenders.isEmpty() ? null : pushSenders.getFirst();
        this.pushAuthorization = pushAuthorization;
        this.instanceRepository = instanceRepository;
        this.transactions = transactions;
        this.clock = clock;
        this.properties = properties;
        this.metrics = metrics;
        this.externalGuard = externalGuard;
        this.lifecycle = Objects.requireNonNull(lifecycle, "项目生命周期许可不能为空");
    }

    /**
     * 执行一次至少一次消息。终态和相同 attempt 重放由 CAS 吸收；预期渠道失败持久化后即返回，
     * 避免 Kafka 分区被一个不可达收件人长期阻塞。
     */
    public void deliverClaimed(NotificationDeliveryRequest request, java.util.UUID dispatchLeaseToken) {
        validate(request);
        Instant startedAt = clock.instant();
        AlarmNotificationDelivery current = inScope(request, () -> transactions.execute(status -> repository
                .findDelivery(request.projectId(), request.deliveryId())
                .orElseThrow(() -> new IllegalArgumentException("通知投递事实不存在"))));
        if (!matches(current, request)) {
            throw new IllegalArgumentException("通知请求与投递事实不匹配");
        }
        if (current.channel() != NotificationChannel.PUSH) {
            deliverNonPush(request, dispatchLeaseToken);
            return;
        }
        deliverPush(request, dispatchLeaseToken, current, startedAt);
    }

    /** ADR0069：PUSH沿原guard、授权及跳过合同，不改授权基础设施异常的既有重试语义。 */
    private void deliverPush(NotificationDeliveryRequest request, java.util.UUID dispatchLeaseToken,
                             AlarmNotificationDelivery current, Instant startedAt) {
        NotificationExternalGuard.Guard guard = externalGuard.tryAcquire(
                current.channel().name(), current.targetSnapshot());
        if (guard == null) {
            inScope(request, () -> {
                transactions.executeWithoutResult(status -> repository.releaseDispatch(
                        request.projectId(), request.deliveryId(), dispatchLeaseToken, Duration.ofSeconds(1)));
                return null;
            });
            return;
        }
        AlarmNotificationDelivery delivery;
        try {
            delivery = inScope(request, () -> transactions.execute(status -> {
                Instant recoveryAt = startedAt.plus(properties.requestTimeout()).plusSeconds(30);
                if (!repository.startDelivery(
                        request.projectId(), request.deliveryId(), request.attemptNo(),
                        dispatchLeaseToken, startedAt, recoveryAt)) {
                    return null;
                }
                return repository.findDelivery(request.projectId(), request.deliveryId()).orElseThrow();
            }));
        } catch (RuntimeException exception) {
            guard.ignoredFailure();
            guard.close();
            throw exception;
        }
        sendAdmitted(request, delivery, guard);
    }

    /**
     * ADR0069决策1/2：先判定冻结，再本地容量；容量拒绝回滚start，提交失败撤销未发送许可。
     * 初次读取只区分PUSH，所有可信身份在本事务重新读取并于start后再次核验，不能信任旧快照。
     */
    private void deliverNonPush(NotificationDeliveryRequest request, java.util.UUID dispatchLeaseToken) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("非PUSH发送不得加入调用方事务");
        }
        NotificationExternalGuard.Guard[] acquired = new NotificationExternalGuard.Guard[1];
        Admission admission;
        try {
            admission = inScope(request, () -> transactions.execute(status -> {
                AlarmNotificationDelivery before = repository.findDelivery(request.projectId(), request.deliveryId())
                        .orElseThrow(() -> new IllegalArgumentException("通知投递事实不存在"));
                if (!matches(before, request) || before.channel() == NotificationChannel.PUSH) {
                    throw new IllegalArgumentException("通知请求与投递事实不匹配");
                }
                Instant startedAt = clock.instant();
                Instant recoveryAt = startedAt.plus(properties.requestTimeout()).plusSeconds(30);
                if (!repository.startDelivery(request.projectId(), request.deliveryId(), request.attemptNo(),
                        dispatchLeaseToken, startedAt, recoveryAt)) {
                    return null;
                }
                AlarmNotificationDelivery started = repository.findDelivery(request.projectId(), request.deliveryId())
                        .orElseThrow(() -> new IllegalStateException("启动后的通知投递事实不存在"));
                requireNonPushIdentity(started, request);
                if (!lifecycle.lockActiveForWrite(started.tenantId(), started.projectId())) {
                    if (!repository.markDeliveryDeadLetter(started.projectId(), started.id(), request.attemptNo(),
                            "PROJECT_FROZEN", clock.instant())) {
                        throw new IllegalStateException("冻结通知终态CAS失败");
                    }
                    return null;
                }
                acquired[0] = externalGuard.tryAcquire(started.channel().name(), started.targetSnapshot());
                if (acquired[0] == null) {
                    status.setRollbackOnly();
                    return new Admission(null, null, true);
                }
                return new Admission(started, acquired[0], false);
            }));
        } catch (RuntimeException exception) {
            if (acquired[0] != null) {
                acquired[0].abortBeforeSend();
                acquired[0].close();
            }
            throw exception;
        }
        if (admission == null) return;
        if (admission.releaseDispatch()) {
            inScope(request, () -> {
                transactions.executeWithoutResult(status -> repository.releaseDispatch(
                        request.projectId(), request.deliveryId(), dispatchLeaseToken, Duration.ofSeconds(1)));
                return null;
            });
            return;
        }
        sendAdmitted(request, admission.delivery(), admission.guard());
    }

    /** 当前Outbox事件也是持久启动身份，旧token或篡改信封不得借新尝试外发。 */
    private static void requireNonPushIdentity(AlarmNotificationDelivery delivery, NotificationDeliveryRequest request) {
        if (!matches(delivery, request) || delivery.channel() == NotificationChannel.PUSH
                || !Objects.equals(delivery.lastOutboxEventId(), request.eventId())) {
            throw new IllegalArgumentException("通知请求与投递事实不匹配");
        }
    }

    /** @param delivery 已提交的启动事实 @param guard 已获准且尚未使用的本地资源 @param releaseDispatch 是否在start回滚后按原token退避 */
    private record Admission(AlarmNotificationDelivery delivery, NotificationExternalGuard.Guard guard,
                             boolean releaseDispatch) { }

    /** 共享原外发与结果处理，非PUSH只有准入事务提交成功后才能到达这里。 */
    private void sendAdmitted(NotificationDeliveryRequest request, AlarmNotificationDelivery delivery,
                              NotificationExternalGuard.Guard guard) {
        try (guard) {
            if (delivery == null) {
                guard.ignoredFailure();
                return;
            }

            long begin = System.nanoTime();
            try {
                String providerId = send(delivery, request, begin, guard);
                if (providerId == null) {
                    return;
                }
                inScope(request, () -> {
                    transactions.executeWithoutResult(status -> repository.markDeliverySucceeded(
                            request.projectId(), request.deliveryId(), request.attemptNo(),
                            providerId, clock.instant()));
                    return null;
                });
                guard.success();
                metrics.recordNotificationDelivery(
                        delivery.channel(), AlarmMetrics.NotificationDeliveryResult.SUCCEEDED,
                        System.nanoTime() - begin);
            } catch (NotificationSendException exception) {
                if (exception.retryable()) {
                    guard.retryableFailure();
                } else {
                    guard.ignoredFailure();
                }
                finishFailure(request, delivery, exception, begin);
            } catch (RuntimeException exception) {
                guard.retryableFailure();
                finishFailure(
                        request,
                        delivery,
                        new NotificationSendException(
                                NotificationSendException.Reason.INVALID_DELIVERY, true, exception),
                        begin);
            }
        }
    }

    /**
     * Kafka 入口只确认数据库事实已经可靠接管，不执行网络调用；失败将阻止 offset 提交。
     */
    public void accept(NotificationDeliveryRequest request) {
        validate(request);
        boolean accepted = inScope(request, () -> transactions.execute(status -> {
            AlarmNotificationDelivery current = repository
                    .findDelivery(request.projectId(), request.deliveryId())
                    .orElse(null);
            if (current == null || !matches(current, request)) {
                throw new IllegalArgumentException("通知请求与投递事实不匹配");
            }
            return repository.acceptQueuedDelivery(
                    request.projectId(), request.deliveryId(), request.eventId(), request.attemptNo());
        }));
        if (!accepted) {
            // 已进入终态的 Kafka 重放可以安全 ack；其余状态不允许伪造为已持久交接。
            AlarmNotificationDelivery current = inScope(request, () -> repository
                    .findDelivery(request.projectId(), request.deliveryId())
                    .orElseThrow(() -> new IllegalArgumentException("通知投递事实不存在")));
            if (current.status() != AlarmNotificationDelivery.Status.SUCCEEDED
                    && current.status() != AlarmNotificationDelivery.Status.DEAD_LETTER
                    && current.status() != AlarmNotificationDelivery.Status.SKIPPED_AUTHORIZATION) {
                throw new IllegalStateException("通知请求尚未由可领取事实接管");
            }
        }
    }

    /**
     * 路由一次外部调用；PUSH 在调用前最后一个短事务内复核授权，空结果直接终态跳过。
     *
     * @return provider 消息 ID；授权失效且已终态化时返回 {@code null}
     */
    private String send(
            AlarmNotificationDelivery delivery,
            NotificationDeliveryRequest request,
            long begin,
            NotificationExternalGuard.Guard guard) {
        if (delivery.channel() != NotificationChannel.PUSH) {
            NotificationChannelSender sender = senders.get(delivery.channel());
            if (sender == null) {
                throw new NotificationSendException(
                        NotificationSendException.Reason.INVALID_DELIVERY, false, null);
            }
            return sender.send(delivery);
        }

        Optional<AlarmPushDeliveryAuthorizationPort.AuthorizedPushTarget> target =
                authorizePush(request, delivery);
        if (target.isEmpty()) {
            Instant now = clock.instant();
            boolean skipped = Boolean.TRUE.equals(inScope(request, () -> transactions.execute(status ->
                    repository.markDeliverySkippedAuthorization(
                            request.projectId(), request.deliveryId(), request.attemptNo(), now))));
            if (!skipped) {
                throw new IllegalStateException("PUSH 授权失效终态 CAS 失败");
            }
            guard.ignoredFailure();
            metrics.recordNotificationDelivery(
                    delivery.channel(),
                    AlarmMetrics.NotificationDeliveryResult.SKIPPED_AUTHORIZATION,
                    System.nanoTime() - begin);
            return null;
        }
        if (pushSender == null) {
            throw new NotificationSendException(
                    NotificationSendException.Reason.PUSH_PROVIDER_UNAVAILABLE, false, null);
        }
        return pushSender.send(delivery, target.orElseThrow());
    }

    /** 在同一个短事务中解析设备并调用 enduser 复核；外部 sender 在事务结束后才执行。 */
    private Optional<AlarmPushDeliveryAuthorizationPort.AuthorizedPushTarget> authorizePush(
            NotificationDeliveryRequest request, AlarmNotificationDelivery delivery) {
        return inScope(request, () -> transactions.execute(status -> {
            AlarmInstance instance = instanceRepository
                    .findById(request.projectId(), request.alarmInstanceId())
                    .orElseThrow(() -> new NotificationSendException(
                            NotificationSendException.Reason.INVALID_DELIVERY, false, null));
            if (!instance.tenantId().equals(request.tenantId())
                    || instance.originatorType() != AlarmRule.OriginatorType.DEVICE
                    || delivery.appUserId() == null
                    || delivery.pushTokenId() == null) {
                throw new NotificationSendException(
                        NotificationSendException.Reason.INVALID_DELIVERY, false, null);
            }
            return pushAuthorization.authorize(
                    request.tenantId(), request.projectId(), instance.originatorId(),
                    delivery.appUserId(), delivery.pushTokenId());
        }));
    }

    /** 将固定失败分类推进到重试或死信；异常正文绝不写库。 */
    private void finishFailure(
            NotificationDeliveryRequest request,
            AlarmNotificationDelivery delivery,
            NotificationSendException failure,
            long begin) {
        Instant now = clock.instant();
        boolean retry = failure.retryable() && request.attemptNo() < delivery.maxAttempts();
        inScope(request, () -> {
            transactions.executeWithoutResult(status -> {
                if (retry) {
                    repository.markDeliveryRetry(
                            request.projectId(), request.deliveryId(), request.attemptNo(),
                            now.plus(retryDelay(request.deliveryId(), request.attemptNo())),
                            failure.reason().name(), now);
                } else {
                    repository.markDeliveryDeadLetter(
                            request.projectId(), request.deliveryId(), request.attemptNo(),
                            failure.reason().name(), now);
                }
            });
            return null;
        });
        metrics.recordNotificationDelivery(
                delivery.channel(),
                retry
                        ? AlarmMetrics.NotificationDeliveryResult.RETRY_SCHEDULED
                        : AlarmMetrics.NotificationDeliveryResult.DEAD_LETTER,
                System.nanoTime() - begin);
    }

    /** @return 规则化退避加不超过 15% 的确定性抖动，测试和多实例结果一致。 */
    private Duration retryDelay(java.util.UUID deliveryId, int attemptNo) {
        Duration base = attemptNo <= 1 ? properties.firstRetryDelay() : properties.secondRetryDelay();
        long bound = Math.max(1L, base.toMillis() * 15L / 100L);
        long jitter = Math.floorMod(Objects.hash(deliveryId, attemptNo), bound);
        return base.plusMillis(jitter);
    }

    /** 消费者输入必须是完整正数尝试信封。 */
    private static void validate(NotificationDeliveryRequest request) {
        Objects.requireNonNull(request, "通知请求不能为空");
        if (request.eventId() == null || request.tenantId() == null || request.projectId() == null
                || request.deliveryId() == null || request.alarmInstanceId() == null
                || request.alarmEventId() == null || request.attemptNo() < 1) {
            throw new IllegalArgumentException("通知请求信封不完整");
        }
    }

    /** 防止伪造信封借 deliveryId 访问另一事故事实。 */
    private static boolean matches(
            AlarmNotificationDelivery delivery, NotificationDeliveryRequest request) {
        return delivery.id().equals(request.deliveryId())
                && delivery.projectId().equals(request.projectId())
                && delivery.tenantId().equals(request.tenantId())
                && delivery.instanceId().equals(request.alarmInstanceId())
                && delivery.alarmEventId().equals(request.alarmEventId());
    }

    /** 在异步线程显式恢复并可靠清理项目上下文。 */
    private static <T> T inScope(NotificationDeliveryRequest request, java.util.function.Supplier<T> work) {
        TenantScope previous = TenantContext.current().orElse(null);
        TenantContext.set(new TenantScope(request.tenantId(), request.projectId(), request.tenantId()));
        try {
            return work.get();
        } finally {
            if (previous == null) TenantContext.clear();
            else TenantContext.set(previous);
        }
    }
}
