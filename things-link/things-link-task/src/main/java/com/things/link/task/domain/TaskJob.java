package com.things.link.task.domain;

import java.time.Instant;
import java.util.UUID;

/** 项目内可重复执行的批量设备任务定义。 */
public record TaskJob(UUID id, UUID tenantId, UUID projectId, String name, String description, Status status,
                      long version, ScheduleType scheduleType, Instant runAt, String cronExpression, String timezone,
                      Instant nextRunAt, TargetType targetType, UUID targetGroupId, String commandKey,
                      String inputJson, UUID createdBy, Instant createdAt, Instant updatedAt) {

    /** 任务启停状态；删除通过独立 deleted_at 保留执行历史。 */
    public enum Status { ACTIVE, PAUSED }
    /** 调度类型；一次性任务在完成后不再计算 nextRunAt。 */
    public enum ScheduleType { ONCE, CRON }
    /** 冻结目标的来源，禁止开放任意查询 DSL。 */
    public enum TargetType { ALL_DEVICES, DEVICE_GROUP }
}
