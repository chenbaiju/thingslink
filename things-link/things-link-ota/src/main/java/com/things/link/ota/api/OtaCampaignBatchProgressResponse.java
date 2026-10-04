package com.things.link.ota.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.things.link.ota.domain.OtaCampaignBatchProgress;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** 批次放行与活动固定统计白名单，不返回租约或设备秘密。
 * @param currentBatchStatus 当前批状态，尚无批时为空
 * @param requireManualBatchApproval 冻结人工审批策略
 * @param awaitingManualApproval 当前是否等待人工放行
 * @param nextBatchNumber 下一冻结批号，可空
 * @param completedAt 完成时间，可空
 * @param outcome 完成结论，可空；FAILED仅保留枚举
 * @param targetCount 冻结目标总数
 * @param succeededCount 成功数
 * @param rolledBackCount 回退数
 * @param skippedCount 跳过数
 * @param timedOutCount 明确失败且预算耗尽的作业数
 * @param cancelledCount 取消数
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "OtaCampaignBatchProgressResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"currentBatchStatus", "requireManualBatchApproval", "awaitingManualApproval",
                "nextBatchNumber", "completedAt", "outcome", "targetCount", "succeededCount", "rolledBackCount",
                "skippedCount", "timedOutCount", "cancelledCount"})
public record OtaCampaignBatchProgressResponse(
        @Schema(types = {"string", "null"}) String currentBatchStatus,
        boolean requireManualBatchApproval, boolean awaitingManualApproval,
        @Schema(types = {"integer", "null"}, minimum = "1", maximum = "1000") Integer nextBatchNumber,
        @Schema(types = {"string", "null"}, format = "date-time") Instant completedAt,
        @Schema(types = {"string", "null"}) String outcome,
        @Schema(minimum = "0", maximum = "1000") long targetCount,
        @Schema(minimum = "0", maximum = "1000") long succeededCount,
        @Schema(minimum = "0", maximum = "1000") long rolledBackCount,
        @Schema(minimum = "0", maximum = "1000") long skippedCount,
        @Schema(minimum = "0", maximum = "1000") long timedOutCount,
        @Schema(minimum = "0", maximum = "1000") long cancelledCount) {
    /** 域投影必须存在，不能以缺失统计伪装零目标。 */
    public static OtaCampaignBatchProgressResponse from(OtaCampaignBatchProgress value) {
        if (value == null) throw new IllegalStateException("OTA批次统计投影缺失");
        return new OtaCampaignBatchProgressResponse(value.currentBatchStatus(), value.requireManualBatchApproval(),
                value.awaitingManualApproval(), value.nextBatchNumber(), value.completedAt(), value.outcome(),
                value.targetCount(), value.succeededCount(), value.rolledBackCount(), value.skippedCount(), value.timedOutCount(), value.cancelledCount());
    }
}
