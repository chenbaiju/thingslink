package com.things.link.rule.api.dto.response;
import com.things.link.rule.domain.DeviceSceneExecutionItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
/** 当前版本或原执行事实白名单，投递状态不表示物理执行。 */
public record DeviceSceneExecutionResponse(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID sceneId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID sceneVersionId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String status,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long deviceActionRecordCount,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant occurredAt,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant createdAt,
        Instant completedAt) {
    public static DeviceSceneExecutionResponse from(DeviceSceneExecutionItem item) { return new DeviceSceneExecutionResponse(item.id(), item.sceneId(), item.sceneVersionId(), item.status(), item.deviceActionRecordCount(), item.occurredAt(), item.createdAt(), item.completedAt()); }
}
