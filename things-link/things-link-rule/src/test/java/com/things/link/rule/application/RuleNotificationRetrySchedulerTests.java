package com.things.link.rule.application;

import com.things.link.support.outbox.TransactionalOutboxRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 到期重试必须重写事务 Outbox，调度线程不得直接触达外部发送器。 */
class RuleNotificationRetrySchedulerTests {

    /** 领取候选先 CAS 到 QUEUED，再在同一短事务追加下一次 Kafka 请求。 */
    @Test
    void requeuesClaimedRetryThroughOutbox() {
        RuleNotificationDeliveryStore store = mock(RuleNotificationDeliveryStore.class);
        ProjectLifecycleAccessService lifecycle = mock(ProjectLifecycleAccessService.class);
        when(lifecycle.lockActiveForWrite(any(), any())).thenReturn(true);
        when(store.matchesClaimedRetry(any(), any(), any())).thenReturn(true);
        TransactionalOutboxRepository outbox = mock(TransactionalOutboxRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            Consumer<TransactionStatus> work = invocation.getArgument(0);
            work.accept(new SimpleTransactionStatus());
            return null;
        }).when(transactions).executeWithoutResult(any());
        UUID leaseToken = UUID.randomUUID();
        RuleNotificationDeliveryStore.RetryCandidate candidate =
                new RuleNotificationDeliveryStore.RetryCandidate(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID(), null, null, null, UUID.randomUUID(),
                        "EMAIL", "ops@example.com", "主题", "正文", "trace", 2);
        when(store.claimDueRetries(50, Duration.ofSeconds(30)))
                .thenReturn(new RuleNotificationDeliveryStore.RetryClaim(
                        leaseToken, List.of(candidate)));
        when(store.requeueClaimedRetry(
                eq(candidate.projectId()), eq(candidate.id()), eq(leaseToken), any(), eq(2), any()))
                .thenReturn(true);

        new RuleNotificationRetryScheduler(
                store, outbox, transactions, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-14T02:00:00Z"), ZoneOffset.UTC), lifecycle)
                .enqueueDueRetries();

        verify(store).requeueClaimedRetry(
                eq(candidate.projectId()), eq(candidate.id()), eq(leaseToken), any(), eq(2), any());
        verify(outbox).append(any());
    }
}
