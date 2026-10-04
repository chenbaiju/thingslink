package com.things.link.task.application;

import com.things.link.support.tenant.DataPlaneDatabase;
import com.things.link.task.domain.TaskJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 从数据库短租约领取到期调度与执行，逐条进入独立应用事务推进。 */
@Component
@DataPlaneDatabase
public class TaskSchedulingScanner {
    /** 不打印命令输入或设备清单。 */ private static final Logger LOGGER = LoggerFactory.getLogger(TaskSchedulingScanner.class);
    /** 任务持久化端口。 */ private final TaskJobRepository repository;
    /** 事务化任务用例。 */ private final TaskJobService service;
    /** 每租户单并发、跨租户并行的有限调度器。 */ private final TenantFairScheduler fairScheduler;
    /** @param repository 任务持久化端口 @param service 事务化任务用例 @param fairScheduler 租户公平调度器 */
    public TaskSchedulingScanner(TaskJobRepository repository, TaskJobService service,
                                 TenantFairScheduler fairScheduler) {
        this.repository = repository; this.service = service; this.fairScheduler = fairScheduler;
    }
    /** 每秒领取有限数量到期调度，慢租户不会阻塞其他租户产生执行事实。 */
    @Scheduled(fixedDelayString = "${things-link.task.schedule-scan-millis:1000}",
            scheduler = "taskTriggerScheduler")
    public void scanSchedules() {
        for (TaskJobRepository.DueSchedule due : repository.claimDueSchedules(100)) {
            if (!fairScheduler.submit(due.tenantId(), () -> processSchedule(due))) {
                // 不主动释放租约可形成统一 30 秒退避；立即释放会让满队列每秒反复抢占并刷爆日志。
                LOGGER.warn("任务调度公平队列已满，等待租约退避 jobId={} projectId={}", due.jobId(), due.projectId());
            }
        }
    }
    /** 每秒推进有限数量执行，目标展开和命令受理均由各自短租约幂等保护。 */
    @Scheduled(fixedDelayString = "${things-link.task.execution-scan-millis:1000}",
            scheduler = "taskTriggerScheduler")
    public void scanExecutions() {
        for (TaskJobRepository.DueExecution due : repository.claimDueExecutions(100)) {
            if (!fairScheduler.submit(due.tenantId(), () -> processExecution(due))) {
                LOGGER.warn("任务执行公平队列已满，等待租约退避 executionId={} projectId={}",
                        due.executionId(), due.projectId());
            }
        }
    }
    /** 在公平调度工作线程内推进调度，并隔离单条失败。 */
    private void processSchedule(TaskJobRepository.DueSchedule due) {
        try { service.processDueSchedule(due); }
        catch (RuntimeException exception) { LOGGER.error("任务调度推进失败 jobId={} projectId={}", due.jobId(), due.projectId(), exception); }
    }
    /** 在公平调度工作线程内推进执行，并隔离单条失败。 */
    private void processExecution(TaskJobRepository.DueExecution due) {
        try { service.processExecution(due); }
        catch (RuntimeException exception) { LOGGER.error("任务执行推进失败 executionId={} projectId={}", due.executionId(), due.projectId(), exception); }
    }
}
