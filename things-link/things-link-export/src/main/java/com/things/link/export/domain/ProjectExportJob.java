package com.things.link.export.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 项目导出任务持久事实。
 *
 * @param id 任务 ID
 * @param tenantId 项目owner租户
 * @param projectId 项目 ID
 * @param projectGeneration 生命周期代次
 * @param requesterAccountId 请求OWNER账号
 * @param status 任务状态
 * @param attemptCount 已开始尝试数
 * @param nextAttemptAt 下次领取时刻
 * @param snapshotAt 成功或当前尝试快照时刻
 * @param objectKey 成功对象键
 * @param objectSize ZIP字节数
 * @param objectSha256 ZIP摘要
 * @param failureCode 最近失败分类
 * @param requestedAt 请求时刻
 * @param startedAt 首次开始时刻
 * @param succeededAt 成功时刻
 * @param expiresAt 对象到期时刻
 */
public record ProjectExportJob(UUID id, UUID tenantId, UUID projectId, long projectGeneration,
                               UUID requesterAccountId, ProjectExportStatus status, int attemptCount,
                               Instant nextAttemptAt, Instant snapshotAt, String objectKey,
                               Long objectSize, String objectSha256, String failureCode,
                               Instant requestedAt, Instant startedAt, Instant succeededAt,
                               Instant expiresAt) {
}
