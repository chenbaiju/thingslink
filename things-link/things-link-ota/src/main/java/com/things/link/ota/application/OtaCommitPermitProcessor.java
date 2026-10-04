package com.things.link.ota.application;

import com.things.link.ota.domain.OtaCommitPermitDeliveryRepository;
import com.things.link.support.tenant.DataPlaneDatabase;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 两个实际执行槽，领取和网络分离；退出只承认线程和HTTP调用物理结束。 */
@Component
@DataPlaneDatabase
public class OtaCommitPermitProcessor implements SmartLifecycle {
    /** 固定分类避免原始网络异常泄露连接信息。 */
    private static final Logger LOG = LoggerFactory.getLogger(OtaCommitPermitProcessor.class);
    /** 领取、预留和观察分别走Spring事务代理。 */ private final OtaCommitPermitDeliveryService service;
    /** 单次有界实际网络。 */ private final OtaCommitPermitPublisher publisher;
    /** 测试显式驱动时关闭自动领取。 */ private final boolean enabled;
    /** 实际线程和排队总和最多两个。 */ private final Semaphore slots = new Semaphore(2);
    /** 不继承账号上下文的独立有限线程。 */
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2), Thread.ofPlatform().name("tc-ota-commit-permit-", 0)
                    .inheritInheritableThreadLocals(false).factory(), new ThreadPoolExecutor.AbortPolicy());
    /** 关闭等待已经进入的短领取退出，不能先销毁数据库。 */
    private final ReentrantReadWriteLock admission = new ReentrantReadWriteLock();
    /** 是否接受新工作。 */ private final AtomicBoolean running = new AtomicBoolean();
    /** 物理资源关闭后不能重启。 */ private final AtomicBoolean closed = new AtomicBoolean();

    /** 只注入端口，缺配置由服务安全暂停，不创建默认成功传输。 */
    public OtaCommitPermitProcessor(OtaCommitPermitDeliveryService service, OtaCommitPermitPublisher publisher,
            @Value("${things-link.ota.commit-permit.enabled:true}") boolean enabled) {
        this.service = service; this.publisher = publisher; this.enabled = enabled;
    }
    /** 容量已满不领取，不在共享调度线程调用HTTP。 */
    @Scheduled(fixedDelayString = "${things-link.ota.commit-permit.delay-millis:1000}", scheduler = "otaUploadScheduler")
    public void tick() {
        admission.readLock().lock();
        try {
            if (!enabled || !running.get() || !slots.tryAcquire()) return;
            boolean submitted = false;
            try {
                var found = service.claimOne();
                if (found.isEmpty() || !running.get()) return;
                var claim = found.orElseThrow();
                workers.execute(() -> {
                    try { processOwned(claim); }
                    finally { slots.release(); }
                });
                submitted = true;
            } catch (RuntimeException failure) {
                LOG.warn("OTA提交许可领取未完成，保留租约，分类=DEPENDENCY_FAILED");
            } finally { if (!submitted) slots.release(); }
        } finally { admission.readLock().unlock(); }
    }
    /** 已占物理执行名额；任何未知提交或回执都留持久尝试恢复，不循环重发。 */
    private void processOwned(OtaCommitPermitDeliveryRepository.Claim claim) {
        if (closed.get()) return;
        try {
            var ready = service.prepare(claim.permit().id(), claim.leaseToken());
            if (ready.isEmpty()) return;
            var prepared = ready.orElseThrow();
            var transport = prepared.transport();
            Instant expiry = Instant.ofEpochSecond(transport.permit().deadlineAt().getEpochSecond());
            Instant limit = expiry.isBefore(prepared.leaseUntil()) ? expiry : prepared.leaseUntil();
            Instant now = Instant.now();
            Duration remaining = Duration.between(now, limit);
            Duration exchangeRoom = Duration.between(now, expiry).minusSeconds(1);
            if (exchangeRoom.compareTo(remaining) < 0) remaining = exchangeRoom;
            OtaCommitPermitPublisher.Result result;
            if (closed.get() || remaining.compareTo(Duration.ofMillis(1)) < 0) {
                result = new OtaCommitPermitPublisher.Result(OtaCommitPermitPublisher.Outcome.UNKNOWN, null,
                        "NOT_STARTED_BEFORE_STOP_OR_DEADLINE");
            } else {
                Duration budget = remaining.compareTo(Duration.ofSeconds(5)) > 0 ? Duration.ofSeconds(5) : remaining;
                result = publisher.publish(prepared.route(), transport.permit().canonical(), expiry, limit, budget);
            }
            service.complete(transport.id(), transport.reservationToken(), result);
        } catch (RuntimeException failure) {
            LOG.warn("OTA提交许可处理未完成，保留原尝试等待恢复，分类=DEPENDENCY_FAILED");
        }
    }
    /** Spring就绪后接受新领取，物理关闭不可逆。 */
    @Override public synchronized void start() { if (!closed.get()) running.set(true); }
    /** 关闭先拒绝新领取与新HTTP，再等待真实工作退出；未知回执由数据库恢复。 */
    @Override public synchronized void stop() {
        running.set(false);
        closed.set(true);
        publisher.close();
        admission.writeLock().lock();
        try { workers.shutdown(); }
        finally { admission.writeLock().unlock(); }
        try {
            if (!workers.awaitTermination(12, TimeUnit.SECONDS)) {
                throw new IllegalStateException("OTA提交许可物理工作未完成关闭");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OTA提交许可物理关闭被中断");
        }
    }
    /** 未确认真实退出时不能调用成功回调。 */
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    /** 当前是否接受新工作。 */
    @Override public boolean isRunning() { return running.get(); }
    /** 早于数据库和共享调度器释放本域工作。 */
    @Override public int getPhase() { return Integer.MAX_VALUE; }
}
