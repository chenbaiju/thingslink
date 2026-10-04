package com.things.link.export.application;

import com.things.link.export.domain.ProjectExportClaim;
import com.things.link.export.domain.ProjectExportJob;
import com.things.link.export.domain.ProjectExportJobRepository;
import com.things.link.export.domain.ProjectExportStatus;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.storage.ObjectStorageException;
import com.things.link.support.storage.ObjectUploadAbortedException;
import com.things.link.support.storage.ObjectUploadControl;
import com.things.link.support.storage.PrivateObjectStorage;
import com.things.link.support.tenant.DataPlaneDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 领取、生成、上传并token-fenced完成项目导出任务。 */
@Component
@DataPlaneDatabase
public class ProjectExportWorker implements DisposableBean {

    /** 日志只记录任务身份和稳定失败类型，禁止输出客户数据或对象签名。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectExportWorker.class);
    /** 固定私有桶。 */
    private static final String EXPORT_BUCKET = "export";
    /** 每三十秒续两分钟租约。 */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
    /** 生成和上传共享的单次尝试总预算。 */
    private static final Duration ATTEMPT_TIMEOUT = Duration.ofMinutes(15);

    /** 任务与清理仓储。 */
    private final ProjectExportJobRepository jobRepository;
    /** 单一数据库快照归档器。 */
    private final ProjectExportArchiveGenerator archiveGenerator;
    /** 私有对象存储。 */
    private final PrivateObjectStorage objectStorage;
    /** 成功CAS与审计原子服务。 */
    private final ProjectExportCompletionService completionService;
    /** 稳定worker实例名。 */
    private final String workerName;
    /** 独立续租线程；慢归档不得占用Spring调度线程。 */
    private final ScheduledExecutorService heartbeatExecutor;

