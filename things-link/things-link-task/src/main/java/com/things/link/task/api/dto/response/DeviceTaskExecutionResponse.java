package com.things.link.task.api.dto.response;
import com.things.link.task.domain.DeviceTaskExecutionItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
/** 设备任务只读摘要，保持定义和历史响应独立。 */
public record DeviceTaskExecutionResponse(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID jobId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String triggerType,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String executionStatus,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String targetStatus,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String commandKey,
        UUID commandId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant startedAt,
        Instant acceptedAt,
        Instant completedAt) {
    public static DeviceTaskExecutionResponse from(DeviceTaskExecutionItem item) { return new DeviceTaskExecutionResponse(item.id(), item.jobId(), item.triggerType(), item.executionStatus(), item.targetStatus(), item.commandKey(), item.commandId(), item.startedAt(), item.acceptedAt(), item.completedAt()); }
}
