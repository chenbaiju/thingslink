package com.things.link.alarm.application;

import com.things.link.support.tenant.DataPlaneDatabase;
import com.things.link.alarm.domain.AlarmNotificationRepository;
import com.things.link.alarm.domain.AlarmNotificationDelivery;
import com.things.link.alarm.domain.NotificationChannel;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.NotificationDeliveryRequest;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.outbox.OutboxEvent;
import com.things.link.support.outbox.TransactionalOutboxRepository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** 多实例安全地把到期重试重新写入事务 Outbox。 */
@Component
@ConditionalOnProperty(
        prefix = "things-link.notification.retry",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@DataPlaneDatabase
public class NotificationRetryScheduler {
    /** 投递事实仓储。 */
    private final AlarmNotificationRepository repository;
    /** 通用事务 Outbox。 */
    private final TransactionalOutboxRepository outboxRepository;
    /** 短事务模板。 */
    private final TransactionTemplate transactions;
    /** 冻结消息序列化器。 */
    private final ObjectMapper objectMapper;
    /** 可测试时钟。 */
    private final Clock clock;
    /** 领取上限和租约。 */
    private final NotificationDeliveryProperties properties;

    /** ADR0069：仅非PUSH重排队以本域持久归属复核项目持续许可。 */
    private final ProjectLifecycleAccessService lifecycle;

    /** 装配调度依赖。 */
    public NotificationRetryScheduler(
            AlarmNotificationRepository repository,
            TransactionalOutboxRepository outboxRepository,
            TransactionTemplate transactions,
            ObjectMapper objectMapper,
            Clock clock,
            NotificationDeliveryProperties properties,
            ProjectLifecycleAccessService lifecycle) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.properties = properties;
        this.lifecycle = java.util.Objects.requireNonNull(lifecycle, "项目生命周期许可不能为空");
    }

    /** 租约只包围数据库重排队，不跨越 Kafka 或外部供应商调用。 */
    @Scheduled(fixedDelayString = "${things-link.notification.retry.scan-millis:1000}",
            scheduler = "notificationLifecycleScheduler")
    public void enqueueDueRetries() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("通知重排队不得加入调用方事务");
        }
        AlarmNotificationRepository.RetryClaim claim =
                repository.claimRetries(properties.retryBatchSize(), properties.retryLease());
        for (AlarmNotificationRepository.RetryCandidate candidate : claim.deliveries()) {
            enqueue(claim.leaseToken(), candidate);
        }
    }

    /** 单条失败终止本轮；尚未处理的候选在租约过期后由后续扫描接管。 */
    private void enqueue(UUID leaseToken, AlarmNotificationRepository.RetryCandidate candidate) {
        TenantScope previous = TenantContext.current().orElse(null);
        TenantContext.set(
                new TenantScope(candidate.tenantId(), candidate.projectId(), candidate.tenantId()));
        try {
            transactions.executeWithoutResult(status -> {
                Instant now = clock.instant();
                AlarmNotificationDelivery delivery = repository.findDelivery(candidate.projectId(), candidate.id())
                        .orElse(null);
                if (delivery == null) return;
                if (!delivery.tenantId().equals(candidate.tenantId())
                        || !delivery.projectId().equals(candidate.projectId()) || !delivery.id().equals(candidate.id())
                        || !delivery.instanceId().equals(candidate.instanceId())
                        || !delivery.alarmEventId().equals(candidate.alarmEventId())) {
                    throw new IllegalArgumentException("通知重试候选与持久身份不匹配");
                }
                // 这里只核对身份与尝试关系；最终SQL仍须用数据库当前时间检查租约，不能把只读当行锁。
                boolean retryScheduled = delivery.status() == AlarmNotificationDelivery.Status.RETRY_SCHEDULED
                        && delivery.attemptCount() < delivery.maxAttempts()
                        && candidate.nextAttemptNo() == delivery.attemptCount() + 1;
                boolean crashedSending = delivery.status() == AlarmNotificationDelivery.Status.SENDING
                        && candidate.nextAttemptNo() == delivery.attemptCount();
                if (!retryScheduled && !crashedSending) return;
                if (delivery.channel() != NotificationChannel.PUSH
                        && !lifecycle.lockActiveForWrite(delivery.tenantId(), delivery.projectId())) {
                    repository.stopClaimedRetryForProjectFreeze(delivery.tenantId(), delivery.projectId(),
                            delivery.id(), leaseToken, candidate.nextAttemptNo(), now);
                    return;
                }
                UUID outboxId = Uuid7.generate();
                String traceId = "notification-retry-" + outboxId;
                NotificationDeliveryRequest request =
                        new NotificationDeliveryRequest(
                                outboxId,
                                candidate.tenantId(),
                                candidate.projectId(),
                                candidate.id(),
                                candidate.instanceId(),
                                candidate.alarmEventId(),
                                candidate.nextAttemptNo(),
                                now,
                                traceId);
                if (!repository.requeueClaimedRetry(
                        candidate.projectId(), candidate.id(), leaseToken, outboxId,
                        candidate.nextAttemptNo(), now)) {
                    return;
                }
                outboxRepository.append(
                        new OutboxEvent(
                                outboxId,
                                candidate.tenantId(),
                                candidate.projectId(),
                                "ALARM_NOTIFICATION_DELIVERY",
                                candidate.id(),
                                NotificationDeliveryRequest.EVENT_TYPE,
                                candidate.id().toString(),
                                objectMapper.writeValueAsString(request),
                                traceId,
                                now));
            });
        } finally {
            if (previous == null) TenantContext.clear();
            else TenantContext.set(previous);
        }
    }
}
