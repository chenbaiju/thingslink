package com.things.link.task.api.dto.response;

import com.things.link.task.domain.TaskExecution;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** 控制台执行日志只读契约。 */
@Schema(description = "批量任务执行日志")
public record TaskExecutionResponse(UUID id, UUID jobId, TaskExecution.TriggerType triggerType, TaskExecution.Status status,
                                    Instant scheduledFireAt, int totalTargets, int acceptedTargets, int succeededTargets,
                                    int failedTargets, int skippedTargets, Instant startedAt, Instant finishedAt,
                                    String failureSummary) {
    /** 领域执行到 HTTP 输出的纯映射。 */
    public static TaskExecutionResponse from(TaskExecution value) { return new TaskExecutionResponse(value.id(), value.jobId(), value.triggerType(), value.status(), value.scheduledFireAt(), value.totalTargets(), value.acceptedTargets(), value.succeededTargets(), value.failedTargets(), value.skippedTargets(), value.startedAt(), value.finishedAt(), value.failureSummary()); }
}
