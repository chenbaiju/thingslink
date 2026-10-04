package com.things.link.task.application;

import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 为任务推进提供每租户单并发、跨租户并行的有界公平调度。
 *
 * <p>架构文档 8.4 要求慢租户不能暂停混有其他租户工作的共享入口。本实现只服务 S7-4 的数据库任务扫描：
 * 同一租户始终最多运行一个工作，后续工作进入该租户自己的有限队列；不同租户可以占用不同工作线程。
 * 活跃租户数和单租户排队量都有限，拒绝的数据库租约会在 30 秒后重新可领，不能在内存中无界堆积。</p>
 */
@Component
public class TenantFairScheduler implements AutoCloseable {

    /** 默认工作并发；至少留出第二条执行通道，慢租户才不会阻塞其他租户。 */
    private static final int DEFAULT_WORKER_COUNT = 4;
    /** 单租户最多等待的工作数；不含当前正在运行的一项。 */
    private static final int DEFAULT_PER_TENANT_QUEUE_CAPACITY = 20;
    /** 一个实例同时容纳的活跃租户上限，防止攻击者用租户数量绕过单队列上限。 */
    private static final int DEFAULT_ACTIVE_TENANT_CAPACITY = 100;
    /** 提交结果指标；只使用固定结果标签，禁止携带租户或项目 ID。 */
    static final String SUBMISSION_METRIC = "thingslink.task.fair_scheduler.submission";
    /** 当前租户内部等待项指标。 */
    static final String QUEUED_METRIC = "thingslink.task.fair_scheduler.queued";
    /** 当前已有运行项或等待项的租户数量指标。 */
    static final String ACTIVE_TENANTS_METRIC = "thingslink.task.fair_scheduler.active_tenants";

    /** 所有队列状态都由此锁保护，避免提交与工作完成同时丢失唤醒。 */
    private final Object monitor = new Object();
    /** 每租户运行标记和等待队列。 */
    private final Map<UUID, TenantQueue> tenantQueues = new HashMap<>();
    /** 固定并发执行器；只会收到每个活跃租户的一项工作，因此全局排队也受活跃租户上限约束。 */
    private final ExecutorService executor;
    /** 单租户等待上限。 */
    private final int perTenantQueueCapacity;
    /** 活跃租户上限。 */
    private final int activeTenantCapacity;
    /** 当前租户内部排队量，为 gauge 提供无锁只读快照。 */
    private final AtomicInteger queuedTasks = new AtomicInteger();
    /** 当前活跃租户量，为 gauge 提供无锁只读快照。 */
    private final AtomicInteger activeTenants = new AtomicInteger();
    /** 接受提交总计。 */
    private final Counter acceptedCounter;
    /** 单租户队列已满拒绝总计。 */
    private final Counter tenantQueueFullCounter;
    /** 活跃租户总量已满拒绝总计。 */
    private final Counter tenantCapacityFullCounter;
    /** 应用关闭后收到提交的拒绝总计。 */
    private final Counter closedCounter;
    /** 关闭标记；关闭后不能接受数据库租约工作。 */
    private boolean closed;

    /**
     * Spring 装配入口；隔离测试没有指标注册表时使用进程内注册表。
     *
     * @param registryProvider 可选指标注册表
     */
    @Autowired
    public TenantFairScheduler(ObjectProvider<MeterRegistry> registryProvider) {
        this(registryProvider.getIfAvailable(SimpleMeterRegistry::new), DEFAULT_WORKER_COUNT,
                DEFAULT_PER_TENANT_QUEUE_CAPACITY, DEFAULT_ACTIVE_TENANT_CAPACITY);
    }

    /**
     * 创建可配置的公平调度器，供故障验收使用较小容量稳定触发拒绝。
     *
     * @param meterRegistry 指标注册表
     * @param workerCount 跨租户工作并发
     * @param perTenantQueueCapacity 单租户等待容量
     * @param activeTenantCapacity 活跃租户容量
     */
    TenantFairScheduler(MeterRegistry meterRegistry, int workerCount, int perTenantQueueCapacity,
                        int activeTenantCapacity) {
        if (workerCount < 2 || perTenantQueueCapacity < 1 || activeTenantCapacity < workerCount) {
            throw new IllegalArgumentException("公平调度容量必须满足 worker>=2、tenantQueue>=1、activeTenants>=worker");
        }
        this.perTenantQueueCapacity = perTenantQueueCapacity;
        this.activeTenantCapacity = activeTenantCapacity;
        ThreadFactory threadFactory = Thread.ofPlatform().daemon(true).name("task-tenant-fair-", 0).factory();
        this.executor = Executors.newFixedThreadPool(workerCount, threadFactory);
        this.acceptedCounter = counter(meterRegistry, "accepted");
        this.tenantQueueFullCounter = counter(meterRegistry, "tenant_queue_full");
        this.tenantCapacityFullCounter = counter(meterRegistry, "tenant_capacity_full");
        this.closedCounter = counter(meterRegistry, "closed");
        Gauge.builder(QUEUED_METRIC, queuedTasks, AtomicInteger::get)
                .description("任务公平调度器内按租户等待的工作数")
                .register(meterRegistry);
        Gauge.builder(ACTIVE_TENANTS_METRIC, activeTenants, AtomicInteger::get)
                .description("任务公平调度器当前活跃租户数")
                .register(meterRegistry);
    }

