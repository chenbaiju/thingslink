package com.things.link.ota.application;

import com.things.link.ota.domain.OtaDownloadAuthorizationRepository;
import com.things.link.support.storage.VersionedPrivateObjectStorage;
import com.things.link.support.storage.VersionedStorageControl;
import com.things.link.support.tenant.DataPlaneDatabase;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 有限签址及秘密发送工作器；网络不占数据库事务或共享tick线程。 */
@Component
@DataPlaneDatabase
public class OtaDownloadAuthorizationProcessor implements SmartLifecycle {
    /** 固定诊断不附任何秘密对象或原始异常。 */
    private static final Logger LOG = LoggerFactory.getLogger(OtaDownloadAuthorizationProcessor.class);
    /** 独立短事务入口。 */
    private final OtaDownloadAuthorizationService service;
    /** 共享对象端口仅借用，不由本工作器关闭。 */
    private final ObjectProvider<VersionedPrivateObjectStorage> storages;
    /** 本域秘密发送器。 */
    private final OtaDownloadResponsePublisher publisher;
    /** 自动处理受控开关。 */
    private final boolean enabled;
    /** 物理执行和等待合计最多两个。 */
    private final Semaphore slots = new Semaphore(2);
    /** 不继承调用方账号上下文。 */
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2), Thread.ofPlatform().name("tc-ota-download-", 0)
                    .inheritInheritableThreadLocals(false).factory(), new ThreadPoolExecutor.AbortPolicy());
    /** 关闭必须等已进入的领取事务退出。 */
    private final ReentrantReadWriteLock admission = new ReentrantReadWriteLock();
    /** 是否接受新领取。 */
    private final AtomicBoolean running = new AtomicBoolean();
    /** 实际存储watchdog可跨线程读取此取消信号。 */
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 固定装配依赖，缺少唯一storage由服务在预留前检查。 */
    public OtaDownloadAuthorizationProcessor(OtaDownloadAuthorizationService service,
            ObjectProvider<VersionedPrivateObjectStorage> storages, OtaDownloadResponsePublisher publisher,
            @Value("${things-link.ota.download-authorization.enabled:true}") boolean enabled) {
        this.service = Objects.requireNonNull(service);
        this.storages = Objects.requireNonNull(storages);
        this.publisher = Objects.requireNonNull(publisher);
        this.enabled = enabled;
    }

    /** 有名额才领取；只提交有限工作，不在共享调度线程签址或发送。 */
    @Scheduled(fixedDelayString = "${things-link.ota.download-authorization.delay-millis:1000}", scheduler = "otaUploadScheduler")
    public void tick() {
        admission.readLock().lock();
        try {
            if (!enabled || !running.get() || closed.get() || !slots.tryAcquire()) return;
            boolean submitted = false;
            try {
                var found = service.claimOne();
                if (found.isEmpty() || !running.get()) return;
                var claim = found.orElseThrow();
                workers.execute(() -> {
                    try { process(claim); }
                    finally { slots.release(); }
                });
                submitted = true;
            } catch (RuntimeException failure) {
                LOG.warn("OTA下载授权领取未完成，分类=DEPENDENCY_FAILED");
            } finally {
                if (!submitted) slots.release();
            }
        } finally { admission.readLock().unlock(); }
    }

    /** 同一已领取工作至多签址一次，封存不确定不能继续发送或原地重签。 */
    private void process(OtaDownloadAuthorizationRepository.Claim claim) {
        if (closed.get()) return;
        try {
            var signing = service.prepareSigning(claim.authorizationId(), claim.leaseToken());
            if (signing.isPresent()) {
                var prepared = signing.orElseThrow();
                try {
                    Instant expiresAt = prepared.claim().responseExpiresAt();
                    Instant leaseUntil = prepared.claim().leaseUntil();
                    Instant limit = expiresAt.isBefore(leaseUntil) ? expiresAt : leaseUntil;
                    Duration budget = budget(Instant.now(), expiresAt, leaseUntil);
                    if (budget.compareTo(Duration.ofMillis(1)) < 0) {
                        unknown(claim);
                        return;
                    }
                    Thread caller = Thread.currentThread();
                    var control = new VersionedStorageControl(budget,
                            () -> closed.get() || caller.isInterrupted() || !Instant.now().isBefore(limit));
                    // 单调预算先启动，配置读取或随后暂停不能刷新TTL准备阶段的预算。
                    var configured = storages.orderedStream().limit(2).toList();
                    Instant now = Instant.now();
                    long seconds = expiresAt.getEpochSecond() - now.getEpochSecond();
                    if (configured.size() != 1 || seconds < 1 || seconds > 54
                            || control.cancelled() || control.timedOut()) {
                        unknown(claim);
                        return;
                    }
                    var url = configured.getFirst().presignGet(prepared.version(), Duration.ofSeconds(seconds), control);
                    if (closed.get() || control.cancelled() || control.timedOut()) {
                        unknown(claim);
                        return;
                    }
                    if (!service.seal(claim.authorizationId(), claim.leaseToken(), url)) return;
                } catch (RuntimeException failure) {
                    unknown(claim);
                    return;
                }
            }
            if (closed.get()) return;
            var sending = service.prepareSend(claim.authorizationId(), claim.leaseToken());
            if (sending.isEmpty()) return;
            var prepared = sending.orElseThrow();
            Duration budget = budget(Instant.now(), prepared.expiresAt(), prepared.leaseUntil());
            OtaDownloadResponsePublisher.Result result;
            if (closed.get() || budget.compareTo(Duration.ofMillis(1)) < 0) {
                result = new OtaDownloadResponsePublisher.Result(OtaDownloadResponsePublisher.Outcome.UNKNOWN, null,
                        "NOT_STARTED_BEFORE_STOP_OR_DEADLINE");
            } else {
                result = publisher.publish(prepared.route(), prepared.canonical(),
                        prepared.expiresAt(), budget);
            }
            service.complete(prepared.transport().id(), prepared.transport().reservationToken(), result);
        } catch (RuntimeException failure) {
            LOG.warn("OTA下载授权处理未完成，保留原事实，分类=DEPENDENCY_FAILED");
        }
    }

    /** 只提交固定未知分类，数据库不可用时保留原SIGNING供失租恢复。 */
    private void unknown(OtaDownloadAuthorizationRepository.Claim claim) {
        try { service.signingUnknown(claim.authorizationId(), claim.leaseToken(), "SIGNING_RESULT_UNKNOWN"); }
        catch (RuntimeException failure) { LOG.warn("OTA下载签址未知记录未完成，分类=DEPENDENCY_FAILED"); }
    }

    /** 网络预算不越过任一固定期限，且独立上限五秒。 */
    private static Duration budget(Instant now, Instant expiresAt, Instant leaseUntil) {
        Instant limit = expiresAt.isBefore(leaseUntil) ? expiresAt : leaseUntil;
        Duration remaining = Duration.between(now, limit);
        return remaining.compareTo(Duration.ofSeconds(5)) > 0 ? Duration.ofSeconds(5) : remaining;
    }

    /** 物理关闭后不可重启。 */
    @Override public synchronized void start() { if (!closed.get()) running.set(true); }
    /** 取消自身工作而非共享storage，等待实际worker及回执处理结束才返回。 */
    @Override public synchronized void stop() {
        running.set(false);
        closed.set(true);
        publisher.close();
        admission.writeLock().lock();
        try { workers.shutdown(); }
        finally { admission.writeLock().unlock(); }
        try {
            if (!workers.awaitTermination(12, TimeUnit.SECONDS)) {
                throw new IllegalStateException("OTA下载授权物理工作未完成关闭");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OTA下载授权物理关闭被中断");
        }
    }
    /** 未确认物理终止不得回调成功。 */
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    /** 是否接受新工作。 */
    @Override public boolean isRunning() { return running.get(); }
    /** 先于数据库和共享调度器完成收束。 */
    @Override public int getPhase() { return Integer.MAX_VALUE; }
}
