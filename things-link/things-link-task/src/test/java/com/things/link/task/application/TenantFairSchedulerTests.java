package com.things.link.task.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** S7-5 慢租户隔离与有界退避的确定性并发验收。 */
class TenantFairSchedulerTests {

    /** 慢租户阻塞时，另一租户仍应在慢工作释放前完成命令推进。 */
    @Test
    void slowTenantDoesNotBlockAnotherTenant() throws InterruptedException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TenantFairScheduler scheduler = new TenantFairScheduler(registry, 2, 2, 8);
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch slowFinished = new CountDownLatch(1);
        CountDownLatch fastFinished = new CountDownLatch(1);
        try {
            assertThat(scheduler.submit(UUID.randomUUID(), () -> {
                slowStarted.countDown();
                await(releaseSlow);
                slowFinished.countDown();
            })).isTrue();
            assertThat(slowStarted.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(scheduler.submit(UUID.randomUUID(), fastFinished::countDown)).isTrue();

            // 断言的是故障期间隔离，而不是释放慢工作后的最终吞吐。
            assertThat(fastFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(slowFinished.getCount()).isEqualTo(1L);
        } finally {
            releaseSlow.countDown();
            scheduler.close();
        }
    }

    /** 单租户积压超过有限容量时必须拒绝并留下指标，不能把内存队列做成隐性缓冲池。 */
    @Test
    void rejectsBeyondPerTenantCapacityAndExposesMetric() throws InterruptedException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TenantFairScheduler scheduler = new TenantFairScheduler(registry, 2, 2, 8);
        UUID slowTenant = UUID.randomUUID();
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch secondSameTenantStarted = new CountDownLatch(1);
        try {
            assertThat(scheduler.submit(slowTenant, () -> {
                slowStarted.countDown();
                await(releaseSlow);
            })).isTrue();
            assertThat(slowStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(scheduler.submit(slowTenant, secondSameTenantStarted::countDown)).isTrue();
            assertThat(scheduler.submit(slowTenant, () -> { })).isTrue();
            assertThat(scheduler.submit(slowTenant, () -> { })).isFalse();

            assertThat(secondSameTenantStarted.await(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(registry.get(TenantFairScheduler.QUEUED_METRIC).gauge().value()).isEqualTo(2D);
            assertThat(registry.get(TenantFairScheduler.SUBMISSION_METRIC)
                    .tag("result", "tenant_queue_full").counter().count()).isEqualTo(1D);
        } finally {
            releaseSlow.countDown();
            scheduler.close();
        }
    }

    /** 不把中断处理重复散落到每个闩锁工作中。 */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("等待故障注入闩锁超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