    /**
     * 创建导出worker。
     * @param jobRepository 任务仓储
     * @param archiveGenerator 快照归档器
     * @param objectStorage 私有对象存储
     * @param completionService 成功完成服务
     * @param workerName worker实例名
     */
    public ProjectExportWorker(
            ProjectExportJobRepository jobRepository,
            ProjectExportArchiveGenerator archiveGenerator,
            PrivateObjectStorage objectStorage,
            ProjectExportCompletionService completionService,
            @Value("${things-link.export.worker-id:${HOSTNAME:local}}") String workerName) {
        this.jobRepository = jobRepository;
        this.archiveGenerator = archiveGenerator;
        this.objectStorage = objectStorage;
        this.completionService = completionService;
        this.workerName = requireWorkerName(workerName);
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform().name("project-export-lease").daemon(true).unstarted(runnable));
    }

    /** 单轮只领取一条，避免两个最大归档同时耗尽本机磁盘。 */
    @Scheduled(fixedDelayString = "${things-link.export.worker-fixed-delay-millis:1000}",
            initialDelayString = "${things-link.export.worker-initial-delay-millis:0}",
            scheduler = "exportLifecycleScheduler")
    public void processReadyJobs() {
        try {
            jobRepository.claimReady(workerName).ifPresent(this::process);
        } catch (RuntimeException exception) {
            LOGGER.error("项目导出任务领取失败", exception);
        }
    }

    /**
     * 处理一条已经提交租约的任务；公开入口供真实故障验收确定性驱动。
     * @param claim 当前领取身份
     */
    public void process(ProjectExportClaim claim) {
        AtomicBoolean leaseLost = new AtomicBoolean(false);
        ObjectUploadControl attemptControl = ObjectUploadControl.start(ATTEMPT_TIMEOUT, leaseLost::get);
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                // 到达总预算后停止续租，使卡住的网络上传不能无限占有任务。
                if (attemptControl.timedOut()) {
                    leaseLost.set(true);
                    return;
                }
                if (!jobRepository.renew(claim.jobId(), claim.leaseToken())) leaseLost.set(true);
            } catch (RuntimeException exception) {
                // 数据库故障期间不假定仍持租约；生成线程会尽快在下一行或ZIP块停止。
                leaseLost.set(true);
                LOGGER.warn("项目导出租约续期失败：exportId={}", claim.jobId(), exception);
            }
        }, HEARTBEAT_INTERVAL.toSeconds(), HEARTBEAT_INTERVAL.toSeconds(), TimeUnit.SECONDS);

        Optional<TenantScope> previous = TenantContext.current();
        UUID uploadId = null;
        String objectKey = null;
        boolean uploaded = false;
        boolean adopted = false;
        AdoptionState adoptionState = AdoptionState.NOT_ADOPTED;
        boolean completionFailureHandled = false;
        try {
            TenantContext.set(new TenantScope(claim.tenantId(), claim.projectId(), claim.requesterAccountId()));
            try (GeneratedProjectExport generated = archiveGenerator.generate(claim, attemptControl)) {
                if (leaseLost.get()) return;
                uploadId = Uuid7.generate();
                objectKey = objectKey(claim, uploadId);
                if (!jobRepository.registerUpload(
                        claim.jobId(), claim.leaseToken(), Uuid7.generate(), uploadId,
                        objectKey, generated.snapshotAt())) {
                    return;
                }
                objectStorage.upload(EXPORT_BUCKET, objectKey, generated.archive(), "application/zip",
                        Map.of("sha256", generated.archiveSha256(),
                                "export-id", claim.jobId().toString(),
                                "project-generation", Long.toString(claim.projectGeneration())),
                        attemptControl);
                uploaded = true;
                try {
                    adopted = completionService.complete(claim, uploadId, objectKey,
                            generated.archiveSize(), generated.archiveSha256());
                    adoptionState = adopted ? AdoptionState.ADOPTED : AdoptionState.NOT_ADOPTED;
                    if (!adopted) {
                        deleteUnadopted(objectKey);
                    }
                } catch (RuntimeException completionFailure) {
                    adoptionState = resolveAdoption(claim, objectKey,
                            generated.archiveSize(), generated.archiveSha256(), completionFailure);
                    if (adoptionState == AdoptionState.ADOPTED) {
                        adopted = true;
                    } else if (adoptionState == AdoptionState.NOT_ADOPTED) {
                        deleteUnadopted(objectKey);
                        fail(claim, "EXPORT_COMPLETE", false, completionFailure);
                        completionFailureHandled = true;
                    } else {
                        // 二次数据库回读也无法确认时，宁可等待cleanup，不冒险删除可能已经采用的对象。
                        LOGGER.error("项目导出完成提交及权威回读结果均未知，等待持久清理：exportId={}",
                                claim.jobId(), completionFailure);
                    }
                }
            }
        } catch (ProjectExportLimitException exception) {
            fail(claim, "EXPORT_LIMIT", true, exception);
        } catch (BusinessException exception) {
            fail(claim, "PROJECT_NOT_EXPORTABLE", true, exception);
        } catch (ObjectUploadAbortedException exception) {
            if (exception.timedOut()) {
                fail(claim, "EXPORT_LIMIT", true, exception);
            } else {
                LOGGER.info("项目导出上传因租约失权停止，交由持久清理收束：exportId={}", claim.jobId());
            }
        } catch (ObjectStorageException exception) {
            fail(claim, "STORAGE_UPLOAD", false, exception);
        } catch (RuntimeException exception) {
            if (adopted) {
                // 成功CAS和审计已经提交；本地清理失败不能反向删除正式对象或降级任务。
                LOGGER.error("项目导出成功后的本地临时文件清理失败：exportId={}", claim.jobId(), exception);
                return;
            }
            if (adoptionState == AdoptionState.UNKNOWN) {
                // 完成事务结果无法确认时，立即删除可能破坏已提交成功对象；持久清理会按ADOPTED/PENDING收束。
                LOGGER.error("项目导出完成结果未知，保留对象等待持久事实收束：exportId={}", claim.jobId(), exception);
                return;
            }
            if (completionFailureHandled) {
                LOGGER.error("项目导出失败已持久化，但本地临时文件清理再次失败：exportId={}",
                        claim.jobId(), exception);
                return;
            }
            if (uploaded && objectKey != null) deleteUnadopted(objectKey);
            fail(claim, "EXPORT_INTERNAL", false, exception);
        } finally {
            heartbeat.cancel(false);
            restore(previous);
        }
    }

    /**
     * 完成提交抛错后的权威三态回读。
     *
     * <p>只有成功读到完整任务且确认当前对象未被采用时才能立即删除；查无任务或数据库再次
     * 失败都属于UNKNOWN，必须交给上传前登记的durable cleanup判断。</p>
     */
    private AdoptionState resolveAdoption(ProjectExportClaim claim, String objectKey, long objectSize,
                                           String objectSha256, RuntimeException completionFailure) {
        try {
            Optional<ProjectExportJob> current = jobRepository.findByIdentity(
                    claim.tenantId(), claim.projectId(), claim.jobId());
            if (current.isEmpty()) {
                return AdoptionState.UNKNOWN;
            }
            ProjectExportJob job = current.get();
            boolean sameTask = job.id().equals(claim.jobId())
                    && job.tenantId().equals(claim.tenantId())
                    && job.projectId().equals(claim.projectId())
                    && job.projectGeneration() == claim.projectGeneration()
                    && job.requesterAccountId().equals(claim.requesterAccountId());
            boolean sameObject = job.status() == ProjectExportStatus.SUCCEEDED
                    && java.util.Objects.equals(job.objectKey(), objectKey)
                    && java.util.Objects.equals(job.objectSize(), objectSize)
                    && java.util.Objects.equals(job.objectSha256(), objectSha256);
            if (sameTask && sameObject) {
                LOGGER.warn("项目导出完成调用抛错但权威任务已采用对象：exportId={}", claim.jobId(), completionFailure);
                return AdoptionState.ADOPTED;
            }
            return AdoptionState.NOT_ADOPTED;
        } catch (RuntimeException readFailure) {
            completionFailure.addSuppressed(readFailure);
            return AdoptionState.UNKNOWN;
        }
    }

    /** 失败只按仍有效token迁移；租约已丢失时由后继worker接管。 */
    private void fail(ProjectExportClaim claim, String code, boolean permanent, RuntimeException exception) {
        try {
            if (!jobRepository.fail(claim.jobId(), claim.leaseToken(), code, permanent)) {
                LOGGER.info("项目导出失败写入被租约围栏拒绝：exportId={}", claim.jobId());
            }
        } catch (RuntimeException persistenceFailure) {
            exception.addSuppressed(persistenceFailure);
        }
        LOGGER.warn("项目导出尝试失败：exportId={} attempt={} code={}",
                claim.jobId(), claim.attemptCount(), code, exception);
    }

    /** CAS失权或完成事务失败后立即尝试删除；durable cleanup仍保留并最终幂等确认。 */
    private void deleteUnadopted(String objectKey) {
        try {
            objectStorage.delete(EXPORT_BUCKET, objectKey);
        } catch (RuntimeException exception) {
            LOGGER.warn("项目导出未采用对象立即删除失败，将由持久清理任务重试：objectKey={}",
                    objectKey, exception);
        }
    }

    /** 对象键只使用持久身份与本次upload，不含项目名或账号信息。 */
    private static String objectKey(ProjectExportClaim claim, UUID uploadId) {
        return "projects/" + claim.tenantId() + "/" + claim.projectId()
                + "/generations/" + claim.projectGeneration() + "/exports/"
                + claim.jobId() + "/" + uploadId + "/project-export-v1.zip";
    }

    /** worker名进入数据库列与日志标签前做有界校验。 */
    private static String requireWorkerName(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("项目导出worker-id必须为1到128字符");
        }
        return value;
    }

    /** 恢复调用线程原租户范围，防止调度线程复用泄漏。 */
    private static void restore(Optional<TenantScope> previous) {
        previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
    }

    /** 停机停止续租；未完成任务在两分钟后由其他实例接管。 */
    @Override
    public void destroy() {
        heartbeatExecutor.shutdownNow();
    }

    /** 完成调用异常后的对象采用判断，UNKNOWN禁止立即删除。 */
    private enum AdoptionState {
        /** 权威任务已经采用当前对象。 */ ADOPTED,
        /** 权威任务存在且未采用当前对象。 */ NOT_ADOPTED,
        /** 权威回读不可得，提交结果仍不确定。 */ UNKNOWN
    }
}
