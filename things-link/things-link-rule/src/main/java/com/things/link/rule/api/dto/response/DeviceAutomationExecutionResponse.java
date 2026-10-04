package com.things.link.rule.api.dto.response;
import com.things.link.rule.domain.DeviceAutomationExecutionItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
/** 当前版本或原执行事实白名单，投递状态不表示物理执行。 */
public record DeviceAutomationExecutionResponse(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID automationId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID automationVersionId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String triggerType,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String status,
        String reasonCode,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) int attemptCount,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long deviceActionRecordCount,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant occurredAt,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant acceptedAt,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant createdAt,
        Instant completedAt) {
    public static DeviceAutomationExecutionResponse from(DeviceAutomationExecutionItem item) { return new DeviceAutomationExecutionResponse(item.id(), item.automationId(), item.automationVersionId(), item.triggerType(), item.status(), item.reasonCode(), item.attemptCount(), item.deviceActionRecordCount(), item.occurredAt(), item.acceptedAt(), item.createdAt(), item.completedAt()); }
}
