package com.things.link.support.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** G1-C3b 按故障域拆分后台触发线程，避免慢 Outbox 或维护任务饿死命令生命周期。 */
@Configuration(proxyBeanMethods = false)
public class SchedulingIsolationConfiguration {

    /** 统一记录任务边界未捕获异常；异常不得静默终止后续 fixed-delay 调度。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(SchedulingIsolationConfiguration.class);
    /** 固定池运行时指标。 */
    private final SchedulerIsolationMetrics metrics;
    /** 关闭时等待在途短事务的秒数；设零仅取消等待，不保证已暂停线程池终止（G2-A4c-Q13）。 */
    private final int shutdownAwaitSeconds;

    /** @param metrics 调度器低基数指标门面 @param shutdownAwaitSeconds 优雅停机等待秒数 */
    public SchedulingIsolationConfiguration(
            SchedulerIsolationMetrics metrics,
            @Value("${things-link.scheduling.shutdown-await-seconds:30}") int shutdownAwaitSeconds) {
        this.metrics = metrics;
        if (shutdownAwaitSeconds < 0 || shutdownAwaitSeconds > 60) {
            throw new IllegalArgumentException("调度器优雅停机等待秒数必须在 0..60");
        }
        this.shutdownAwaitSeconds = shutdownAwaitSeconds;
    }

    /** @return Outbox 领取专属单线程调度器；实际 Kafka 等待进入 stripe worker */
    @Bean(name = "outboxTriggerScheduler")
    public ThreadPoolTaskScheduler outboxTriggerScheduler() {
        return scheduler("outbox-trigger", "tc-outbox-trigger-", 1);
    }

    /** @return 设备命令超时推进专属调度器 */
    @Bean(name = "commandLifecycleScheduler")
    public ThreadPoolTaskScheduler commandLifecycleScheduler() {
        return scheduler("command-lifecycle", "tc-command-lifecycle-", 1);
    }

    /** @return Modbus 到期派发与超时回收共享但不影响其他域的双线程调度器 */
    @Bean(name = "modbusLifecycleScheduler")
    public ThreadPoolTaskScheduler modbusLifecycleScheduler() {
        return scheduler("modbus-lifecycle", "tc-modbus-lifecycle-", 2);
    }

    /** @return 告警与规则通知重排队/领取调度器；真实发送进入独立公平 worker */
    @Bean(name = "notificationLifecycleScheduler")
    public ThreadPoolTaskScheduler notificationLifecycleScheduler() {
        return scheduler("notification-lifecycle", "tc-notification-lifecycle-", 2);
    }

    /** @return 任务计划与执行租约扫描专属调度器 */
    @Bean(name = "taskTriggerScheduler")
    public ThreadPoolTaskScheduler taskTriggerScheduler() {
        return scheduler("task-trigger", "tc-task-trigger-", 1);
    }

    /** @return 自动化数据库动作独立单线程，无额外内存工作队列 */
    @Bean(name = "automationLifecycleScheduler")
    public ThreadPoolTaskScheduler automationLifecycleScheduler() {
        return scheduler("automation-lifecycle", "tc-automation-lifecycle-", 1);
    }

    /** @return 项目导出生成与孤儿清理专属单线程调度器；长快照不占用其他生命周期线程 */
    @Bean(name = "exportLifecycleScheduler")
    public ThreadPoolTaskScheduler exportLifecycleScheduler() {
        return scheduler("export-lifecycle", "tc-export-lifecycle-", 1);
    }

    /** @return OTA上传恢复专属单线程；慢对象存储不占用导出或生命周期线程 */
    @Bean(name = "otaUploadScheduler")
    public ThreadPoolTaskScheduler otaUploadScheduler() {
        return scheduler("ota-upload", "tc-ota-upload-", 1);
    }

    /** @return token、幂等、调试事实清理与日对账共用的维护调度器 */
    @Bean(name = "maintenanceScheduler")
    public ThreadPoolTaskScheduler maintenanceScheduler() {
        return scheduler("maintenance", "tc-maintenance-", 2);
    }

    /** @param threadNamePrefix 可观测线程名前缀 @param poolSize 冻结线程数 @return 调度器 Bean */
    private ThreadPoolTaskScheduler scheduler(String schedulerName, String threadNamePrefix, int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(shutdownAwaitSeconds);
        scheduler.setErrorHandler(error -> LOGGER.error("后台调度任务未捕获异常", error));
        metrics.instrument(schedulerName, scheduler);
        return scheduler;
    }
}
