package com.things.link.support.scheduling;

import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.support.tenant.DataPlaneDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 告警与规则共享的通知持久工作协调器。
 *
 * <p>四个 worker 和 64 全局队列只承担进程内隔离；真正的崩溃恢复来自各域投递租约，集群级
 * 同租户单并发来自 {@link TenantWorkSlotRepository}。拒绝路径必须释放投递租约，不能丢弃。</p>
 */
@Component
@DataPlaneDatabase
public class NotificationWorkCoordinator implements DisposableBean {

    /** 只记录固定来源与异常类型，不记录租户和通知地址。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationWorkCoordinator.class);
    /** 冻结每实例四个外部通知 worker。 */
    private static final int WORKERS = 4;
    /** 冻结单实例全局待执行上限。 */
    private static final int QUEUE_CAPACITY = 64;
    /** 投递与租户槽共用 30 秒租约；发送适配器上限 10 秒，留足状态 CAS 时间。 */
    private static final Duration LEASE = Duration.ofSeconds(30);

    /** 两个业务域的持久工作来源。 */
    private final List<NotificationWorkSource> sources;
    /** 集群级同租户单并发槽。 */
    private final TenantWorkSlotRepository tenantSlots;
    /** 有界外部 I/O worker；Abort 保证调度线程绝不执行网络调用。 */
    private final ThreadPoolExecutor workers;
    /** 全局低基数队列、公平拒绝与耗时指标。 */
    private final NotificationWorkerMetrics metrics;

    /** @param sources 告警与规则持久工作来源 @param tenantSlots 集群租户槽 */
    @Autowired
    public NotificationWorkCoordinator(
            List<NotificationWorkSource> sources,
            TenantWorkSlotRepository tenantSlots,
            NotificationWorkerMetrics metrics) {
        this.sources = List.copyOf(sources);
        this.tenantSlots = tenantSlots;
        this.metrics = metrics;
        AtomicInteger sequence = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(
                WORKERS,
                WORKERS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "tc-notification-worker-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.metrics.register(this.workers);
    }

    /** 不加载 Actuator 的单元测试入口。 */
    NotificationWorkCoordinator(
            List<NotificationWorkSource> sources,
            TenantWorkSlotRepository tenantSlots) {
        this(sources, tenantSlots,
                new NotificationWorkerMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    /** 每轮按来源轮转领取；每个来源 SQL 自身先按 tenant 取最早一条。 */
    @Scheduled(fixedDelayString = "${things-link.notification.worker.fixed-delay-millis:500}",
            scheduler = "notificationLifecycleScheduler")
    public void dispatchReadyWork() {
        if (sources.isEmpty()) {
            return;
        }
        for (NotificationWorkSource source : sources) {
            try {
                metrics.recordFactAges(source.sourceName(), source.observeAges());
            } catch (RuntimeException exception) {
                // 指标查询失败不能阻止可靠工作领取；下一轮会重试，错误只带固定来源类名。
                LOGGER.error("通知持久事实年龄观测失败 source={}", source.getClass().getSimpleName(), exception);
            }
            int remaining = QUEUE_CAPACITY - workers.getQueue().size();
            if (remaining <= 0) {
                return;
            }
            List<NotificationWorkSource.NotificationWork> claimed;
            try {
                claimed = source.claim(Math.min(remaining, 16), LEASE);
            } catch (RuntimeException exception) {
                LOGGER.error("通知持久工作领取失败 source={}", source.getClass().getSimpleName(), exception);
                continue;
            }
            for (NotificationWorkSource.NotificationWork work : claimed) {
                submit(work);
            }
        }
    }

    /** 进入 worker 前后所有拒绝都释放投递租约，让数据库工作可再次领取。 */
    private void submit(NotificationWorkSource.NotificationWork work) {
        try {
            workers.execute(() -> execute(work));
        } catch (RejectedExecutionException exception) {
            metrics.rejected("queue");
            safelyRelease(work);
        }
    }

    /** 先以短事务竞争集群槽，随后在不持连接的情况下执行真实外部调用。 */
    private void execute(NotificationWorkSource.NotificationWork work) {
        // 调度切面运行在线程池提交者上，ThreadLocal 不会自动传播；worker 入口必须重新声明数据面。
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            TenantWorkSlotRepository.Lease tenantLease = tenantSlots
                    .tryAcquire(TenantWorkSlotRepository.WorkType.NOTIFICATION, work.tenantId(), LEASE)
                    .orElse(null);
            if (tenantLease == null) {
                metrics.rejected("tenant_slot");
                safelyRelease(work);
                return;
            }
            long started = System.nanoTime();
            try {
                work.execute().run();
            } finally {
                metrics.duration(System.nanoTime() - started);
                try {
                    tenantSlots.release(tenantLease);
                } catch (RuntimeException exception) {
                    // 30 秒租约仍会回收；不得因释放失败覆盖真实投递结果。
                    LOGGER.error("通知租户工作槽释放失败，等待租约到期", exception);
                }
            }
        }
    }

    /** 数据库释放失败时等待原投递租约自然到期，不能执行无租约工作。 */
    private static void safelyRelease(NotificationWorkSource.NotificationWork work) {
        try {
            work.release().run();
        } catch (RuntimeException exception) {
            LOGGER.error("通知投递租约释放失败，等待租约到期", exception);
        }
    }

    /** 停止领取后优雅等待短外部调用；超时后中断，数据库租约负责接管。 */
    @Override
    public void destroy() throws InterruptedException {
        workers.shutdown();
        if (!workers.awaitTermination(25, TimeUnit.SECONDS)) {
            workers.shutdownNow();
        }
    }
}
