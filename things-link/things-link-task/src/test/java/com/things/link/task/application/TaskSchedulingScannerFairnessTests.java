package com.things.link.task.application;

import com.things.link.task.domain.TaskJobRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证生产扫描器确实经过公平调度，而不是只存在一个未接线的调度组件。 */
class TaskSchedulingScannerFairnessTests {

    /** 同轮先领取慢租户时，后领取的快租户仍应在慢工作释放前完成。 */
    @Test
    void scanDispatchesDifferentTenantsIndependently() throws InterruptedException {
        TaskJobRepository repository = mock(TaskJobRepository.class);
        TaskJobService service = mock(TaskJobService.class);
        TenantFairScheduler scheduler = new TenantFairScheduler(new SimpleMeterRegistry(), 2, 2, 8);
        TaskJobRepository.DueExecution slow = due(UUID.randomUUID());
        TaskJobRepository.DueExecution fast = due(UUID.randomUUID());
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch fastFinished = new CountDownLatch(1);
        when(repository.claimDueExecutions(100)).thenReturn(List.of(slow, fast));
        doAnswer(invocation -> {
            slowStarted.countDown();
            releaseSlow.await(5, TimeUnit.SECONDS);
            return null;
        }).when(service).processExecution(slow);
        doAnswer(invocation -> {
            fastFinished.countDown();
            return null;
        }).when(service).processExecution(fast);
        try {
            TaskSchedulingScanner scanner = new TaskSchedulingScanner(repository, service, scheduler);

            scanner.scanExecutions();

            assertThat(slowStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(fastFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(releaseSlow.getCount()).isEqualTo(1L);
        } finally {
            releaseSlow.countDown();
            scheduler.close();
        }
    }

    /** @param tenantId 租户 ID @return 最小待执行租约投影。 */
    private static TaskJobRepository.DueExecution due(UUID tenantId) {
        return new TaskJobRepository.DueExecution(tenantId, UUID.randomUUID(), UUID.randomUUID());
    }
}
