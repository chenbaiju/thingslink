package com.things.link.task.application;

import com.things.link.task.domain.TaskJob;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** 任务保存用例的内部命令，隔离 HTTP DTO。 */
public record TaskJobCommand(String name, String description, TaskJob.ScheduleType scheduleType, Instant runAt,
                             String cronExpression, String timezone, TaskJob.TargetType targetType, UUID targetGroupId,
                             String commandKey, JsonNode input, boolean enabled, Long expectedVersion) {
}
