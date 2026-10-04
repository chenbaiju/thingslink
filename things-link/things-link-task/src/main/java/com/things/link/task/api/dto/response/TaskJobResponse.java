package com.things.link.task.api.dto.response;

import com.things.link.task.domain.TaskJob;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/** 控制台任务定义只读契约。 */
@Schema(description = "批量设备任务")
public record TaskJobResponse(UUID id, String name, String description, TaskJob.Status status, long version,
                              TaskJob.ScheduleType scheduleType, Instant runAt, String cronExpression, String timezone,
                              Instant nextRunAt, TaskJob.TargetType targetType, UUID targetGroupId, String commandKey,
                              JsonNode input, Instant createdAt, Instant updatedAt) {
    /** 将领域任务映射为 HTTP 输出，JSON 解析失败代表已损坏事实而非客户端输入错误。 */
    public static TaskJobResponse from(TaskJob value, ObjectMapper mapper) { return new TaskJobResponse(value.id(), value.name(), value.description(), value.status(), value.version(), value.scheduleType(), value.runAt(), value.cronExpression(), value.timezone(), value.nextRunAt(), value.targetType(), value.targetGroupId(), value.commandKey(), mapper.readTree(value.inputJson()), value.createdAt(), value.updatedAt()); }
}
