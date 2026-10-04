package com.things.link.ota.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.things.link.ota.domain.OtaCommitPermitDeliveryRepository;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** 真实worker容量与关闭边界，mock事务不代替数据库租约授权证据。 */
class OtaCommitPermitProcessorTests {
    /** 两个实际网络调用阻塞时第三次tick不领取，网络也不运行于tick线程。 */
    @Test void twoPhysicalWorkersBoundClaimsAndDoNotInheritAccount() throws Exception {
        Instant deadline = Instant.now().plusSeconds(20).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusNanos(123000);
        var service = service(deadline);
        var publisher = new ControlledPublisher(2);
        publisher.expectedExpiry = Instant.ofEpochSecond(deadline.getEpochSecond());
        var processor = new OtaCommitPermitProcessor(service, publisher, true);
        long caller = Thread.currentThread().threadId();
        publisher.caller = caller;
        TenantContext.set(new TenantScope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        try {
            processor.start();
            processor.tick();
            processor.tick();
            assertTrue(publisher.entered.await(3, TimeUnit.SECONDS));
            processor.tick();
            verify(service, times(2)).claimOne();
            assertFalse(publisher.inherited.get());
            assertFalse(publisher.sameThread.get());
        } finally {
            TenantContext.clear();
            publisher.release.countDown();
            processor.stop();
        }
    }
    /** 关闭发出真实取消后仍等待publish及回执事务实际结束，不能提前callback。 */
    @Test void stopWaitsForPhysicalPublisherAndReceiptCompletion() throws Exception {
        var service = service(Instant.now().plusSeconds(30));
        CountDownLatch receiptEntered = new CountDownLatch(1);
        CountDownLatch receiptReleased = new CountDownLatch(1);
        when(service.complete(any(), any(), any())).thenAnswer(call -> {
            receiptEntered.countDown();
            if (!receiptReleased.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试回执未释放");
            return true;
        });
        var publisher = new ControlledPublisher(1);
        var processor = new OtaCommitPermitProcessor(service, publisher, true);
        AtomicBoolean callback = new AtomicBoolean();
        try (var closer = Executors.newSingleThreadExecutor()) {
            try {
                processor.start();
                processor.tick();
                assertTrue(publisher.entered.await(3, TimeUnit.SECONDS));
                var stopping = closer.submit(() -> processor.stop(() -> callback.set(true)));
                assertTrue(publisher.cancelled.await(2, TimeUnit.SECONDS));
                assertFalse(callback.get());
                assertFalse(stopping.isDone());
                publisher.release.countDown();
                assertTrue(receiptEntered.await(2, TimeUnit.SECONDS));
                assertFalse(callback.get());
                assertFalse(stopping.isDone());
                receiptReleased.countDown();
                stopping.get(3, TimeUnit.SECONDS);
                assertTrue(callback.get());
            } finally {
                publisher.release.countDown();
                receiptReleased.countDown();
                processor.stop();
            }
        }
    }
    /** 已过期限不启动网络，但仍记录明确未知观察；prepare失败也不发送。 */
    @Test void expiredAndFailedPreparationNeverPublish() throws Exception {
        var expiredService = service(Instant.now().minusSeconds(1));
        var publisher = mock(OtaCommitPermitPublisher.class);
        CountDownLatch completed = new CountDownLatch(1);
        when(expiredService.complete(any(), any(), any())).thenAnswer(call -> {
            OtaCommitPermitPublisher.Result result = call.getArgument(2);
            assertEquals(OtaCommitPermitPublisher.Outcome.UNKNOWN, result.outcome());
            completed.countDown();
            return true;
        });
        var processor = new OtaCommitPermitProcessor(expiredService, publisher, true);
        try {
            processor.start();
            processor.tick();
            assertTrue(completed.await(3, TimeUnit.SECONDS));
            verifyNoInteractions(publisher);
        } finally { processor.stop(); }
        var failedService = service(Instant.now().plusSeconds(30));
        CountDownLatch prepared = new CountDownLatch(1);
        when(failedService.prepare(any(), any())).thenAnswer(call -> {
            prepared.countDown();
            throw new IllegalStateException("测试准备失败");
        });
        var otherPublisher = mock(OtaCommitPermitPublisher.class);
        var failed = new OtaCommitPermitProcessor(failedService, otherPublisher, true);
        try {
            failed.start();
            failed.tick();
            assertTrue(prepared.await(3, TimeUnit.SECONDS));
        } finally { failed.stop(); }
        verify(otherPublisher, times(0)).publish(any(), any(), any(), any(), any());
    }
    /** 禁用、尚未启动和已关闭都不领取，不因start重入复活资源。 */
    @Test void disabledAndStoppedNeverClaim() {
        var service = mock(OtaCommitPermitDeliveryService.class);
        var publisher = mock(OtaCommitPermitPublisher.class);
        var disabled = new OtaCommitPermitProcessor(service, publisher, false);
        disabled.start();
        disabled.tick();
        disabled.stop();
        var stopped = new OtaCommitPermitProcessor(service, publisher, true);
        stopped.tick();
        stopped.start();
        stopped.stop();
        stopped.start();
        stopped.tick();
        assertFalse(stopped.isRunning());
        verifyNoInteractions(service);
    }
    /** 构造完整独立预留事实，测试无需数据库状态。 */
    private static OtaCommitPermitDeliveryService service(Instant deadline) {
        var service = mock(OtaCommitPermitDeliveryService.class);
        var claim = mock(OtaCommitPermitDeliveryRepository.Claim.class);
        UUID event = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        var permit = mock(com.things.link.ota.domain.OtaCommitPermit.class);
        when(permit.id()).thenReturn(event);
        when(permit.deadlineAt()).thenReturn(deadline);
        when(permit.canonical()).thenReturn(new byte[]{1});
        when(claim.permit()).thenReturn(permit);
        when(claim.leaseToken()).thenReturn(token);
        var transport = new OtaCommitPermitDeliveryRepository.Transport(UUID.randomUUID(), permit,
                1, UUID.randomUUID(), token, Instant.now(), "tc/v1/p/d/down/ota/commit/permit");
        var prepared = new OtaCommitPermitDeliveryService.Prepared(transport,
                new com.things.link.device.application.DeviceMqttDownlinkRoute(UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), 7, "p", "d"), Instant.now().plusSeconds(30));
        when(service.claimOne()).thenReturn(Optional.of(claim));
        when(service.prepare(event, token)).thenReturn(Optional.of(prepared));
        return service;
    }
    /** 不自动响应取消的物理夹具，证明不能仅依赖取消标志结束生命周期。 */
    private static final class ControlledPublisher implements OtaCommitPermitPublisher {
        /** 真实publish进入数量。 */ private final CountDownLatch entered;
        /** 模拟物理响应释放。 */ private final CountDownLatch release = new CountDownLatch(1);
        /** 取消已发出。 */ private final CountDownLatch cancelled = new CountDownLatch(1);
        /** 是否发现错误继承。 */ private final AtomicBoolean inherited = new AtomicBoolean();
        /** 是否占用了tick线程。 */ private final AtomicBoolean sameThread = new AtomicBoolean();
        /** 原tick线程。 */ private long caller;
        /** 本例待检查的保守秒截止。 */ private Instant expectedExpiry;
        /** 固定并发证据数量。 */ private ControlledPublisher(int count) { entered = new CountDownLatch(count); }
        /** 测试发布器完整配置。 */ @Override public boolean configured() { return true; }
        /** 真实工作线程进入后保持阻塞。 */
        @Override public Result publish(com.things.link.device.application.DeviceMqttDownlinkRoute route, byte[] canonical, Instant expiresAt, Instant operationDeadline, Duration budget) {
            if (expectedExpiry != null) {
                assertEquals(expectedExpiry, expiresAt);
                assertEquals(expectedExpiry, operationDeadline);
                assertEquals(0, expiresAt.getNano());
            }
            inherited.compareAndSet(false, TenantContext.current().isPresent());
            sameThread.compareAndSet(false, Thread.currentThread().threadId() == caller);
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试发布未释放");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("测试发布被中断");
            }
            return new Result(Outcome.UNKNOWN, null, "TEST_CANCELLED");
        }
        /** 只发取消，不谎称实际工作结束。 */ @Override public void cancelActive() { cancelled.countDown(); }
        /** 关闭等价于取消信号，实际publish由测试单独释放。 */ @Override public void close() { cancelActive(); }
    }
}
