package com.things.link.rule.application.queue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** S8-2B 租户外层、项目内层公平性与有界容量的确定性并发验收。 */
class TwoLevelRuleFairSchedulerTests {

    /** 慢租户占用一个槽时，另一租户仍能在慢工作释放前完成。 */
    @Test
    void slowTenantDoesNotBlockAnotherTenant() throws InterruptedException {
        TwoLevelRuleFairScheduler scheduler = new TwoLevelRuleFairScheduler(2, 8, 8, 16);
        UUID slowTenant = UUID.randomUUID();
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch fastFinished = new CountDownLatch(1);
        try {
            assertThat(submit(scheduler, slowTenant, UUID.randomUUID(), () -> {
                slowStarted.countDown();
                await(releaseSlow);
            }, new RuleQueueLimits(1, 4, 4))).isEqualTo(RuleQueueSubmissionResult.ACCEPTED);
            assertThat(slowStarted.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(submit(scheduler, UUID.randomUUID(), UUID.randomUUID(), fastFinished::countDown,
                    new RuleQueueLimits(1, 4, 4))).isEqualTo(RuleQueueSubmissionResult.ACCEPTED);

            assertThat(fastFinished.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(releaseSlow.getCount()).isEqualTo(1L);
        } finally {
            releaseSlow.countDown();
            scheduler.close();
        }
    }

    /** 同租户热点项目连续积压时，新项目会进入项目轮转环而不会永久饥饿。 */
    @Test
    void hotProjectDoesNotStarveAnotherProjectInSameTenant() throws InterruptedException {
        TwoLevelRuleFairScheduler scheduler = new TwoLevelRuleFairScheduler(2, 8, 8, 16);
        UUID tenantId = UUID.randomUUID();
        UUID hotProject = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        RuleQueueLimits limits = new RuleQueueLimits(1, 6, 4);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch allFinished = new CountDownLatch(3);
        List<String> order = new CopyOnWriteArrayList<>();
        try {
            submit(scheduler, tenantId, hotProject, () -> {
                firstStarted.countDown();
                await(releaseFirst);
            }, limits);
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
            submit(scheduler, tenantId, hotProject, () -> record(order, "hot-1", allFinished), limits);
            submit(scheduler, tenantId, otherProject, () -> record(order, "other", allFinished), limits);
            submit(scheduler, tenantId, hotProject, () -> record(order, "hot-2", allFinished), limits);

            releaseFirst.countDown();
            assertThat(allFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(order).containsExactly("hot-1", "other", "hot-2");
        } finally {
            releaseFirst.countDown();
            scheduler.close();
        }
    }

    /** 租户与项目等待额度分别拒绝过载，且零等待额度不阻止立即执行。 */
    @Test
    void enforcesDynamicTenantAndProjectQueueLimits() throws InterruptedException {
        TwoLevelRuleFairScheduler scheduler = new TwoLevelRuleFairScheduler(2, 8, 8, 16);
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            assertThat(submit(scheduler, tenantId, projectId, () -> {
                started.countDown();
                await(release);
            }, new RuleQueueLimits(1, 0, 0))).isEqualTo(RuleQueueSubmissionResult.ACCEPTED);
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(submit(scheduler, tenantId, UUID.randomUUID(), () -> { },
                    new RuleQueueLimits(1, 0, 4))).isEqualTo(RuleQueueSubmissionResult.TENANT_QUEUE_FULL);

            UUID secondTenant = UUID.randomUUID();
            UUID secondProject = UUID.randomUUID();
            CountDownLatch secondStarted = new CountDownLatch(1);
            CountDownLatch releaseSecond = new CountDownLatch(1);
            submit(scheduler, secondTenant, secondProject, () -> {
                secondStarted.countDown();
                await(releaseSecond);
            }, new RuleQueueLimits(1, 4, 1));
            assertThat(secondStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(submit(scheduler, secondTenant, secondProject, () -> { },
                    new RuleQueueLimits(1, 4, 1))).isEqualTo(RuleQueueSubmissionResult.ACCEPTED);
            assertThat(submit(scheduler, secondTenant, secondProject, () -> { },
                    new RuleQueueLimits(1, 4, 1))).isEqualTo(RuleQueueSubmissionResult.PROJECT_QUEUE_FULL);
            releaseSecond.countDown();
        } finally {
            release.countDown();
            scheduler.close();
        }
    }

    /** 业务额度为空时，实例总等待上限仍会拒绝额外积压。 */
    @Test
    void physicalQueueCapacityStillAppliesWhenPolicyLimitsAreNull() throws InterruptedException {
        TwoLevelRuleFairScheduler scheduler = new TwoLevelRuleFairScheduler(2, 1, 8, 16);
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        RuleQueueLimits unlimited = new RuleQueueLimits(null, null, null);
        try {
            submit(scheduler, UUID.randomUUID(), UUID.randomUUID(), blocking(bothStarted, release), unlimited);
            submit(scheduler, UUID.randomUUID(), UUID.randomUUID(), blocking(bothStarted, release), unlimited);
            assertThat(bothStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(submit(scheduler, UUID.randomUUID(), UUID.randomUUID(), () -> { }, unlimited))
                    .isEqualTo(RuleQueueSubmissionResult.ACCEPTED);
            assertThat(submit(scheduler, UUID.randomUUID(), UUID.randomUUID(), () -> { }, unlimited))
                    .isEqualTo(RuleQueueSubmissionResult.INSTANCE_QUEUE_FULL);
        } finally {
            release.countDown();
            scheduler.close();
        }
    }

    /** 关闭后明确拒绝新工作，避免调用方误认为已排队。 */
    @Test
    void rejectsSubmissionAfterClose() {
        TwoLevelRuleFairScheduler scheduler = new TwoLevelRuleFairScheduler(2, 4, 4, 4);
        scheduler.close();

        assertThat(submit(scheduler, UUID.randomUUID(), UUID.randomUUID(), () -> { },
                new RuleQueueLimits(null, null, null))).isEqualTo(RuleQueueSubmissionResult.CLOSED);
    }

    /** 有效策略的零并发语义是禁用，不能留下永远无法派发的等待项。 */
    @Test
    void rejectsTenantWhosePolicyDisablesExecution() {
        try (TwoLevelRuleFairScheduler scheduler = new TwoLevelRuleFairScheduler(2, 4, 4, 4)) {
            assertThat(submit(scheduler, UUID.randomUUID(), UUID.randomUUID(), () -> { },
                    new RuleQueueLimits(0, 4, 4)))
                    .isEqualTo(RuleQueueSubmissionResult.TENANT_QUEUE_FULL);
        }
    }

    /**
     * 构造阻塞工作，减少物理容量测试里的重复同步代码。
     *
     * @param started 启动计数
     * @param release 释放闩锁
     * @return 阻塞工作
     */
    private static Runnable blocking(CountDownLatch started, CountDownLatch release) {
        return () -> {
            started.countDown();
            await(release);
        };
    }

    /**
     * 记录执行次序并通知完成。
     *
     * @param order 顺序记录
     * @param value 当前值
     * @param finished 完成闩锁
     */
    private static void record(List<String> order, String value, CountDownLatch finished) {
        order.add(value);
        finished.countDown();
    }

    /**
     * 提交测试工作。
     *
     * @param scheduler 调度器
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param work 工作
     * @param limits 策略限制
     * @return 提交结果
     */
    private static RuleQueueSubmissionResult submit(TwoLevelRuleFairScheduler scheduler, UUID tenantId,
                                                     UUID projectId, Runnable work, RuleQueueLimits limits) {
        return scheduler.submit(new RuleQueueWorkItem(tenantId, projectId, work), limits);
    }

    /** 不把中断处理散落到每个故障注入工作中。 */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("等待调度测试闩锁超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
