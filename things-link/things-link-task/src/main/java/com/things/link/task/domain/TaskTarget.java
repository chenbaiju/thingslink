package com.things.link.task.domain;

import java.time.Instant;
import java.util.UUID;

/** 执行时冻结的一台设备目标及其命令结果引用。 */
public record TaskTarget(UUID executionId, UUID tenantId, UUID projectId, UUID deviceId, Status status,
                         UUID commandId, String failureSummary, Instant acceptedAt, Instant completedAt) {

    /** 目标从快照生成到命令终态同步的状态。 */
    public enum Status { PENDING, ACCEPTED, SUCCEEDED, FAILED, TIMED_OUT, SKIPPED }
}
