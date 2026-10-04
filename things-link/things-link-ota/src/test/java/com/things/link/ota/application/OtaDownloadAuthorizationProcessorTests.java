package com.things.link.ota.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.things.link.ota.domain.OtaDownloadAuthorizationRepository;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.storage.VersionedPrivateObjectStorage;
import com.things.link.support.storage.VersionedStorageControl;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** 实际阻塞工作与关闭反例；mock事务不代表真实租约或密码学验收。 */
class OtaDownloadAuthorizationProcessorTests {
    /** 两个物理签址阻塞时不多领取，并且worker不继承调用方账号或占用tick线程。 */
    @Test void twoPhysicalSigningCallsBoundClaimsAndIsolateContext() throws Exception {
        var fixture = fixture(Instant.now().plusSeconds(40));
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean wrongThread = new AtomicBoolean();
        AtomicBoolean inherited = new AtomicBoolean();
        long caller = Thread.currentThread().threadId();
        when(fixture.storage().presignGet(any(), any(), any())).thenAnswer(call -> {
            wrongThread.compareAndSet(false, caller == Thread.currentThread().threadId());
            inherited.compareAndSet(false, TenantContext.current().isPresent());
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试签址未释放");
            return URI.create("https://storage.example/object");
        });
        TenantContext.set(new TenantScope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        try {
            fixture.processor().start();
            fixture.processor().tick();
            fixture.processor().tick();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            fixture.processor().tick();
            verify(fixture.service(), times(2)).claimOne();
            assertFalse(wrongThread.get());
            assertFalse(inherited.get());
        } finally {
            TenantContext.clear();
            release.countDown();
            fixture.processor().stop();
        }
    }

    /** 发取消信号后不能提前回调或关闭共享storage，必须等物理签址真正退出。 */
    @Test void closeCancelsStorageControlButWaitsForPhysicalExit() throws Exception {
        var fixture = fixture(Instant.now().plusSeconds(40));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(fixture.storage().presignGet(any(), any(), any())).thenAnswer(call -> {
            VersionedStorageControl control = call.getArgument(2);
            entered.countDown();
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!control.cancelled() && System.nanoTime() < limit) Thread.onSpinWait();
            if (control.cancelled()) cancelled.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试签址未释放");
            return URI.create("https://storage.example/object?secret=hidden");
        });
        AtomicBoolean callback = new AtomicBoolean();
        try (var closer = Executors.newSingleThreadExecutor()) {
            try {
                fixture.processor().start();
                fixture.processor().tick();
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var stopping = closer.submit(() -> fixture.processor().stop(() -> callback.set(true)));
                assertTrue(cancelled.await(3, TimeUnit.SECONDS));
                assertFalse(stopping.isDone());
                assertFalse(callback.get());
                release.countDown();
                stopping.get(3, TimeUnit.SECONDS);
                assertTrue(callback.get());
                verify(fixture.service(), never()).seal(any(), any(), any());
                verify(fixture.service()).signingUnknown(any(), any(), eq("SIGNING_RESULT_UNKNOWN"));
            } finally {
                release.countDown();
                fixture.processor().stop();
            }
        }
    }

