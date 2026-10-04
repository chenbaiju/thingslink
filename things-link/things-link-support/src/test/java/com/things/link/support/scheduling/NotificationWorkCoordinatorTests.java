package com.things.link.support.scheduling;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 通知公平执行器测试，钉住同租户单并发、跨租户并行与拒绝可恢复语义。 */
class NotificationWorkCoordinatorTests {

    /** 一个慢租户只能占一个槽；其重复工作被释放时，其他租户仍可在独立 worker 上推进。 */
    @Test
    void limitsSameTenantAndLetsAnotherTenantProgress() throws Exception {
        UUID slowTenant = UUID.randomUUID();
        UUID healthyTenant = UUID.randomUUID();
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch healthyFinished = new CountDownLatch(1);
        CountDownLatch rejectedReleaseFinished = new CountDownLatch(1);
        AtomicInteger slowActive = new AtomicInteger();
        AtomicInteger slowMaximum = new AtomicInteger();
        AtomicInteger rejectedReleased = new AtomicInteger();
        AtomicBoolean claimed = new AtomicBoolean();
        Runnable slowWork = () -> {
            int active = slowActive.incrementAndGet();
            slowMaximum.accumulateAndGet(active, Math::max);
            slowStarted.countDown();
            await(releaseSlow);
            slowActive.decrementAndGet();
        };
        Runnable rejectedRelease = () -> {
            rejectedReleased.incrementAndGet();
            rejectedReleaseFinished.countDown();
        };

        NotificationWorkSource source = (limit, lease) -> claimed.compareAndSet(false, true)
                ? List.of(
                // 线程池不承诺提交顺序；两条同租户工作必须等价，测试才能只验证“最多一个占槽”的冻结契约。
                work(slowTenant, slowWork, rejectedRelease),
                work(slowTenant, slowWork, rejectedRelease),
                work(healthyTenant, healthyFinished::countDown, () -> { }))
                : List.of();
        InMemoryTenantSlots slots = new InMemoryTenantSlots();
        NotificationWorkCoordinator coordinator = new NotificationWorkCoordinator(List.of(source), slots);
        try {
            coordinator.dispatchReadyWork();

            assertThat(slowStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(healthyFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(rejectedReleaseFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(rejectedReleased).hasValue(1);
            assertThat(slowMaximum).hasValue(1);
        } finally {
            releaseSlow.countDown();
            coordinator.destroy();
        }
    }

    /** 构造单条持久工作。 */
    private static NotificationWorkSource.NotificationWork work(
            UUID tenantId, Runnable execute, Runnable release) {
        return new NotificationWorkSource.NotificationWork(tenantId, execute, release);
    }

    /** 测试线程等待门闩；中断必须恢复标志并失败，不能让断言假绿。 */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new AssertionError("慢租户测试门闩未在期限内释放");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("慢租户测试被中断", exception);
        }
    }

    /** 进程内替身只模拟数据库唯一槽的 token 所有权，真实跨实例竞争由 JDBC 测试覆盖。 */
    private static final class InMemoryTenantSlots implements TenantWorkSlotRepository {
        /** 租户到当前 token。 */
        private final ConcurrentHashMap<UUID, UUID> tokens = new ConcurrentHashMap<>();

        /** {@inheritDoc} */
        @Override
        public Optional<Lease> tryAcquire(WorkType workType, UUID tenantId, Duration duration) {
            UUID token = UUID.randomUUID();
            return tokens.putIfAbsent(tenantId, token) == null
                    ? Optional.of(new Lease(workType, tenantId, token))
                    : Optional.empty();
        }

        @Override public boolean fence(Lease lease) { return lease.token().equals(tokens.get(lease.tenantId())); }

        /** {@inheritDoc} */
        @Override
        public boolean release(Lease lease) {
            return tokens.remove(lease.tenantId(), lease.token());
        }
    }
}
