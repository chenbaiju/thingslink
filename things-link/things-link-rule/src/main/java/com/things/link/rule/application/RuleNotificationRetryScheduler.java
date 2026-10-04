package com.things.link.rule.application;

import com.things.link.support.tenant.DataPlaneDatabase;
import com.things.link.project.application.ProjectLifecycleAccessService;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.RuleNotificationDeliveryRequest;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.outbox.OutboxEvent;
import com.things.link.support.outbox.TransactionalOutboxRepository;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** 多实例安全地把规则通知到期重试重新写入事务 Outbox；调度线程绝不直接调用外部渠道。 */
@Component
@DataPlaneDatabase
public class RuleNotificationRetryScheduler {

    /** 单次最多领取五十条，避免慢项目长期占满调度线程。 */
    private static final int RETRY_BATCH_SIZE = 50;

    /** 重排队短事务的跨项目领取租约。 */
    private static final Duration RETRY_LEASE = Duration.ofSeconds(30);

    /** 到期重试状态机仓储。 */
    private final RuleNotificationDeliveryStore store;

    /** 通用事务 Outbox。 */
    private final TransactionalOutboxRepository outboxRepository;

    /** 单条重排队短事务。 */
    private final TransactionTemplate transactions;

    /** 冻结消息序列化器。 */
    private final ObjectMapper objectMapper;

    /** 可测试 UTC 时钟。 */
    private final Clock clock;

    /** ADR0069：重排队或冻结收束前取得项目持续许可。 */
    private final ProjectLifecycleAccessService lifecycleAccessService;

    /**
     * @param store 负责跨项目互斥领取与重排队 CAS 的仓储
     * @param outboxRepository 通用事务 Outbox
     * @param transactions 单条短事务模板
     * @param objectMapper JSON 序列化器
     * @param clock UTC 时钟
     * @param lifecycleAccessService 原短事务项目持续许可
     */
    public RuleNotificationRetryScheduler(
            RuleNotificationDeliveryStore store,
            TransactionalOutboxRepository outboxRepository,
            TransactionTemplate transactions,
            ObjectMapper objectMapper,
            Clock clock,
            ProjectLifecycleAccessService lifecycleAccessService) {
        this.store = store;
        this.outboxRepository = outboxRepository;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.lifecycleAccessService = java.util.Objects.requireNonNull(lifecycleAccessService, "lifecycleAccessService");
    }

    /** 每秒扫描一次；每次真实尝试仍必须经过 Outbox 发布与 Kafka 消费。 */
    @Scheduled(fixedDelayString = "${things-link.rule.notification.retry.scan-millis:1000}",
            scheduler = "notificationLifecycleScheduler")
    public void enqueueDueRetries() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("规则通知重排队入口不能加入调用方事务");
        }
        RuleNotificationDeliveryStore.RetryClaim claim =
                store.claimDueRetries(RETRY_BATCH_SIZE, RETRY_LEASE);
        for (RuleNotificationDeliveryStore.RetryCandidate candidate : claim.deliveries()) {
            enqueue(claim.leaseToken(), candidate);
        }
    }

    /** 单条失败回滚自身事务并结束本轮扫描；剩余已领取候选在原租约到期后恢复。 */
    private void enqueue(
            UUID leaseToken,
            RuleNotificationDeliveryStore.RetryCandidate candidate) {
        TenantScope previous = TenantContext.current().orElse(null);
        TenantContext.set(new TenantScope(
                candidate.tenantId(), candidate.projectId(), candidate.tenantId()));
        try {
            transactions.executeWithoutResult(status -> {
                Instant now = clock.instant();
                // candidate来自本域真实claim；只读复核不持通知行锁，最终函数仍按租约/状态CAS。
                if (!store.matchesClaimedRetry(candidate, leaseToken, now)) return;
                if (!lifecycleAccessService.lockActiveForWrite(candidate.tenantId(), candidate.projectId())) {
                    store.stopClaimedRetryForProjectFreeze(candidate.tenantId(), candidate.projectId(), candidate.id(),
                            leaseToken, candidate.nextAttemptNo(), now);
                    return;
                }
                UUID outboxId = Uuid7.generate();
                RuleNotificationDeliveryRequest request = new RuleNotificationDeliveryRequest(
                        candidate.id(), candidate.tenantId(), candidate.projectId(), candidate.ruleId(),
                        candidate.ruleVersionId(), candidate.messageId(), candidate.sceneId(),
                        candidate.sceneVersionId(), candidate.sceneExecutionId(), candidate.deviceId(),
                        candidate.channel(), candidate.recipient(), candidate.subject(), candidate.body(),
                        candidate.traceId(), candidate.nextAttemptNo(), now, candidate.automationId(),
                        candidate.automationVersionId(), candidate.automationExecutionId());
                if (!store.requeueClaimedRetry(
                        candidate.projectId(), candidate.id(), leaseToken, outboxId,
                        candidate.nextAttemptNo(), now)) {
                    return;
                }
                outboxRepository.append(new OutboxEvent(
                        outboxId, candidate.tenantId(), candidate.projectId(), "RULE_NOTIFICATION",
                        candidate.id(), RuleNotificationDeliveryRequest.EVENT_TYPE,
                        candidate.id().toString(), objectMapper.writeValueAsString(request),
                        candidate.traceId(), now));
            });
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.set(previous);
        }
    }
}
