package com.things.link.task.api.dto.response;
import com.things.link.task.domain.DeviceTaskJobItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
/** 设备任务只读摘要，保持定义和历史响应独立。 */
public record DeviceTaskJobResponse(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String status,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long version,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String targetType,
        UUID targetGroupId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String commandKey,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant createdAt) {
    public static DeviceTaskJobResponse from(DeviceTaskJobItem item) { return new DeviceTaskJobResponse(item.id(), item.name(), item.status(), item.version(), item.targetType(), item.targetGroupId(), item.commandKey(), item.createdAt()); }
}