    /**
     * 提交一项带权威租户归属的任务推进工作。
     *
     * @param tenantId 项目所有者租户 ID
     * @param work 已持有数据库短租约的工作
     * @return true 表示已经运行或排队；false 表示保留租约等待到期后重试
     */
    public boolean submit(UUID tenantId, Runnable work) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(work, "work");
        synchronized (monitor) {
            if (closed) {
                closedCounter.increment();
                return false;
            }
            TenantQueue queue = tenantQueues.get(tenantId);
            if (queue == null) {
                if (tenantQueues.size() >= activeTenantCapacity) {
                    tenantCapacityFullCounter.increment();
                    return false;
                }
                queue = new TenantQueue();
                tenantQueues.put(tenantId, queue);
                activeTenants.incrementAndGet();
                acceptedCounter.increment();
                executor.execute(() -> runOne(tenantId, work));
                return true;
            }
            if (queue.waiting.size() >= perTenantQueueCapacity) {
                tenantQueueFullCounter.increment();
                return false;
            }
            queue.waiting.addLast(work);
            queuedTasks.incrementAndGet();
            acceptedCounter.increment();
            return true;
        }
    }

    /**
     * 运行一项工作，并把同租户下一项重新放到全局执行器尾部形成租户间轮转机会。
     *
     * @param tenantId 当前租户
     * @param work 当前工作
     */
    private void runOne(UUID tenantId, Runnable work) {
        // 公平调度线程执行的是持久任务扫描结果，必须与 HTTP 控制面连接池隔离。
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            try {
                work.run();
            } finally {
                scheduleNext(tenantId);
            }
        }
    }

    /**
     * 完成后只续接同租户一项；不能在当前线程清空整个租户队列，否则它会长期霸占工作线程。
     *
     * @param tenantId 已完成工作的租户
     */
    private void scheduleNext(UUID tenantId) {
        synchronized (monitor) {
            TenantQueue queue = tenantQueues.get(tenantId);
            if (queue == null) {
                return;
            }
            Runnable next = queue.waiting.pollFirst();
            if (next == null || closed) {
                if (closed) {
                    queuedTasks.addAndGet(-queue.waiting.size());
                    queue.waiting.clear();
                }
                tenantQueues.remove(tenantId);
                activeTenants.decrementAndGet();
                return;
            }
            queuedTasks.decrementAndGet();
            // 固定线程池按 FIFO 接受各租户续接项，避免同租户在当前线程内连续清空队列。
            executor.execute(() -> runOne(tenantId, next));
        }
    }

    /**
     * 创建固定结果标签的提交计数器。
     *
     * @param meterRegistry 指标注册表
     * @param result 有限结果分类
     * @return 已注册计数器
     */
    private static Counter counter(MeterRegistry meterRegistry, String result) {
        return Counter.builder(SUBMISSION_METRIC)
                .description("任务公平调度器提交结果")
                .tag("result", result)
                .register(meterRegistry);
    }

    /** 应用关闭时停止接受新租约并中断仍在运行的后台工作。 */
    @Override
    @PreDestroy
    public void close() {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            int waiting = tenantQueues.values().stream().mapToInt(queue -> queue.waiting.size()).sum();
            queuedTasks.addAndGet(-waiting);
            tenantQueues.values().forEach(queue -> queue.waiting.clear());
        }
        executor.shutdownNow();
    }

    /** 单租户的有限等待队列；映射中存在即表示已有一项正在运行或等待执行器线程。 */
    private static final class TenantQueue {

        /** 不含当前运行项的 FIFO 队列。 */
        private final ArrayDeque<Runnable> waiting = new ArrayDeque<>();
    }
}
