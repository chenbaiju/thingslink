package com.things.link.rule.api.dto.response;
import com.things.link.rule.domain.DeviceMessageRuleExecutionItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
/** 候选、执行尝试和动作记录分别投影，不推测未知历史。 */
public record DeviceMessageRuleExecutionResponse(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID ruleId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID ruleVersionId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID messageId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) int attempt,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String status,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String resultCode,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long durationMillis,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) int inputBytes,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) int outputBytes,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant createdAt) {
    public static DeviceMessageRuleExecutionResponse from(DeviceMessageRuleExecutionItem item) { return new DeviceMessageRuleExecutionResponse(item.id(), item.ruleId(), item.ruleVersionId(), item.messageId(), item.attempt(), item.status(), item.resultCode(), item.durationMillis(), item.inputBytes(), item.outputBytes(), item.createdAt()); }
}
