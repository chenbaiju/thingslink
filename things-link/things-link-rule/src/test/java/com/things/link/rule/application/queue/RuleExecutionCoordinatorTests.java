package com.things.link.rule.application.queue;

import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.shared.error.RetryableLeaseConflictException;
import com.things.link.rule.application.outbox.RuleSideEffectOutboxBridge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;

/** S8-2B 协调器测试：回执、有限恢复与套餐容量在同一入口闭环。 */
class RuleExecutionCoordinatorTests {

    /** 每项测试独占调度线程池。 */
    private TwoLevelRuleFairScheduler scheduler;

    /** 测试后停止后台线程，避免影响下一用例。 */
    @AfterEach
    void closeScheduler() {
        if (scheduler != null) scheduler.close();
    }

    /** 成功只执行一次，相同 message/version 重放由持久回执吸收。 */
    @Test
    void completesOnceAndAbsorbsReplay() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(2);
        MemoryReceiptStore receipts = new MemoryReceiptStore();
        RecordingRecovery recovery = new RecordingRecovery();
        RuleExecutionCoordinator coordinator = coordinator(receipts, envelope -> {
            executions.incrementAndGet();
            completed.countDown();
            return RuleExecutionResult.withoutSideEffects(envelope.message());
        }, recovery);
        RuleExecutionEnvelope envelope = envelope(1);

        assertThat(coordinator.submit(envelope)).isEqualTo(RuleExecutionSubmission.ACCEPTED);
        while (receipts.state != 2) Thread.onSpinWait();
        assertThat(coordinator.submit(envelope)).isEqualTo(RuleExecutionSubmission.ACCEPTED);
        completed.countDown();

        assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(executions).hasValue(1);
        assertThat(recovery.retryCount).isZero();
    }

    /** 暂态失败先确认一分钟 retry，随后释放回执供 attempt=2 重新抢占。 */
    @Test
    void publishesFiniteRetryBeforeReleasingReceipt() throws Exception {
        CountDownLatch retried = new CountDownLatch(1);
        MemoryReceiptStore receipts = new MemoryReceiptStore();
        RecordingRecovery recovery = new RecordingRecovery(retried);
        RuleExecutionCoordinator coordinator = coordinator(receipts, envelope -> {
            throw new RuleExecutionException(RuleExecutionFailure.DATABASE_TRANSIENT, "数据库暂不可用");
        }, recovery);

        assertThat(coordinator.submit(envelope(1))).isEqualTo(RuleExecutionSubmission.ACCEPTED);
        assertThat(retried.await(2, TimeUnit.SECONDS)).isTrue();
        while (receipts.state == 3) Thread.onSpinWait();
        assertThat(recovery.retryCount).isEqualTo(1);
        assertThat(recovery.lastEnvelope.attempt()).isEqualTo(2);
        assertThat(receipts.state).isEqualTo(1);
    }

    /** 生产等待入口只在 continuation broker ack 与回执完成后返回。 */
    @Test
    void awaitsContinuationBeforeCompletingReceipt() {
        MemoryReceiptStore receipts = new MemoryReceiptStore();
        RecordingRecovery recovery = new RecordingRecovery();
        scheduler = new TwoLevelRuleFairScheduler(2, 10, 10, 10);
        AtomicInteger sequence = new AtomicInteger();
        RuleExecutionCoordinator coordinator = new RuleExecutionCoordinator(
                new FixedPolicyProvider(policy()), ignored -> true, scheduler, receipts,
                envelope -> RuleExecutionResult.withoutSideEffects(envelope.message()
                        .withPayload(new ObjectMapper().createObjectNode().put("done", true))),
                (envelope, message) -> {
                    assertThat(receipts.state).isEqualTo(3);
                    assertThat(message.payload().get("done").asBoolean()).isTrue();
                    sequence.compareAndSet(0, 1);
                }, entry -> true, null, recovery, new RuleRetryPolicy(), new RuleQueueMetrics(new SimpleMeterRegistry()),
                Clock.fixed(Instant.parse("2026-08-13T06:00:00Z"), ZoneOffset.UTC));

        assertThat(coordinator.submitAndAwait(envelope(1))).isEqualTo(RuleExecutionSubmission.ACCEPTED);
        assertThat(sequence).hasValue(1);
        assertThat(receipts.state).isEqualTo(2);
    }

    /** 有效租约冲突必须形成窄化可重试信号，不能被消息入口当作未知永久错误提交 offset。 */
    @Test
    void reportsActiveReceiptLeaseAsRetryableConflict() {
        MemoryReceiptStore receipts = new MemoryReceiptStore();
        receipts.state = 3;
        RuleExecutionCoordinator coordinator = coordinator(receipts,
                envelope -> RuleExecutionResult.withoutSideEffects(envelope.message()),
                new RecordingRecovery());

        assertThatThrownBy(() -> coordinator.submitAndAwait(envelope(1)))
                .isInstanceOf(RetryableLeaseConflictException.class)
                .hasMessage("相同规则执行仍由有效租约处理");
    }

    /** 动作拒绝允许processed已确认，但只在DLQ发布返回后完成receipt，绝不记录SUCCESS。 */
    @Test
    void actionSecurityRejectionRecordsOnlyDeadLetterAfterReliableRecovery() {
        List<String> order = new ArrayList<>();
        RuleExecutionReceiptStore receipts = mock(RuleExecutionReceiptStore.class);
        when(receipts.tryClaim(any())).thenReturn(RuleExecutionReceiptClaim.ACQUIRED);
        doAnswer(invocation -> { order.add("receipt"); return null; }).when(receipts).complete(any());
        RuleSideEffectOutboxBridge bridge = mock(RuleSideEffectOutboxBridge.class);
        doAnswer(invocation -> {
            order.add("bridge");
            throw new RuleExecutionException(RuleExecutionFailure.SECURITY_REJECTED, "frozen");
        }).when(bridge).completeWithSideEffects(any(), any());
        RuleRecoveryPublisher recovery = mock(RuleRecoveryPublisher.class);
        doAnswer(invocation -> { order.add("dlq-ack"); return null; }).when(recovery)
                .publishDeadLetter(any(), org.mockito.ArgumentMatchers.eq(RuleExecutionFailure.SECURITY_REJECTED));
        List<RuleExecutionLogEntry> logs = new ArrayList<>();
        RuleExecutionCoordinator coordinator = coordinator(receipts, bridge,
                (envelope, message) -> order.add("processed-ack"), entry -> { logs.add(entry); return true; }, recovery);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThat(coordinator.submitAndAwait(envelope(1))).isEqualTo(RuleExecutionSubmission.DEAD_LETTER));
        assertThat(order).containsExactly("processed-ack", "bridge", "dlq-ack", "receipt");
        assertThat(logs).singleElement().satisfies(entry -> {
            assertThat(entry.status()).isEqualTo(RuleExecutionLogEntry.Status.DEAD_LETTER);
            assertThat(entry.resultCode()).isEqualTo("SECURITY_REJECTED");
        });
        verify(receipts, never()).release(any());
    }

    /** 只有动作/receipt提交返回后才追加SUCCESS；观测失败仍不能撤销可靠结果。 */
    @Test
    void successLogFollowsBridgeCompletion() {
        List<String> order = new ArrayList<>();
        RuleExecutionReceiptStore receipts = mock(RuleExecutionReceiptStore.class);
        when(receipts.tryClaim(any())).thenReturn(RuleExecutionReceiptClaim.ACQUIRED);
        RuleSideEffectOutboxBridge bridge = mock(RuleSideEffectOutboxBridge.class);
        doAnswer(invocation -> { order.add("bridge-commit"); return null; }).when(bridge).completeWithSideEffects(any(), any());
        RuleExecutionCoordinator coordinator = coordinator(receipts, bridge,
                (envelope, message) -> order.add("processed-ack"), entry -> {
                    order.add(entry.status().name());
                    throw new IllegalStateException("observation unavailable");
                }, mock(RuleRecoveryPublisher.class));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThat(coordinator.submitAndAwait(envelope(1))).isEqualTo(RuleExecutionSubmission.ACCEPTED));
        assertThat(order).containsExactly("processed-ack", "bridge-commit", "SUCCESS");
    }

    /** 领取、恢复发布和恢复后回执失败都必须解除来源等待；不改变异常分类，也不伪造ACCEPTED。 */
    @ParameterizedTest
    @ValueSource(strings = {"CLAIM", "RECOVERY", "RECEIPT"})
    void workerBoundaryFailuresReachAwaitingCallerWithoutHanging(String location) {
        RuntimeException failure = new IllegalStateException("boundary failure " + location);
        RuleExecutionReceiptStore receipts = mock(RuleExecutionReceiptStore.class);
        if (location.equals("CLAIM")) {
            when(receipts.tryClaim(any())).thenThrow(failure);
        } else {
            when(receipts.tryClaim(any())).thenReturn(RuleExecutionReceiptClaim.ACQUIRED);
        }
        RuleSideEffectOutboxBridge bridge = mock(RuleSideEffectOutboxBridge.class);
        doThrow(new RuleExecutionException(RuleExecutionFailure.SECURITY_REJECTED, "frozen"))
                .when(bridge).completeWithSideEffects(any(), any());
        RuleRecoveryPublisher recovery = mock(RuleRecoveryPublisher.class);
        if (location.equals("RECOVERY")) {
            doThrow(failure).when(recovery).publishDeadLetter(any(), any());
        } else if (location.equals("RECEIPT")) {
            doThrow(failure).when(receipts).complete(any());
        }
        RuleExecutionLogStore logs = mock(RuleExecutionLogStore.class);
        RuleExecutionCoordinator coordinator = coordinator(receipts, bridge, (envelope, message) -> { }, logs, recovery);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThatThrownBy(() -> coordinator.submitAndAwait(envelope(1))).isSameAs(failure));
        if (!location.equals("RECEIPT")) verify(receipts, never()).complete(any());
        // 来源等待异常后工作线程仍可结束并让调度器释放容量；上下文清理由execute外层finally负责。
        verify(receipts, never()).release(any());
    }

    /** 生产成功与恢复日志均使用原消息设备，不能从派生载荷猜测。 */
    @Test void logsTrustedDeviceIdentityOnSuccessAndRecovery() {
        RuleExecutionReceiptStore receipts = mock(RuleExecutionReceiptStore.class);
        when(receipts.tryClaim(any())).thenReturn(RuleExecutionReceiptClaim.ACQUIRED);
        List<RuleExecutionLogEntry> logs = new ArrayList<>();
        RuleSideEffectOutboxBridge bridge = mock(RuleSideEffectOutboxBridge.class);
        RuleExecutionCoordinator coordinator = coordinator(receipts, bridge, (e,m) -> {}, e -> {logs.add(e);return true;}, mock(RuleRecoveryPublisher.class));
        var first = envelope(1);
        assertThat(coordinator.submitAndAwait(first)).isEqualTo(RuleExecutionSubmission.ACCEPTED);
        assertThat(logs.getFirst().deviceId()).isEqualTo(first.message().deviceId());
        doThrow(new RuleExecutionException(RuleExecutionFailure.SECURITY_REJECTED,"frozen")).when(bridge).completeWithSideEffects(any(),any());
        var second = envelope(2);
        assertThat(coordinator.submitAndAwait(second)).isEqualTo(RuleExecutionSubmission.DEAD_LETTER);
        assertThat(logs.getLast().deviceId()).isEqualTo(second.message().deviceId());
        assertThat(logs.getLast().attempt()).isEqualTo(2);
    }

    /** 创建完整生产形态的协调器替身组合；所有Broker返回只代表本用例中的显式确认边界。 */
    private RuleExecutionCoordinator coordinator(
            RuleExecutionReceiptStore receipts, RuleSideEffectOutboxBridge bridge,
            RuleExecutionSuccessPublisher success, RuleExecutionLogStore logs, RuleRecoveryPublisher recovery) {
        scheduler = new TwoLevelRuleFairScheduler(2, 10, 10, 10);
        return new RuleExecutionCoordinator(new FixedPolicyProvider(policy()), ignored -> true, scheduler, receipts,
                envelope -> RuleExecutionResult.withoutSideEffects(envelope.message()), success, logs, bridge,
                recovery, new RuleRetryPolicy(), new RuleQueueMetrics(new SimpleMeterRegistry()),
                Clock.fixed(Instant.parse("2026-08-13T06:00:00Z"), ZoneOffset.UTC));
    }

    /** 创建聚焦协调器；策略固定为 2 并发、4/2 等待容量。 */
    private RuleExecutionCoordinator coordinator(
            MemoryReceiptStore receipts, RuleExecutionProcessor processor, RecordingRecovery recovery) {
        scheduler = new TwoLevelRuleFairScheduler(2, 10, 10, 10);
        EffectiveQuotaPolicyProvider policies = new FixedPolicyProvider(policy());
        return new RuleExecutionCoordinator(policies, ignored -> true, scheduler, receipts, processor,
                recovery, new RuleRetryPolicy(), new RuleQueueMetrics(new SimpleMeterRegistry()),
                Clock.fixed(Instant.parse("2026-08-13T06:00:00Z"), ZoneOffset.UTC));
    }

    /** @return 带规则队列额度的有效策略 */
    private static EffectiveQuotaPolicy policy() {
        return new EffectiveQuotaPolicy(UUID.randomUUID(), UUID.randomUUID(), 1, 1,
                10L, 20L, 100L, 1000L, 20L, 10L, 100L, 100L, 20L,
                20L, 100L, 2L, 4L, 2L, 8000, 12000);
    }

    /** @param attempt 尝试次数 @return 身份一致的不可变信封 */
    private static RuleExecutionEnvelope envelope(int attempt) {
        UUID tenant = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        com.things.link.rule.application.engine.RuleMessage ruleMessage =
                new com.things.link.rule.application.engine.RuleMessage(message, tenant, project,
                        UUID.randomUUID(), "trace", Instant.parse("2026-08-13T05:59:00Z"), "PROPERTY",
                        new ObjectMapper().createObjectNode(), Map.of());
        return new RuleExecutionEnvelope(new RuleExecutionKey(project, message, UUID.randomUUID(),
                UUID.randomUUID()), tenant, ruleMessage, attempt, Instant.parse("2026-08-13T06:00:00Z"));
    }

    /** 测试内模拟 PostgreSQL 回执状态：0 空、1 retry pending、2 completed、3 in progress。 */
    private static final class MemoryReceiptStore implements RuleExecutionReceiptStore {
        /** 当前状态。 */
        private volatile int state;
        /** {@inheritDoc} */
        public synchronized RuleExecutionReceiptClaim tryClaim(RuleExecutionEnvelope envelope) {
            if (state == 2) return RuleExecutionReceiptClaim.COMPLETED;
            if (state == 3) return RuleExecutionReceiptClaim.BUSY;
            state = 3;
            return RuleExecutionReceiptClaim.ACQUIRED;
        }
        /** {@inheritDoc} */
        public synchronized void complete(RuleExecutionEnvelope envelope) { state = 2; }
        /** {@inheritDoc} */
        public synchronized void release(RuleExecutionEnvelope envelope) { state = 1; }
    }

    /** 记录已得到 broker 确认的恢复消息。 */
    private static final class RecordingRecovery implements RuleRecoveryPublisher {
        /** 可选完成信号。 */
        private final CountDownLatch latch;
        /** retry 数量。 */
        private int retryCount;
        /** 最近 retry 信封。 */
        private RuleExecutionEnvelope lastEnvelope;
        /** 无信号构造。 */
        private RecordingRecovery() { this(new CountDownLatch(0)); }
        /** @param latch 发布完成信号 */
        private RecordingRecovery(CountDownLatch latch) { this.latch = latch; }
        /** {@inheritDoc} */
        public void publishRetry(RuleExecutionEnvelope envelope, java.time.Duration delay) {
            retryCount++;
            lastEnvelope = envelope;
            latch.countDown();
        }
        /** {@inheritDoc} */
        public void publishDeadLetter(RuleExecutionEnvelope envelope, RuleExecutionFailure failure) { }
    }

    /** 返回同一测试策略的端口桩。 */
    private record FixedPolicyProvider(EffectiveQuotaPolicy policy) implements EffectiveQuotaPolicyProvider {
        /** {@inheritDoc} */ public EffectiveQuotaPolicy resolveTrustedTenant(UUID id) { return policy; }
        /** {@inheritDoc} */ public EffectiveQuotaPolicy resolveTrustedProject(UUID id) { return policy; }
        /** {@inheritDoc} */ public EffectiveQuotaPolicy resolveTrustedDeviceProject(UUID t, UUID p) { return policy; }
    }
}
