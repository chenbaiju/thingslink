package com.things.link.task.domain;
import java.time.Instant;
import java.util.UUID;
/** 当前任务定义摘要，不能作为过去执行的版本快照。 */
public record DeviceTaskJobItem(UUID id, String name, String status, long version,
        String targetType, UUID targetGroupId, String commandKey, Instant createdAt) { }
