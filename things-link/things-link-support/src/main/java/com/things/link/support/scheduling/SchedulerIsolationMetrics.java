package com.things.link.support.scheduling;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/** 六个固定后台调度器的低基数 active/queue/耗时/失败指标。 */
@Component
public class SchedulerIsolationMetrics {

    /** 当前执行任务数。 */
    static final String ACTIVE = "thingslink.scheduler.active";
    /** 尚未执行的延迟/周期任务数。 */
    static final String QUEUED = "thingslink.scheduler.queued";
    /** 每次任务执行墙钟。 */
    static final String DURATION = "thingslink.scheduler.duration";
    /** 越过任务边界的未捕获失败。 */
    static final String FAILED = "thingslink.scheduler.failed";

    /** 应用注册表。 */
    private final MeterRegistry registry;

    /** Spring 装配入口。 */
    @Autowired
    public SchedulerIsolationMetrics(ObjectProvider<MeterRegistry> provider) {
        this(provider.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** 显式注册表测试入口。 */
    SchedulerIsolationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 为一个固定调度器安装任务装饰器和运行时 Gauge。 */
    void instrument(String schedulerName, ThreadPoolTaskScheduler scheduler) {
        scheduler.setTaskDecorator(runnable -> () -> {
            Timer.Sample sample = Timer.start(registry);
            try {
                runnable.run();
            } catch (RuntimeException | Error exception) {
                Counter.builder(FAILED).tag("scheduler", schedulerName).register(registry).increment();
                throw exception;
            } finally {
                sample.stop(Timer.builder(DURATION)
                        .tag("scheduler", schedulerName)
                        .publishPercentileHistogram()
                        .register(registry));
            }
        });
        Gauge.builder(ACTIVE, scheduler, ThreadPoolTaskScheduler::getActiveCount)
                .tag("scheduler", schedulerName)
                .register(registry);
        Gauge.builder(QUEUED, scheduler, SchedulerIsolationMetrics::queueSize)
                .tag("scheduler", schedulerName)
                .register(registry);
    }

    /** @return 初始化前为零，初始化后为延迟队列深度 */
    private static double queueSize(ThreadPoolTaskScheduler scheduler) {
        try {
            return scheduler.getScheduledThreadPoolExecutor().getQueue().size();
        } catch (IllegalStateException exception) {
            return 0D;
        }
    }
}
