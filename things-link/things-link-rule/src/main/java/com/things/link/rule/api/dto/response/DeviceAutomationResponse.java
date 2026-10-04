package com.things.link.rule.api.dto.response;
import com.things.link.rule.domain.DeviceAutomationItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
/** 当前版本或原执行事实白名单，投递状态不表示物理执行。 */
public record DeviceAutomationResponse(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String status,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID activeVersionId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long versionNumber,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String triggerType,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean usesConditionInput,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean hasDeviceAction,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant createdAt) {
    public static DeviceAutomationResponse from(DeviceAutomationItem item) { return new DeviceAutomationResponse(item.id(), item.name(), item.status(), item.activeVersionId(), item.versionNumber(), item.triggerType(), item.usesConditionInput(), item.hasDeviceAction(), item.createdAt()); }
}
