package com.things.link.ota.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.things.link.ota.domain.OtaCampaignRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** 冻结批次读投影：批次行事实加真实作业计数，不返回设备名单、租约或计划字节。
 * @param batchNumber 从1开始的稳定批次号
 * @param status 持久批次状态
 * @param targetCount 冻结目标数量
 * @param succeededCount 真实成功作业数
 * @param rolledBackCount 真实安全回退作业数
 * @param skippedCount 未准入跳过作业数
 * @param timedOutCount 明确失败且预算耗尽的作业数
 * @param cancelledCount 取消作业数
 * @param completedAt 终态批次转移时间，尚未终结为空
 * @param outcome 终态批次结论，尚未终结为空
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "OtaCampaignBatchResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record OtaCampaignBatchResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "1000") int batchNumber,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"PENDING", "RUNNING", "PAUSED", "DRAINING", "SUCCEEDED", "FAILED", "CANCELLING", "CANCELLED"}) String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "1000") int targetCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "1000") long succeededCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "1000") long rolledBackCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "1000") long skippedCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "1000") long timedOutCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "1000") long cancelledCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "date-time") Instant completedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}) String outcome) {
    /** 只做字段白名单复制，计数口径与运行投影保持一致。
     * @param value 仓储批次投影
     * @return HTTP批次响应
     */
    public static OtaCampaignBatchResponse from(OtaCampaignRepository.Batch value) {
        return new OtaCampaignBatchResponse(value.batchNumber(), value.status(), value.targetCount(),
                value.succeededCount(), value.rolledBackCount(), value.skippedCount(), value.timedOutCount(), value.cancelledCount(),
                value.completedAt(), value.outcome());
    }
}
