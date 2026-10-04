package com.things.link.task.api.dto.request;

import com.things.link.task.domain.TaskJob;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** 任务创建或更新 HTTP 写入契约。 */
@Schema(description = "批量设备任务保存请求")
public record SaveTaskJobRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 512) String description,
        @NotNull TaskJob.ScheduleType scheduleType,
        Instant runAt,
        @Size(max = 128) String cronExpression,
        @Size(max = 64) String timezone,
        @NotNull TaskJob.TargetType targetType,
        UUID targetGroupId,
        @NotBlank @Size(max = 64) String commandKey,
        @NotNull JsonNode input,
        @NotNull Boolean enabled,
        Long expectedVersion) {
}