    /** 封存提交未知时不能在本工作重新签址，也不能直接发送未证实响应。 */
    @Test void uncertainSealNeverResignsOrSends() throws Exception {
        var fixture = fixture(Instant.now().plusSeconds(40));
        when(fixture.storage().presignGet(any(), any(), any())).thenReturn(URI.create("https://storage.example/object"));
        when(fixture.service().seal(any(), any(), any())).thenThrow(new IllegalStateException("测试封存结果未知"));
        CountDownLatch unknown = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> { unknown.countDown(); return true; })
                .when(fixture.service()).signingUnknown(any(), any(), any());
        try {
            fixture.processor().start();
            fixture.processor().tick();
            assertTrue(unknown.await(3, TimeUnit.SECONDS));
        } finally { fixture.processor().stop(); }
        verify(fixture.storage(), times(1)).presignGet(any(), any(), any());
        verify(fixture.service(), never()).prepareSend(any(), any());
        verify(fixture.publisher(), never()).publish(any(), any(), any(), any());
    }

    /** 原响应期限已到不接触外部存储，停用工作器不领取任何事实。 */
    @Test void expiredAndDisabledNeverStartExternalOperation() throws Exception {
        var fixture = fixture(Instant.now().minusSeconds(1));
        CountDownLatch unknown = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> { unknown.countDown(); return true; })
                .when(fixture.service()).signingUnknown(any(), any(), any());
        try {
            fixture.processor().start();
            fixture.processor().tick();
            assertTrue(unknown.await(3, TimeUnit.SECONDS));
        } finally { fixture.processor().stop(); }
        verifyNoInteractions(fixture.storage());
        var service = mock(OtaDownloadAuthorizationService.class);
        var disabled = new OtaDownloadAuthorizationProcessor(service, provider(fixture.storage()), fixture.publisher(), false);
        disabled.start();
        disabled.tick();
        disabled.stop();
        disabled.start();
        disabled.tick();
        assertFalse(disabled.isRunning());
        verifyNoInteractions(service);
    }

    /** 已封存重试不签址，关闭仍等待实际发送和回执事务退出。 */
    @Test void sealedRetryAndCloseWaitForSendAndReceipt() throws Exception {
        var fixture = fixture(Instant.now().plusSeconds(40));
        when(fixture.service().prepareSigning(any(), any())).thenReturn(Optional.empty());
        var transport = mock(OtaDownloadAuthorizationRepository.Transport.class);
        when(transport.id()).thenReturn(UUID.randomUUID());
        when(transport.reservationToken()).thenReturn(UUID.randomUUID());
        var sending = new OtaDownloadAuthorizationService.Sending(transport,
                new com.things.link.device.application.DeviceMqttDownlinkRoute(UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), 7, "project", "device"), new byte[] {1},
                Instant.now().plusSeconds(40), Instant.now().plusSeconds(30));
        when(fixture.service().prepareSend(any(), any())).thenReturn(Optional.of(sending));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch receipt = new CountDownLatch(1);
        CountDownLatch receiptRelease = new CountDownLatch(1);
        when(fixture.publisher().publish(any(), any(), any(), any())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试发送未释放");
            return new OtaDownloadResponsePublisher.Result(OtaDownloadResponsePublisher.Outcome.UNKNOWN, null, "TEST_UNKNOWN");
        });
        org.mockito.Mockito.doAnswer(call -> { cancelled.countDown(); return null; }).when(fixture.publisher()).close();
        when(fixture.service().complete(any(), any(), any())).thenAnswer(call -> {
            receipt.countDown();
            if (!receiptRelease.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试回执未释放");
            return true;
        });
        AtomicBoolean callback = new AtomicBoolean();
        try (var closer = Executors.newSingleThreadExecutor()) {
            try {
                fixture.processor().start();
                fixture.processor().tick();
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var stopping = closer.submit(() -> fixture.processor().stop(() -> callback.set(true)));
                assertTrue(cancelled.await(2, TimeUnit.SECONDS));
                assertFalse(callback.get());
                assertFalse(stopping.isDone());
                release.countDown();
                assertTrue(receipt.await(2, TimeUnit.SECONDS));
                assertFalse(callback.get());
                receiptRelease.countDown();
                stopping.get(3, TimeUnit.SECONDS);
                assertTrue(callback.get());
                verifyNoInteractions(fixture.storage());
            } finally {
                release.countDown();
                receiptRelease.countDown();
                fixture.processor().stop();
            }
        }
    }

    /** 构造真实固定期限字段，其余授权细节属于服务测试。 */
    private static Fixture fixture(Instant expiresAt) {
        var service = mock(OtaDownloadAuthorizationService.class);
        var storage = mock(VersionedPrivateObjectStorage.class);
        var publisher = mock(OtaDownloadResponsePublisher.class);
        var claim = mock(OtaDownloadAuthorizationRepository.Claim.class);
        when(claim.authorizationId()).thenReturn(UUID.randomUUID());
        when(claim.leaseToken()).thenReturn(UUID.randomUUID());
        when(claim.responseExpiresAt()).thenReturn(expiresAt);
        when(claim.leaseUntil()).thenReturn(Instant.now().plusSeconds(30));
        var signing = new OtaDownloadAuthorizationService.Signing(claim,
                new VersionedPrivateObjectStorage.VersionRef("private", "firmware", "v1"));
        when(service.claimOne()).thenReturn(Optional.of(claim));
        when(service.prepareSigning(any(), any())).thenReturn(Optional.of(signing));
        return new Fixture(service, storage, publisher,
                new OtaDownloadAuthorizationProcessor(service, provider(storage), publisher, true));
    }

    /** 每次请求都提供新Stream，避免消费已关闭的流。 */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<VersionedPrivateObjectStorage> provider(VersionedPrivateObjectStorage storage) {
        ObjectProvider<VersionedPrivateObjectStorage> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(call -> Stream.of(storage));
        return provider;
    }

    /** 本测试独立资源与mock依赖。
     * @param service 短事务mock
     * @param storage 有界签址mock
     * @param publisher 秘密发送mock
     * @param processor 实际线程组件
     */
    private record Fixture(OtaDownloadAuthorizationService service, VersionedPrivateObjectStorage storage,
            OtaDownloadResponsePublisher publisher, OtaDownloadAuthorizationProcessor processor) { }
}
