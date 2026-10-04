package com.things.link.task.domain;
import java.time.Instant;
import java.util.UUID;
/** 原执行与目标快照的低敏事实，不关联当前组或任务名称。 */
public record DeviceTaskExecutionItem(UUID id, UUID jobId, String triggerType,
        String executionStatus, String targetStatus, String commandKey, UUID commandId,
        Instant startedAt, Instant acceptedAt, Instant completedAt) { }
