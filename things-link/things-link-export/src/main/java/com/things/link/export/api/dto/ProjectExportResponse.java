package com.things.link.export.api.dto;

import com.things.link.export.domain.ProjectExportJob;

import java.time.Instant;
import java.util.UUID;

/**
 * 项目导出异步任务响应；5g1不暴露对象键或下载能力。
 * @param id 导出任务 ID
 * @param projectId 项目 ID
 * @param projectGeneration 生命周期代次
 * @param status 任务状态
 * @param attemptCount 已开始尝试数
 * @param failureCode 最近稳定失败分类
 * @param requestedAt 请求时刻
 * @param startedAt 首次开始时刻
 * @param snapshotAt 成功快照时刻
 * @param succeededAt 成功时刻
 * @param expiresAt 成功对象到期时刻
 * @param objectSize 成功ZIP字节数
 * @param objectSha256 成功ZIP摘要
 */
public record ProjectExportResponse(UUID id, UUID projectId, long projectGeneration, String status,
                                    int attemptCount, String failureCode, Instant requestedAt,
                                    Instant startedAt, Instant snapshotAt, Instant succeededAt,
                                    Instant expiresAt, Long objectSize, String objectSha256) {

    /** @param job 持久任务 @return 不含存储实现细节的HTTP响应 */
    public static ProjectExportResponse from(ProjectExportJob job) {
        return new ProjectExportResponse(
                job.id(), job.projectId(), job.projectGeneration(), job.status().name(), job.attemptCount(),
                job.failureCode(), job.requestedAt(), job.startedAt(), job.snapshotAt(), job.succeededAt(),
                job.expiresAt(), job.objectSize(), job.objectSha256());
    }
}
