package com.things.link.rule.application.queue;

import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.rule.application.outbox.RuleSideEffectOutboxBridge;
import com.things.link.shared.error.RetryableLeaseConflictException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.fault.FaultInjectionCheckpoint;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 把有效策略、两级公平调度、持久回执和有限恢复路径收敛为唯一规则队列入口。
 *
 * <p>S8-2B 不接生产上行；S8-2C 只能调用本入口，不能在 Kafka consumer 线程直接执行脚本。永久失败与
 * 重试消息必须先得到 broker 确认，本方法才返回可提交原 offset 的有限结果。</p>
 */
public final class RuleExecutionCoordinator {

    /** 有效套餐读取端口。 */
    private final EffectiveQuotaPolicyProvider policyProvider;
    /** 脚本次数与 CPU 日额度门禁。 */
    private final RuleQuotaGate quotaGate;
    /** 租户外层、项目内层公平调度器。 */
    private final TwoLevelRuleFairScheduler scheduler;
    /** 持久幂等回执。 */
    private final RuleExecutionReceiptStore receiptStore;
    /** 实际规则版本处理器。 */
    private final RuleExecutionProcessor processor;
    /** 成功 continuation 必须先于回执终态得到 broker 确认。 */
    private final RuleExecutionSuccessPublisher successPublisher;
    /** 每个 attempt 的封闭生产执行事实。 */
    private final RuleExecutionLogStore logStore;
    /** S9-1 副作用与回执终态同事务收口；S8-2B 测试兼容构造可空。 */
    private final RuleSideEffectOutboxBridge bridge;
    /** retry Topic 与规则 DLQ 发布端口。 */
    private final RuleRecoveryPublisher recoveryPublisher;
    /** 有限退避策略。 */
    private final RuleRetryPolicy retryPolicy;
    /** 固定低基数指标。 */
    private final RuleQueueMetrics metrics;
    /** G1-C4b 专用的一次性进程外故障检查点；默认关闭且不改变生产路径。 */
    private final FaultInjectionCheckpoint faultCheckpoint;
    /** 可测试的 UTC 时钟。 */
    private final Clock clock;

    /**
     * @param policyProvider 有效策略端口
     * @param quotaGate 日额度门禁
     * @param scheduler 两级公平调度器
     * @param receiptStore 持久回执
     * @param processor 规则处理器
     * @param successPublisher 可靠 continuation 发布器
     * @param logStore 生产执行事实
     * @param bridge 副作用与回执终态同事务收口
     * @param recoveryPublisher 恢复发布端口
     * @param retryPolicy 有限退避策略
     * @param metrics 队列指标
     * @param clock UTC 时钟
     */
    public RuleExecutionCoordinator(
            EffectiveQuotaPolicyProvider policyProvider,
            RuleQuotaGate quotaGate,
            TwoLevelRuleFairScheduler scheduler,
            RuleExecutionReceiptStore receiptStore,
            RuleExecutionProcessor processor,
            RuleExecutionSuccessPublisher successPublisher,
            RuleExecutionLogStore logStore,
            RuleSideEffectOutboxBridge bridge,
            RuleRecoveryPublisher recoveryPublisher,
            RuleRetryPolicy retryPolicy,
            RuleQueueMetrics metrics,
            Clock clock) {
        this(policyProvider, quotaGate, scheduler, receiptStore, processor, successPublisher, logStore, bridge,
                recoveryPublisher, retryPolicy, metrics, FaultInjectionCheckpoint.disabled(), clock);
    }

