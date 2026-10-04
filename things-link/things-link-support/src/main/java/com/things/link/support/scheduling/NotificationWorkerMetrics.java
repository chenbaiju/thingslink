package com.things.link.support.scheduling;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/** 共享通知 worker 的全局低基数队列、公平拒绝与执行耗时指标。 */
@Component
public class NotificationWorkerMetrics {

    /** 当前外部工作数。 */
    static final String ACTIVE = "thingslink.notification.worker.active";
    /** 单实例有界队列深度。 */
    static final String QUEUED = "thingslink.notification.worker.queued";
    /** 固定原因拒绝计数。 */
    static final String REJECTED = "thingslink.notification.worker.rejected";
    /** 外部工作墙钟耗时。 */
    static final String DURATION = "thingslink.notification.worker.duration";
    /** QUEUED/SENDING 持久事实最老年龄。 */
    static final String FACT_AGE = "thingslink.notification.fact.age";

    /** 应用注册表。 */
    private final MeterRegistry registry;
    /** 固定 source/state 到最近数据库年龄秒数。 */
    private final ConcurrentHashMap<String, AtomicLong> factAges = new ConcurrentHashMap<>();

    /** Spring 装配入口。 */
    @Autowired
    public NotificationWorkerMetrics(ObjectProvider<MeterRegistry> provider) {
        this(provider.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** 测试入口。 */
    NotificationWorkerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 注册唯一 worker 池的 active/queue Gauge。 */
    void register(ThreadPoolExecutor workers) {
        Gauge.builder(ACTIVE, workers, ThreadPoolExecutor::getActiveCount)
                .description("通知共享 worker 当前执行数")
                .register(registry);
        Gauge.builder(QUEUED, workers, value -> value.getQueue().size())
                .description("通知共享 worker 有界队列深度")
                .register(registry);
    }

    /** @param reason 固定拒绝原因 queue 或 tenant_slot */
    void rejected(String reason) {
        Counter.builder(REJECTED).tag("reason", reason).register(registry).increment();
    }

    /** @param elapsedNanos 租户槽内真实工作耗时 */
    void duration(long elapsedNanos) {
        Timer.builder(DURATION)
                .publishPercentileHistogram()
                .maximumExpectedValue(Duration.ofSeconds(15))
                .register(registry)
                .record(Duration.ofNanos(elapsedNanos));
    }

    /** @param source 固定业务来源 @param ages 数据库最老事实年龄 */
    void recordFactAges(String source, NotificationWorkSource.BacklogAges ages) {
        recordFactAge(source, "QUEUED", ages.queued());
        recordFactAge(source, "SENDING", ages.sending());
    }

    /** 首次观测时注册固定来源/状态 Gauge，后续只更新原子值。 */
    private void recordFactAge(String source, String state, Duration age) {
        String key = source + ":" + state;
        AtomicLong value = factAges.computeIfAbsent(key, ignored -> {
            AtomicLong created = new AtomicLong();
            Gauge.builder(FACT_AGE, created, AtomicLong::get)
                    .description("通知持久事实最老年龄秒数")
                    .tags("source", source, "state", state)
                    .register(registry);
            return created;
        });
        value.set(Math.max(0L, age == null ? 0L : age.toSeconds()));
    }
}
