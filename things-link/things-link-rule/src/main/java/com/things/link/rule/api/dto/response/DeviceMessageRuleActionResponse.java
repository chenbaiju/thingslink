package com.things.link.rule.api.dto.response;
import com.things.link.rule.domain.DeviceMessageRuleActionItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
/** 候选、执行尝试和动作记录分别投影，不推测未知历史。 */
public record DeviceMessageRuleActionResponse(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID ruleId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID ruleVersionId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID messageId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String operationType,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String status,
        UUID commandId,
        String failureCode,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant createdAt,
        Instant completedAt) {
    public static DeviceMessageRuleActionResponse from(DeviceMessageRuleActionItem item) { return new DeviceMessageRuleActionResponse(item.id(), item.ruleId(), item.ruleVersionId(), item.messageId(), item.operationType(), item.status(), item.commandId(), item.failureCode(), item.createdAt(), item.completedAt()); }
}