    /**
     * G1-C4b 生产装配构造器；检查点仅在显式开启且场景匹配时阻塞目标 Worker。
     *
     * @param policyProvider 有效策略端口
     * @param quotaGate 日额度门禁
     * @param scheduler 两级公平调度器
     * @param receiptStore 持久回执
     * @param processor 规则处理器
     * @param successPublisher 可靠 continuation 发布器
     * @param logStore 生产执行事实
     * @param bridge 副作用与回执终态同事务收口
     * @param recoveryPublisher 恢复发布端口
     * @param retryPolicy 有限退避策略
     * @param metrics 队列指标
     * @param faultCheckpoint 一次性进程外故障检查点
     * @param clock UTC 时钟
     */
    public RuleExecutionCoordinator(
            EffectiveQuotaPolicyProvider policyProvider,
            RuleQuotaGate quotaGate,
            TwoLevelRuleFairScheduler scheduler,
            RuleExecutionReceiptStore receiptStore,
            RuleExecutionProcessor processor,
            RuleExecutionSuccessPublisher successPublisher,
            RuleExecutionLogStore logStore,
            RuleSideEffectOutboxBridge bridge,
            RuleRecoveryPublisher recoveryPublisher,
            RuleRetryPolicy retryPolicy,
            RuleQueueMetrics metrics,
            FaultInjectionCheckpoint faultCheckpoint,
            Clock clock) {
        this.policyProvider = Objects.requireNonNull(policyProvider, "policyProvider");
        this.quotaGate = Objects.requireNonNull(quotaGate, "quotaGate");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.receiptStore = Objects.requireNonNull(receiptStore, "receiptStore");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.successPublisher = Objects.requireNonNull(successPublisher, "successPublisher");
        this.logStore = Objects.requireNonNull(logStore, "logStore");
        this.bridge = bridge;
        this.recoveryPublisher = Objects.requireNonNull(recoveryPublisher, "recoveryPublisher");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.faultCheckpoint = Objects.requireNonNull(faultCheckpoint, "faultCheckpoint");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** S8-2B 测试兼容构造器；生产装配必须显式提供可靠 continuation 发布器与副作用桥接。 */
    public RuleExecutionCoordinator(
            EffectiveQuotaPolicyProvider policyProvider, RuleQuotaGate quotaGate,
            TwoLevelRuleFairScheduler scheduler, RuleExecutionReceiptStore receiptStore,
            RuleExecutionProcessor processor, RuleRecoveryPublisher recoveryPublisher,
            RuleRetryPolicy retryPolicy, RuleQueueMetrics metrics, Clock clock) {
        this(policyProvider, quotaGate, scheduler, receiptStore, processor, (envelope, message) -> { }, entry -> true,
                null, recoveryPublisher, retryPolicy, metrics, clock);
    }

    /**
     * 通过额度与容量门禁后异步执行；拒绝项在返回前完成 retry/DLQ broker 发布。
     *
     * @param envelope 不可变执行信封
     * @return 可供消息入口解释的有限提交结果
     */
    public RuleExecutionSubmission submit(RuleExecutionEnvelope envelope) {
        return submitInternal(envelope, null);
    }

    /** 等待持久终态后返回；脚本仍只运行在公平调度 Worker。 */
    public RuleExecutionSubmission submitAndAwait(RuleExecutionEnvelope envelope) {
        CompletableFuture<RuleExecutionSubmission> completion = new CompletableFuture<>();
        RuleExecutionSubmission immediate = submitInternal(envelope, completion);
        if (immediate != RuleExecutionSubmission.ACCEPTED) return immediate;
        try {
            return completion.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
            throw exception;
        }
    }

    /** 异步入口共享门禁；completion 仅供生产 listener 等待可靠交接。 */
    private RuleExecutionSubmission submitInternal(
            RuleExecutionEnvelope envelope, CompletableFuture<RuleExecutionSubmission> completion) {
        Objects.requireNonNull(envelope, "envelope");
        if (!quotaGate.allows(envelope)) {
            metrics.recordSubmission(RuleQueueMetrics.SubmissionStage.ADMISSION,
                    RuleQueueMetrics.SubmissionResult.QUOTA_REJECTED);
            recoveryPublisher.publishDeadLetter(envelope, RuleExecutionFailure.QUOTA_REJECTED);
            metrics.recordDeadLetterPublished(RuleExecutionFailure.QUOTA_REJECTED);
            return RuleExecutionSubmission.DEAD_LETTER;
        }
        EffectiveQuotaPolicy policy = policyProvider.resolveTrustedDeviceProject(
                envelope.tenantId(), envelope.key().projectId());
        if (Long.valueOf(0L).equals(policy.ruleTenantConcurrencyLimit())) {
            metrics.recordSubmission(RuleQueueMetrics.SubmissionStage.ADMISSION,
                    RuleQueueMetrics.SubmissionResult.QUOTA_REJECTED);
            recoveryPublisher.publishDeadLetter(envelope, RuleExecutionFailure.QUOTA_REJECTED);
            metrics.recordDeadLetterPublished(RuleExecutionFailure.QUOTA_REJECTED);
            return RuleExecutionSubmission.DEAD_LETTER;
        }
        RuleQueueLimits limits = new RuleQueueLimits(
                bounded(policy.ruleTenantConcurrencyLimit()),
                bounded(policy.ruleTenantQueueCapacity()),
                bounded(policy.ruleProjectQueueCapacity()));
        RuleQueueSubmissionResult result = scheduler.submit(new RuleQueueWorkItem(
                envelope.tenantId(), envelope.key().projectId(), () -> execute(envelope, completion)), limits);
        if (result == RuleQueueSubmissionResult.ACCEPTED) {
            metrics.recordSubmission(RuleQueueMetrics.SubmissionStage.DISPATCH,
                    RuleQueueMetrics.SubmissionResult.ACCEPTED);
            return RuleExecutionSubmission.ACCEPTED;
        }
        RuleQueueMetrics.SubmissionResult metricResult = switch (result) {
            case TENANT_QUEUE_FULL, INSTANCE_QUEUE_FULL, ACTIVE_TENANT_LIMIT ->
                    RuleQueueMetrics.SubmissionResult.TENANT_FULL;
            case PROJECT_QUEUE_FULL, ACTIVE_PROJECT_LIMIT -> RuleQueueMetrics.SubmissionResult.PROJECT_FULL;
            case CLOSED -> RuleQueueMetrics.SubmissionResult.CLOSED;
            case ACCEPTED -> throw new IllegalStateException("已接受结果不能进入拒绝分支");
        };
        metrics.recordSubmission(RuleQueueMetrics.SubmissionStage.DISPATCH, metricResult);
        return recover(envelope, RuleExecutionFailure.QUEUE_SATURATED, Duration.ZERO);
    }

    /** 取得持久回执后执行一项工作，并把失败路由到有限恢复路径。 */
    private void execute(RuleExecutionEnvelope envelope,
                         CompletableFuture<RuleExecutionSubmission> completion) {
        // 后台 Worker 线程没有请求 RLS 上下文，而 Outbox append 走直接 INSERT 受项目 RLS 保护；
        // 与 NotificationRetryScheduler 同款约定：从信封恢复项目范围，完成或失败后都在 finally 清除。
        try {
            TenantContext.set(new TenantScope(
                    envelope.tenantId(), envelope.key().projectId(), envelope.plan().steps().getFirst().createdBy()));
            executeWithScope(envelope, completion);
        } catch (RuntimeException exception) {
            // ADR0068决策2：领取/检查点/恢复发布及回执维护也可能失败，必须解除listener等待。
            // 保留原异常交还来源入口，不在这里报告可靠接管或改写其既有Kafka恢复分类。
            fail(completion, exception);
        } finally {
            TenantContext.clear();
        }
    }

    /** 在已恢复的项目 RLS 范围内执行一次规则尝试；副作用收口（Outbox append）依赖该上下文。 */
    private void executeWithScope(RuleExecutionEnvelope envelope,
                                  CompletableFuture<RuleExecutionSubmission> completion) {
        Instant startedAt = clock.instant();
        faultCheckpoint.reach(FaultInjectionCheckpoint.Checkpoint.RULE_BEFORE_RECEIPT_CLAIM,
                stableIdentity(envelope));
        RuleExecutionReceiptClaim claim = receiptStore.tryClaim(envelope);
        if (claim == RuleExecutionReceiptClaim.COMPLETED) {
            metrics.recordExecution(RuleQueueMetrics.ExecutionStage.ENGINE,
                    RuleQueueMetrics.ExecutionResult.REPLAYED, Duration.between(startedAt, clock.instant()));
            complete(completion, RuleExecutionSubmission.REPLAYED);
            return;
        }
        if (claim == RuleExecutionReceiptClaim.BUSY) {
            // G1-C4b-F26：崩溃后的 normalized 重放可能先于 60 秒租约到期抵达；必须让持久入口保留
            // 来源 offset 并有限重试。普通 IllegalStateException 会被 F15 的未知错误策略立即送入 DLQ。
            fail(completion, new RetryableLeaseConflictException("相同规则执行仍由有效租约处理"));
            return;
        }
        faultCheckpoint.reach(FaultInjectionCheckpoint.Checkpoint.RULE_AFTER_RECEIPT_CLAIM,
                stableIdentity(envelope));
        try {
            RuleExecutionResult result = processor.process(envelope);
            try {
                successPublisher.publish(envelope, result.message());
                faultCheckpoint.reach(FaultInjectionCheckpoint.Checkpoint.RULE_AFTER_CONTINUATION_ACK,
                        stableIdentity(envelope));
            } catch (RuntimeException exception) {
                throw new RuleExecutionException(RuleExecutionFailure.INFRASTRUCTURE_UNAVAILABLE,
                        "规则成功 continuation 发布失败", exception);
            }
            // 保留既有成功耗时口径，项目锁与动作落库不追加到脚本计量。
            Duration duration = Duration.between(startedAt, clock.instant());
            if (bridge != null) {
                bridge.completeWithSideEffects(envelope, result.sideEffectIntents());
            } else {
                receiptStore.complete(envelope);
            }
            // ADR0068决策2：只有动作及receipt提交成功才记录SUCCESS，processed ACK不代表动作成功。
            appendLogBestEffort(envelope, RuleExecutionLogEntry.Status.SUCCESS, "SUCCESS",
                    duration, bytes(result.message()));
            faultCheckpoint.reach(FaultInjectionCheckpoint.Checkpoint.RULE_AFTER_RECEIPT_COMMIT,
                    stableIdentity(envelope));
            metrics.recordExecution(RuleQueueMetrics.ExecutionStage.ENGINE,
                    RuleQueueMetrics.ExecutionResult.SUCCESS, duration);
            complete(completion, RuleExecutionSubmission.ACCEPTED);
        } catch (RuleExecutionException exception) {
            RuleExecutionSubmission outcome = recover(envelope, exception.failure(),
                    Duration.between(startedAt, clock.instant()));
            RuleExecutionLogEntry.Status status = outcome == RuleExecutionSubmission.RETRY_SCHEDULED
                    ? RuleExecutionLogEntry.Status.RETRY_SCHEDULED : RuleExecutionLogEntry.Status.DEAD_LETTER;
            // retry/DLQ 已获 broker ack 后，日志失败不能回退可靠恢复事实或再次执行脚本。
            appendLogBestEffort(envelope, status, exception.failure().name(),
                    Duration.between(startedAt, clock.instant()), 0);
            if (outcome == RuleExecutionSubmission.RETRY_SCHEDULED) {
                receiptStore.release(envelope);
            } else {
                receiptStore.complete(envelope);
            }
            complete(completion, outcome);
        } catch (RuntimeException exception) {
            fail(completion, exception);
        }
    }

    /** 追加不含载荷、源码与异常正文的最小执行事实。 */
    private void appendLog(RuleExecutionEnvelope envelope, RuleExecutionLogEntry.Status status,
                           String resultCode, Duration duration, int inputBytes, int outputBytes) {
        logStore.append(new RuleExecutionLogEntry(envelope.tenantId(), envelope.key(), envelope.attempt(),
                status, resultCode, duration, inputBytes, outputBytes, clock.instant(), envelope.message().deviceId()));
    }

    /** 恢复消息已经持久化后日志只能尽力追加，不能以观测事实破坏消息可靠性。 */
    private void appendLogBestEffort(RuleExecutionEnvelope envelope, RuleExecutionLogEntry.Status status,
                                    String resultCode, Duration duration, int outputBytes) {
        try {
            appendLog(envelope, status, resultCode, duration, bytes(envelope.message()), outputBytes);
        } catch (RuntimeException ignored) {
            // 低基数执行指标仍保留结果；S8-2D 应通过数据库可用性告警定位日志缺口。
        }
    }

    /** @return payload 的真实 UTF-8 字节数，不把可信 metadata 或身份计入脚本输入。 */
    private static int bytes(com.things.link.rule.application.engine.RuleMessage message) {
        return message.payload().toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /** 兼容异步入口的空 completion。 */
    private static void complete(CompletableFuture<RuleExecutionSubmission> completion,
                                 RuleExecutionSubmission outcome) {
        if (completion != null) completion.complete(outcome);
    }

    /** 未形成持久终态时让 listener 抛错，禁止提交 source offset。 */
    private static void fail(CompletableFuture<RuleExecutionSubmission> completion,
                             RuntimeException exception) {
        if (completion != null) completion.completeExceptionally(exception);
    }

    /** 先取得 broker 确认，再让上层更新回执或提交原 offset。 */
    private RuleExecutionSubmission recover(
            RuleExecutionEnvelope envelope, RuleExecutionFailure failure, Duration duration) {
        RuleRetryDecision decision = retryPolicy.decide(envelope, failure);
        if (decision.disposition() == RuleRetryDecision.Disposition.RETRY) {
            RuleExecutionEnvelope next = envelope.nextAttempt(clock.instant().plus(decision.delay()));
            recoveryPublisher.publishRetry(next, decision.delay());
            faultCheckpoint.reach(FaultInjectionCheckpoint.Checkpoint.RULE_AFTER_RETRY_ACK,
                    stableIdentity(envelope));
            metrics.recordRetry(decision.delay(), RuleQueueMetrics.RetryResult.PUBLISHED);
            metrics.recordExecution(RuleQueueMetrics.ExecutionStage.ENGINE,
                    RuleQueueMetrics.ExecutionResult.RETRY_SCHEDULED, duration);
            return RuleExecutionSubmission.RETRY_SCHEDULED;
        }
        recoveryPublisher.publishDeadLetter(envelope, failure);
        metrics.recordDeadLetterPublished(failure);
        metrics.recordExecution(RuleQueueMetrics.ExecutionStage.ENGINE,
                RuleQueueMetrics.ExecutionResult.DEAD_LETTER, duration);
        return RuleExecutionSubmission.DEAD_LETTER;
    }

    /** PostgreSQL bigint 套餐值必须安全收敛到进程内队列整数上限。 */
    private static Integer bounded(Long value) {
        return value == null ? null : Math.toIntExact(Math.min(value, Integer.MAX_VALUE));
    }

    /** 使用持久消息键标识一次尝试；检查点只落摘要，避免业务标识进入公开证据。 */
    private static String stableIdentity(RuleExecutionEnvelope envelope) {
        return envelope.tenantId() + ":" + envelope.key().projectId() + ":"
                + envelope.key().messageId() + ":" + envelope.attempt();
    }
}
