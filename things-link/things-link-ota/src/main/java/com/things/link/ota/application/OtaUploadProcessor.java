package com.things.link.ota.application;

import com.things.link.ota.domain.OtaUploadErrorCode;
import com.things.link.ota.domain.OtaUploadSession;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.storage.VersionedPrivateObjectStorage;
import com.things.link.support.storage.VersionedStorageControl;
import com.things.link.support.storage.VersionedStorageException;
import com.things.link.support.tenant.ScopedTenantWork;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

/** 无业务长事务的上传/回读编排；网络结果不确定时保留持久身份交给恢复。 */
@Component
public class OtaUploadProcessor {
    /** 仅短事务状态服务。 */
    private final OtaUploadService service;
    /** 心跳与任何业务调度器隔离；独立事务自身有5秒预算。 */
    private final ScheduledThreadPoolExecutor heartbeats = new ScheduledThreadPoolExecutor(2,
            Thread.ofPlatform().name("tc-ota-upload-lease-", 0).inheritInheritableThreadLocals(false).factory());
    /** 本实例在途租约，用于物理调用取消。 */
    private final Set<Lease> leases = ConcurrentHashMap.newKeySet();
    /** 禁止关闭后新建租约。 */
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 注入状态服务并让已取消定时任务立即出队。 */
    public OtaUploadProcessor(OtaUploadService service) {
        this.service = service;
        heartbeats.setRemoveOnCancelPolicy(true);
    }

    /** 接收前开始覆盖整个工作周期的租约，不依靠读取线程访问数据库。 */
    public Lease begin(OtaUploadSession session) {
        if (closed.get() || session.leaseToken() == null) throw unavailable();
        long started = System.nanoTime();
        // 领取到实际执行之间可能已停顿过期；首个外部动作前必须验证数据库当前token。
        if (!ScopedTenantWork.call(scope(session), () -> service.renew(session))) throw unavailable();
        synchronized (this) {
            if (closed.get()) throw unavailable();
            Lease lease = new Lease(session, started);
            leases.add(lease);
            lease.future = heartbeats.scheduleWithFixedDelay(lease::renew, 5, 5, TimeUnit.SECONDS);
            return lease;
        }
    }

    /** 只调用一次上传；回执登记和采用失败必须先权威重读，禁止盲删。 */
    public OtaUploadSession process(OtaUploadSession session, Path file, Lease lease, BooleanSupplier cancelled) {
        boolean writing = false;
        boolean receipt = false;
        try {
            if (lease.cancelled() || cancelled.getAsBoolean()) throw unavailable();
            VersionedPrivateObjectStorage storage = service.requireStorage();
            service.markWriting(session);
            writing = true;
            var version = storage.upload(new VersionedPrivateObjectStorage.WriteRequest(identity(session), file),
                    control(lease, cancelled));
            receipt = true;
            if (!service.recordVersion(session, version.versionId())) throw unavailable();
            storage.verify(version, session.expectedLength(), session.expectedSha256(), control(lease, cancelled));
            return service.adopt(session);
        } catch (RuntimeException failure) {
            try {
                OtaUploadSession current = service.reread(session);
                if ("VERIFIED".equals(current.status())) return current;
            } catch (RuntimeException rereadFailure) {
                // 回读同样失败时不尝试删除；租约到期后从WRITING恢复。
            }
            boolean unknown = receipt || writing;
            if (!receipt && failure instanceof VersionedStorageException storageFailure) {
                unknown = storageFailure.mayHaveWritten();
            }
            try { service.fail(session, reason(failure), unknown); }
            catch (RuntimeException persistenceFailure) {
                // 持久服务不可用时保留原始WRITING/租约事实，绝不以异常清除对象。
            }
            if (failure instanceof BusinessException business) throw business;
            if (failure instanceof VersionedStorageException storageFailure
                    && storageFailure.reason() == VersionedStorageException.Reason.INTEGRITY) {
                throw new BusinessException(OtaUploadErrorCode.CONTENT_MISMATCH);
            }
            throw unavailable();
        }
    }

    /** 可信数据库领取身份用于显式工作线程scope，不接受客户端直接提供tenant。 */
    public static TenantScope scope(OtaUploadSession session) {
        return new TenantScope(session.tenantId(), session.projectId(), session.createdBy());
    }

    /** 与持久写入请求一致的独占对象身份。 */
    public static VersionedPrivateObjectStorage.WriteIdentity identity(OtaUploadSession session) {
        return new VersionedPrivateObjectStorage.WriteIdentity(session.bucket(), session.objectKey(), session.requestId());
    }

    /** 单次存储调用共享90秒预算，外部取消读取必须是非阻塞布尔信号。 */
    public static VersionedStorageControl control(Lease lease, BooleanSupplier cancelled) {
        return new VersionedStorageControl(Duration.ofSeconds(90), () -> lease.cancelled() || cancelled.getAsBoolean());
    }

    /** 对外和持久化只使用固定分类，不保存供应商异常消息或对象地址。 */
    public static String reason(RuntimeException failure) {
        return failure instanceof VersionedStorageException storageFailure
                ? storageFailure.reason().name() : "DEPENDENCY_FAILED";
    }

    /** 先取消本实例调用，后有界等待心跳结束；不触碰共享存储client的其他工作。 */
    @PreDestroy
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        leases.forEach(Lease::close);
        heartbeats.shutdownNow();
        try {
            if (!heartbeats.awaitTermination(10, TimeUnit.SECONDS)) throw new IllegalStateException("OTA上传心跳未完成关闭");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OTA上传心跳关闭中断");
        }
    }

    /** 固定服务不可用，不传播底层URL或数据库内容。 */
    private static BusinessException unavailable() { return new BusinessException(OtaUploadErrorCode.UNAVAILABLE); }

    /** 可取消租约句柄；读取不访问数据库，心跳失败或本地安全截止都会拒绝继续。 */
    public final class Lease implements AutoCloseable {
        /** 被领取的精确token身份。 */
        private final OtaUploadSession session;
        /** 只允许由true停止，不自动复活。 */
        private final AtomicBoolean cancelled = new AtomicBoolean();
        /** 本地截止比数据库租约提前10秒；心跳阻塞也不会永久授权网络。 */
        private volatile long renewedAt;
        /** 可注销固定间隔心跳。 */
        private volatile ScheduledFuture<?> future;
        /** 绑定已提交的会话租约。 */
        private Lease(OtaUploadSession session, long started) { this.session = session; this.renewedAt = started; }
        /** 非阻塞失权判断。 */
        public boolean cancelled() {
            return cancelled.get() || closed.get() || System.nanoTime() - renewedAt >= TimeUnit.SECONDS.toNanos(110);
        }
        /** 在无继承上下文线程上显式恢复可信范围，失败只取消不重试复活。 */
        private void renew() {
            if (cancelled()) { close(); return; }
            try {
                long started = System.nanoTime();
                boolean renewed = ScopedTenantWork.call(scope(session), () -> service.renew(session));
                if (renewed) renewedAt = started;
                else close();
            } catch (RuntimeException failure) { close(); }
        }
        /** 释放自身任务和信号，已经开始的物理请求将在watchdog观察取消。 */
        @Override
        public void close() {
            cancelled.set(true);
            ScheduledFuture<?> scheduled = future;
            if (scheduled != null) scheduled.cancel(false);
            leases.remove(this);
        }
    }
}
