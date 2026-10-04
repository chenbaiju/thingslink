package com.things.link.export.application;

import com.things.link.export.domain.ProjectExportJob;
import com.things.link.export.domain.ProjectExportJobRepository;
import com.things.link.export.domain.ProjectExportDownloadClaim;
import com.things.link.project.application.ProjectExportErrors;
import com.things.link.project.application.ProjectExportSource;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.storage.PrivateObjectStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** ADR0075 项目一致快照导出任务的请求与状态查询用例。 */
@Service
public class ProjectExportService {

    /** 对外下载能力固定五分钟，不允许调用方自定义。 */
    private static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);
    /** ADR0075 固定的私有导出桶。 */
    private static final String EXPORT_BUCKET = "export";

    /** project域提供冻结窗口、OWNER和真实归属许可。 */
    private final ProjectExportSource projectSource;
    /** 导出任务持久化端口。 */
    private final ProjectExportJobRepository jobRepository;
    /** 请求审计与新任务必须在同一事务提交。 */
    private final AuditLogService auditLogService;
    /** 请求入口账号项目组合限流器。 */
    private final ProjectExportRateLimiter rateLimiter;
    /** 只使用external endpoint签发浏览器可访问地址。 */
    private final PrivateObjectStorage objectStorage;

    /**
     * 创建导出用例。
     * @param projectSource 项目导出许可端口
     * @param jobRepository 导出任务仓储
     * @param auditLogService 审计服务
     * @param rateLimiter 导出请求限流器
     * @param objectStorage 私有对象存储端口
     */
    public ProjectExportService(ProjectExportSource projectSource,
                                ProjectExportJobRepository jobRepository,
                                AuditLogService auditLogService,
                                ProjectExportRateLimiter rateLimiter,
                                PrivateObjectStorage objectStorage) {
        this.projectSource = projectSource;
        this.jobRepository = jobRepository;
        this.auditLogService = auditLogService;
        this.rateLimiter = rateLimiter;
        this.objectStorage = objectStorage;
    }

    /**
     * 建立导出任务；同一项目代次已有QUEUED/RUNNING时返回该任务。
     * @param projectId 待导出项目
     * @return 新建或吸收的非终态任务
     */
    @Transactional
    public ProjectExportJob request(UUID projectId) {
        UUID accountId = TenantContext.require().accountId();
        // 高成本导出在任何项目授权或数据库查询之前计数，避免无效枚举消耗数据库。
        if (!rateLimiter.tryAcquire(accountId, projectId)) {
            throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);
        }
        ProjectExportSource.ProjectExportScope scope = projectSource.authorizeRequest(accountId, projectId);
        ProjectExportJob existing = jobRepository.findActive(
                scope.tenantId(), scope.projectId(), scope.generation()).orElse(null);
        if (existing != null) {
            return existing;
        }
        if (scope.storageQuotaConfigured()) {
            throw ProjectExportErrors.storageQuotaUnavailable();
        }
        UUID candidateId = Uuid7.generate();
        boolean created = jobRepository.create(candidateId, scope.tenantId(), scope.projectId(),
                scope.generation(), accountId);
        ProjectExportJob job = jobRepository.findActive(scope.tenantId(), scope.projectId(), scope.generation())
                .orElseThrow(() -> new IllegalStateException("导出任务建立后无法回读"));
        if (created) {
            auditLogService.record(new AuditLogEntry(
                    scope.tenantId(), scope.projectId(), accountId, "project_export", job.id(),
                    "project.export.requested",
                    Map.of("exportId", job.id().toString(),
                            "projectGeneration", scope.generation())));
        }
        return job;
    }

    /**
     * 查询一个项目导出任务；查询时仍复核冻结窗口和当前有效OWNER。
     * @param projectId 项目 ID
     * @param exportId 导出任务 ID
     * @return 状态事实，不包含对象键或未来下载URL
     */
    @Transactional
    public ProjectExportJob get(UUID projectId, UUID exportId) {
        UUID accountId = TenantContext.require().accountId();
        ProjectExportSource.ProjectExportScope scope = projectSource.authorizeRequest(accountId, projectId);
        return jobRepository.findByIdentity(scope.tenantId(), scope.projectId(), exportId)
                .filter(job -> job.projectGeneration() == scope.generation())
                .orElseThrow(ProjectExportErrors::notFound);
    }

    /**
     * 在单一短事务中按project/member到job的固定锁序复核原requester并签发URL。
     *
     * <p>审计在签名之前写入；签名失败会回滚审计。预签名URL是bearer能力，
     * 因此审计和服务日志只记录任务、代次和对象摘要，不记录URL。</p>
     *
     * @param projectId 路径中的项目 ID
     * @param exportId 导出任务 ID
     * @return 仅含URL和截止时刻的短时能力
     */
    @Transactional
    public ProjectExportDownload download(UUID projectId, UUID exportId) {
        UUID accountId = TenantContext.require().accountId();
        ProjectExportSource.ProjectDownloadScope scope = projectSource.authorizeDownload(accountId, projectId);
        // 项目锁后才按当前归属、代次和原requester锁job，避免信任JWT tenant或任务历史字段授权。
        ProjectExportDownloadClaim claim = jobRepository.lockDownloadCandidate(
                        scope.tenantId(), scope.projectId(), exportId, scope.generation(), accountId)
                .orElseThrow(ProjectExportErrors::notFound);
        ProjectExportJob job = claim.job();
        auditLogService.record(new AuditLogEntry(
                job.tenantId(), job.projectId(), accountId, "project_export", job.id(),
                "project.export.downloaded",
                Map.of("exportId", job.id().toString(),
                        "projectGeneration", job.projectGeneration(),
                        "objectSize", job.objectSize(),
                        "objectSha256", job.objectSha256())));
        // MinIO SDK直接用external endpoint参与签名；业务层不能在签名后改写host。
        return new ProjectExportDownload(
                objectStorage.presignGet(EXPORT_BUCKET, job.objectKey(), DOWNLOAD_TTL),
                claim.downloadExpiresAt());
    }
}
