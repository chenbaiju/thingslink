package com.things.link.rule.api.dto.response;
import com.things.link.rule.domain.DeviceMessageRuleItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
/** 候选、执行尝试和动作记录分别投影，不推测未知历史。 */
public record DeviceMessageRuleResponse(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String status,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID activeVersionId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long versionNumber,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String relationScope,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean hasDeviceAction,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant createdAt) {
    public static DeviceMessageRuleResponse from(DeviceMessageRuleItem item) { return new DeviceMessageRuleResponse(item.id(), item.name(), item.status(), item.activeVersionId(), item.versionNumber(), item.relationScope(), item.hasDeviceAction(), item.createdAt()); }
}
