package com.things.link.ota.application;

import com.things.link.ota.domain.OtaUploadSession;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.storage.VersionedPrivateObjectStorage;
import com.things.link.support.storage.VersionedStorageControl;
import com.things.link.support.storage.VersionedStorageException;
import com.things.link.support.tenant.DataPlaneDatabase;
import com.things.link.support.tenant.ScopedTenantWork;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 上传过期、丢失回执和物理清理的独立有界worker；空盘点永不消除未决写入。 */
@Component
@DataPlaneDatabase
public class OtaUploadRecoveryWorker implements SmartLifecycle {
    /** 固定错误分类日志，禁止供应商异常链。 */
    private static final Logger LOG = LoggerFactory.getLogger(OtaUploadRecoveryWorker.class);
    /** 短事务状态服务。 */
    private final OtaUploadService service;
    /** 与上传共享同一种当前token心跳，不共享接收线程。 */
    private final OtaUploadProcessor processor;
    /** 显式测试停调度开关，生产默认开启。 */
    private final boolean enabled;
    /** 高优先级停机先取消物理调用，再等待调度器结束。 */
    private final AtomicBoolean running = new AtomicBoolean();

    /** 显式绑定依赖和后台触发开关。 */
    public OtaUploadRecoveryWorker(OtaUploadService service, OtaUploadProcessor processor,
            @Value("${things-link.ota.upload.recovery-enabled:true}") boolean enabled) {
        this.service = service;
        this.processor = processor;
        this.enabled = enabled;
    }

    /** 每轮只领取一条，网络不进入领取事务，其他模块调度器不受慢S3阻塞。 */
    @Scheduled(fixedDelayString = "${things-link.ota.upload.recovery-delay-millis:1000}",
            scheduler = "otaUploadScheduler")
    public void tick() {
        if (!enabled || !running.get()) return;
        try {
            service.claimRecovery().ifPresent(session -> ScopedTenantWork.run(OtaUploadProcessor.scope(session),
                    () -> recover(session)));
        } catch (RuntimeException failure) {
            LOG.warn("OTA上传恢复暂未完成，分类={}", OtaUploadProcessor.reason(failure));
        }
    }

    /** 精确领取身份的恢复入口；调用者必须显式建立可信范围。 */
    public void recover(OtaUploadSession claimed) {
        if ("CLEANED".equals(claimed.status())) return;
        try (var lease = processor.begin(claimed)) {
            VersionedStorageControl control = new VersionedStorageControl(Duration.ofSeconds(90),
                    () -> lease.cancelled() || !running.get());
            OtaUploadSession session = rereadOwned(claimed, claimed);
            VersionedPrivateObjectStorage storage = service.requireStorage();
            if (session.writeStartedAt() != null && session.writeSettledAt() == null) {
                var versions = storage.listVersions(session.bucket(), session.objectKey(), null, 2, control);
                if (versions.hasMore() || versions.items().size() != 1 || versions.items().getFirst().deleteMarker()) {
                    service.postpone(session, "UNKNOWN_WRITE");
                    return;
                }
                var probe = storage.probe(OtaUploadProcessor.identity(session), control);
                if (probe.status() != VersionedPrivateObjectStorage.ProbeStatus.MATCHED
                        || !versions.items().getFirst().version().equals(probe.version())) {
                    service.postpone(session, "IDENTITY_CONFLICT");
                    return;
                }
                if (!service.recordVersion(session, probe.version().versionId())) return;
                session = rereadOwned(claimed, session);
            }
            if ("UNKNOWN".equals(session.status()) && session.cancelRequestedAt() != null
                    && session.writeSettledAt() != null) {
                service.markCleanup(session, "CANCELLED");
                session = rereadOwned(claimed, session);
            }
            if ("UNKNOWN".equals(session.status()) && session.cancelRequestedAt() == null
                    && session.versionId() != null) {
                try {
                    storage.verify(version(session), session.expectedLength(), session.expectedSha256(), control);
                    service.adopt(session);
                    return;
                } catch (BusinessException failure) {
                    // 新鲜权限/父状态不再允许采用，已确定写入终结的版本转入回收。
                    service.markCleanup(session, "ADOPTION_REJECTED");
                } catch (VersionedStorageException failure) {
                    if (failure.reason() != VersionedStorageException.Reason.INTEGRITY
                            && failure.reason() != VersionedStorageException.Reason.NOT_FOUND) throw failure;
                    service.markCleanup(session, failure.reason().name());
                }
                session = rereadOwned(claimed, session);
            }
            if (!"CLEANUP_PENDING".equals(session.status())) {
                service.postpone(session, "UNKNOWN_WRITE");
                return;
            }
            if (session.writeStartedAt() == null) {
                service.finishCleanup(session);
                return;
            }
            if (session.writeSettledAt() == null) {
                service.postpone(session, "UNKNOWN_WRITE");
                return;
            }
            // 一轮最多删除32个固定版本、中止32个精确分片。下一轮仍从首屏重新观察。
            var versions = storage.listVersions(session.bucket(), session.objectKey(), null, 32, control);
            for (var item : versions.items()) storage.delete(item.version(), control);
            var uploads = storage.listMultipartUploads(session.bucket(), session.objectKey(), null, 32, control);
            for (var upload : uploads.items()) storage.abortMultipart(upload, control);
            var remainingVersions = storage.listVersions(session.bucket(), session.objectKey(), null, 1, control);
            var remainingUploads = storage.listMultipartUploads(session.bucket(), session.objectKey(), null, 1, control);
            if (remainingVersions.items().isEmpty() && !remainingVersions.hasMore()
                    && remainingUploads.items().isEmpty() && !remainingUploads.hasMore()) {
                service.finishCleanup(session);
            } else service.postpone(session, "CLEANUP_REMAINS");
        } catch (RuntimeException failure) {
            // 状态提交未知也不能继续删除或宣布完成；下轮领取前保留相同身份。
            try { service.postpone(claimed, OtaUploadProcessor.reason(failure)); }
            catch (RuntimeException persistenceFailure) {
                LOG.warn("OTA上传恢复退避未持久化，保留租约等待到期");
            }
        }
    }

    /** 权威刷新不能窃取另一个worker的新租约；本轮能力始终等于原领取token。 */
    private OtaUploadSession rereadOwned(OtaUploadSession claimed, OtaUploadSession session) {
        OtaUploadSession current = service.reread(session);
        if (!Objects.equals(claimed.leaseToken(), current.leaseToken())) {
            throw new VersionedStorageException(VersionedStorageException.Reason.CANCELLED, false);
        }
        return current;
    }

    /** 只能构造持久固定版本，禁止退回当前key读取。 */
    private static VersionedPrivateObjectStorage.VersionRef version(OtaUploadSession session) {
        return new VersionedPrivateObjectStorage.VersionRef(session.bucket(), session.objectKey(), session.versionId());
    }
    /** 应用启动后才允许后台物理操作。 */
    @Override public void start() { running.set(true); }
    /** 先发取消信号，网络watchdog将关闭本轮的实际Call。 */
    @Override public void stop() { running.set(false); }
    /** 同步发信号后允许容器继续关闭。 */
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    /** 返回实际是否接受后台工作。 */
    @Override public boolean isRunning() { return running.get(); }
    /** 早于默认调度器停机，避免等慢网络才触发取消。 */
    @Override public int getPhase() { return Integer.MAX_VALUE; }
}
