package com.things.link.ota.application;

import com.things.link.ota.domain.OtaPublication;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.storage.VersionedPrivateObjectStorage;
import com.things.link.support.storage.VersionedStorageControl;
import com.things.link.support.storage.VersionedStorageException;
import com.things.link.support.tenant.DataPlaneDatabase;
import com.things.link.support.tenant.ScopedTenantWork;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 持久发布的有界外部编排；实际线程退出前不释放名额，未知签名不自动重发。 */
@Component
@DataPlaneDatabase
public class OtaPublicationProcessor implements SmartLifecycle {
    /** 只记录固定分类，禁止签名/URL/供应商异常链。 */
    private static final Logger LOG = LoggerFactory.getLogger(OtaPublicationProcessor.class);
    /** 业务短事务服务。 */ private final OtaPublicationService service;
    /** 原子信任端口，协调器每次实际重读。 */ private final OtaTrustService trust;
    /** 已有受控版本对象技术入口。 */ private final OtaUploadService storageService;
    /** 当前进程执行及等待合计最多两个工作，不无界排队。 */ private final Semaphore slots = new Semaphore(2);
    /** 只跟踪当前实际调用，不跨上下文继承用户身份。 */ private final Set<Lease> active = ConcurrentHashMap.newKeySet();
    /** 两个真实执行线程；容量2仅跨越线程交接空隙，总任务始终受2个名额约束。 */
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), Thread.ofPlatform().name("tc-ota-publication-", 0)
                    .inheritInheritableThreadLocals(false).factory());
    /** 独立短事务心跳；取消任务立即移除。 */
    private final ScheduledThreadPoolExecutor heartbeats = new ScheduledThreadPoolExecutor(2,
            Thread.ofPlatform().name("tc-ota-publication-lease-", 0).inheritInheritableThreadLocals(false).factory());
    /** 不执行数据库的独立截止信号，慢心跳不能延后90秒预算取消。 */
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().name("tc-ota-publication-deadline-", 0).inheritInheritableThreadLocals(false).factory());
    /** 测试共享栈禁止后台抢取当前夹具。 */ private final boolean enabled;
    /** 生命周期与领取互斥。 */ private final AtomicBoolean running = new AtomicBoolean();
    /** 销毁后不能重新启动执行器。 */ private final AtomicBoolean closed = new AtomicBoolean();

    /** 构造只绑定端口，绝不创建默认成功signer。 */
    public OtaPublicationProcessor(OtaPublicationService service, OtaTrustService trust, OtaUploadService storageService,
            @Value("${things-link.ota.publication.enabled:true}") boolean enabled) {
        this.service = service; this.trust = trust; this.storageService = storageService; this.enabled = enabled;
        heartbeats.setRemoveOnCancelPolicy(true);
        deadlines.setRemoveOnCancelPolicy(true);
    }

    /** 借用已有OTA调度入口只做有界领取，外部调用在本类独立执行线程。 */
    @Scheduled(fixedDelayString = "${things-link.ota.publication.delay-millis:1000}", scheduler = "otaUploadScheduler")
    public void tick() {
        if (!enabled || !running.get() || !slots.tryAcquire()) return;
        boolean submitted = false;
        try {
            var claimed = service.claim();
            if (claimed.isEmpty() || claimed.get().leaseToken() == null) return;
            OtaPublication value = claimed.get();
            workers.execute(() -> {
                try { ScopedTenantWork.run(scope(value), () -> processOwned(value)); }
                finally { slots.release(); }
            });
            submitted = true;
        } catch (RejectedExecutionException failure) {
            // 未执行的SIGNING由租约终结；绝不在拒绝路径重新发送。
            LOG.warn("OTA发布执行容量已关闭");
        } catch (RuntimeException failure) {
            LOG.warn("OTA发布领取暂不可用，分类=DEPENDENCY_FAILED");
        } finally { if (!submitted) slots.release(); }
    }

    /** 测试与受限领取共用真实执行入口；调用方须建立可信scope并持有执行名额。 */
    public void process(OtaPublication claimed) {
        if (!slots.tryAcquire()) return;
        try { ScopedTenantWork.run(scope(claimed), () -> processOwned(claimed)); }
        finally { slots.release(); }
    }

    /** 已占有物理名额的内部工作，所有状态写都有精确租约。 */
    private void processOwned(OtaPublication claimed) {
        if (claimed.leaseToken() == null || closed.get()) return;
        Lease lease = new Lease(claimed);
        active.add(lease);
        boolean signerEntered = false;
        try {
            OtaPublicationService.Context context = service.prepare(claimed);
            lease.future = heartbeats.scheduleWithFixedDelay(lease::renew, 5, 5, TimeUnit.SECONDS);
            lease.deadline = deadlines.scheduleWithFixedDelay(() -> lease.control.cancelled(), 0, 100, TimeUnit.MILLISECONDS);
            lease.control.check();
            if ("SIGNING".equals(context.publication().status())) {
                OtaControlledReleaseSigner signer = service.requireSigner();
                OtaSigningCoordinator coordinator = new OtaSigningCoordinator(
                        request -> signer.sign(request, lease.control),
                        domain -> service.binding(claimed, domain));
                signerEntered = true;
                var signed = coordinator.sign(claimed.requestId(), context.publication().canonicalManifest());
                lease.control.check();
                service.signed(claimed, signed);
            }
            OtaPublicationService.Context ready = service.prepare(claimed);
            if (!"SIGNED".equals(ready.publication().status())) throw new IllegalStateException("发布签名阶段漂移");
            lease.control.check();
            long remaining = lease.control.remainingNanos();
            if (remaining < TimeUnit.MILLISECONDS.toNanos(1)) { lease.control.check(); throw new IllegalStateException("发布预算耗尽"); }
            var upload = ready.upload();
            var version = new VersionedPrivateObjectStorage.VersionRef(upload.bucket(), upload.objectKey(), upload.versionId());
            storageService.requireStorage().verify(version, upload.expectedLength(), upload.expectedSha256(),
                    new VersionedStorageControl(Duration.ofNanos(remaining), lease.control::cancelled));
            lease.control.check();
            service.commit(claimed);
        } catch (RuntimeException failure) {
            settleFailure(claimed, signerEntered, failure);
        } finally {
            lease.close();
            active.remove(lease);
        }
    }

    /** 提交结果未知先回读；SIGNED的临时存储故障只等待租约恢复，不重新签名。 */
    private void settleFailure(OtaPublication claimed, boolean signerEntered, RuntimeException failure) {
        try {
            OtaPublication actual = service.reread(claimed);
            if ("COMMITTED".equals(actual.status())) return;
            boolean definiteRejection = failure instanceof BusinessException || failure instanceof IllegalArgumentException
                    || failure instanceof VersionedStorageException storage
                    && storage.reason() == VersionedStorageException.Reason.INTEGRITY;
            if ("SIGNED".equals(actual.status()) && !definiteRejection) return;
            boolean unknown = signerEntered && "SIGNING".equals(actual.status())
                    && !(failure instanceof BusinessException) && !(failure instanceof IllegalArgumentException);
            if (failure instanceof OtaSigningCoordinator.Failure signatureFailure) {
                unknown = signatureFailure.reason() == OtaSigningCoordinator.Reason.SIGNER_UNAVAILABLE;
            }
            String reason = failure instanceof BusinessException ? "QUALIFICATION_CHANGED"
                    : failure instanceof VersionedStorageException storage ? storage.reason().name()
                    : failure instanceof OtaSigningCoordinator.Failure signature ? signature.reason().name()
                    : "DEPENDENCY_FAILED";
            service.failed(claimed, unknown, reason);
        } catch (RuntimeException persistenceFailure) {
            // 保留领取事实，后续只能从持久阶段恢复；不删除固定对象、不伪造失败提交。
            LOG.warn("OTA发布结果暂不可恢复，分类=PERSISTENCE_UNAVAILABLE");
        }
    }

    /** 仅使用受限数据库领取的可信身份。 */
    public static TenantScope scope(OtaPublication value) {
        return new TenantScope(value.tenantId(), value.projectId(), value.createdBy());
    }
    /** 开启领取不执行网络。 */ @Override public void start() { if (!closed.get()) running.set(true); }
    /** 关闭先停止领取和信号，实际外部工作未结束则明确报告失败。 */
    @Override public void stop() {
        if (!closed.compareAndSet(false, true)) {
            if (!workers.isTerminated() || !heartbeats.isTerminated() || !deadlines.isTerminated() || !active.isEmpty()) {
                throw new IllegalStateException("OTA发布物理关闭仍未完成");
            }
            return;
        }
        running.set(false);
        active.forEach(value -> value.control.cancel());
        workers.shutdown();
        heartbeats.shutdownNow();
        deadlines.shutdownNow();
        try {
            boolean workersStopped = workers.awaitTermination(10, TimeUnit.SECONDS);
            boolean heartbeatsStopped = heartbeats.awaitTermination(5, TimeUnit.SECONDS);
            boolean deadlinesStopped = deadlines.awaitTermination(5, TimeUnit.SECONDS);
            if (!workersStopped || !heartbeatsStopped || !deadlinesStopped || !active.isEmpty()) throw new IllegalStateException("OTA发布物理执行尚未关闭");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OTA发布关闭被中断");
        }
    }
    /** 只有已实际完成关闭才能通知生命周期回调。 */
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    /** 返回实际领取生命周期。 */ @Override public boolean isRunning() { return running.get(); }
    /** 先于共享调度器关闭本域外部操作。 */ @Override public int getPhase() { return Integer.MAX_VALUE; }
    /** 自动参与应用生命周期。 */ @Override public boolean isAutoStartup() { return true; }

    /** 单次工作租约；心跳失败只能撤销资格，不续接其他token。 */
    private final class Lease implements AutoCloseable {
        /** 实际领取身份。 */ private final OtaPublication value;
        /** 不可复活的失权标志。 */ private final AtomicBoolean lost = new AtomicBoolean();
        /** 单次90秒包含签名与对象复验。 */
        private final OtaSigningControl control = new OtaSigningControl(Duration.ofSeconds(90),
                () -> lost.get() || closed.get());
        /** 当前周期任务。 */ private ScheduledFuture<?> future;
        /** 独立截止信号任务。 */ private ScheduledFuture<?> deadline;
        /** 保存本次领取。 */ private Lease(OtaPublication value) { this.value = value; }
        /** DB延迟有5秒事务预算；首因固定后不继续续租。 */
        private void renew() {
            if (control.cancelled()) return;
            try {
                if (!ScopedTenantWork.call(scope(value), () -> service.renew(value))) lost.set(true);
            } catch (RuntimeException failure) { lost.set(true); }
            control.cancelled();
        }
        /** 物理调用已返回后注销本次心跳。 */
        @Override public void close() {
            lost.set(true); control.cancel();
            if (future != null) future.cancel(false);
            if (deadline != null) deadline.cancel(false);
        }
    }
}
