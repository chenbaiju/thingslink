package com.things.link.export.application;

import com.things.link.export.domain.ProjectExportClaim;
import com.things.link.export.domain.ProjectExportJobRepository;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** 将成功对象采用与成功审计置于同一数据库事务。 */
@Service
public class ProjectExportCompletionService {

    /** 导出任务仓储。 */
    private final ProjectExportJobRepository jobRepository;
    /** 不可篡改审计服务。 */
    private final AuditLogService auditLogService;

    /**
     * 创建完成服务。
     * @param jobRepository 任务仓储
     * @param auditLogService 审计服务
     */
    public ProjectExportCompletionService(ProjectExportJobRepository jobRepository,
                                          AuditLogService auditLogService) {
        this.jobRepository = jobRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * 按当前token采用对象；CAS失败不写成功审计。
     * @param claim 当前领取
     * @param uploadId 上传身份
     * @param objectKey 私有对象键
     * @param size ZIP字节数
     * @param sha256 ZIP摘要
     * @return 当前token完成时为true
     */
    @Transactional
    public boolean complete(ProjectExportClaim claim, UUID uploadId, String objectKey,
                            long size, String sha256) {
        if (!jobRepository.complete(claim.jobId(), claim.leaseToken(), uploadId,
                objectKey, size, sha256)) {
            return false;
        }
        auditLogService.record(new AuditLogEntry(
                claim.tenantId(), claim.projectId(), claim.requesterAccountId(),
                "project_export", claim.jobId(), "project.export.succeeded",
                Map.of("exportId", claim.jobId().toString(),
                        "projectGeneration", claim.projectGeneration(),
                        "objectSize", size,
                        "objectSha256", sha256)));
        return true;
    }
}
