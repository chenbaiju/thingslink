package com.things.link.export.application;

import com.things.link.export.domain.ProjectExportCleanupClaim;
import com.things.link.export.domain.ProjectExportExpiryClaim;
import com.things.link.export.domain.ProjectExportJobRepository;
import com.things.link.support.storage.PrivateObjectStorage;
import com.things.link.support.tenant.DataPlaneDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 对未被成功任务采用的每次upload执行持久、可重试删除。 */
@Component
@DataPlaneDatabase
public class ProjectExportCleanupWorker {

    /** 清理日志只记录对象键和稳定错误，不记录存储凭据。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectExportCleanupWorker.class);
    /** 固定私有桶。 */
    private static final String EXPORT_BUCKET = "export";
    /** 清理事实仓储。 */
    private final ProjectExportJobRepository jobRepository;
    /** 私有对象存储。 */
    private final PrivateObjectStorage objectStorage;

    /**
     * 创建孤儿对象清理worker。
     * @param jobRepository 清理事实仓储
     * @param objectStorage 私有对象存储
     */
    public ProjectExportCleanupWorker(ProjectExportJobRepository jobRepository,
                                      PrivateObjectStorage objectStorage) {
        this.jobRepository = jobRepository;
        this.objectStorage = objectStorage;
    }

    /** 单轮领取一条，失败保留两分钟退避。 */
    @Scheduled(fixedDelayString = "${things-link.export.cleanup-fixed-delay-millis:1000}",
            initialDelayString = "${things-link.export.cleanup-initial-delay-millis:0}",
            scheduler = "exportLifecycleScheduler")
    public void cleanupReadyObjects() {
        try {
            jobRepository.claimCleanup().ifPresent(this::cleanup);
        } catch (RuntimeException exception) {
            LOGGER.error("项目导出孤儿对象领取失败", exception);
        }
    }

    /**
     * 单轮领取一条已到期成功对象。
     *
     * <p>领取SQL先锁导出任务行，与下载签发共用锁序；任务到期后新签名
     * 会在持久边界被拒绝，已领取对象则由token围栏收束。</p>
     */
    @Scheduled(fixedDelayString = "${things-link.export.expiry-fixed-delay-millis:1000}",
            initialDelayString = "${things-link.export.expiry-initial-delay-millis:0}",
            scheduler = "exportLifecycleScheduler")
    public void cleanupExpiredObjects() {
        try {
            jobRepository.claimExpired().ifPresent(this::cleanupExpired);
        } catch (RuntimeException exception) {
            LOGGER.error("项目导出到期对象领取失败", exception);
        }
    }

    /**
     * 删除一条已领取对象；MinIO删除不存在对象也是幂等成功。
     * @param claim 清理领取身份
     */
    public void cleanup(ProjectExportCleanupClaim claim) {
        try {
            objectStorage.delete(EXPORT_BUCKET, claim.objectKey());
            if (!jobRepository.completeCleanup(claim.cleanupId(), claim.leaseToken())) {
                LOGGER.info("项目导出对象清理完成写被租约围栏拒绝：cleanupId={}", claim.cleanupId());
            }
        } catch (RuntimeException exception) {
            try {
                jobRepository.failCleanup(claim.cleanupId(), claim.leaseToken(), "STORAGE_DELETE");
            } catch (RuntimeException persistenceFailure) {
                exception.addSuppressed(persistenceFailure);
            }
            LOGGER.warn("项目导出孤儿对象删除失败：cleanupId={}", claim.cleanupId(), exception);
        }
    }

    /**
     * 删除一条已到期采用对象，真实删除后再原子收束cleanup和任务。
     * @param claim 到期对象的当前领取身份
     */
    public void cleanupExpired(ProjectExportExpiryClaim claim) {
        try {
            objectStorage.delete(EXPORT_BUCKET, claim.objectKey());
            if (!jobRepository.completeExpired(claim.exportId(), claim.cleanupId(), claim.leaseToken())) {
                LOGGER.info("项目导出到期收束被租约围栏拒绝：exportId={} cleanupId={}",
                        claim.exportId(), claim.cleanupId());
            }
        } catch (RuntimeException exception) {
            try {
                jobRepository.failExpired(claim.cleanupId(), claim.leaseToken(), "STORAGE_DELETE");
            } catch (RuntimeException persistenceFailure) {
                exception.addSuppressed(persistenceFailure);
            }
            LOGGER.warn("项目导出到期对象删除失败：exportId={} cleanupId={}",
                    claim.exportId(), claim.cleanupId(), exception);
        }
    }
}
