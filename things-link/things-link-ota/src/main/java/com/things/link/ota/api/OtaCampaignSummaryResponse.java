package com.things.link.ota.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.things.link.ota.domain.OtaCampaignRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** 管理端活动摘要白名单，只暴露列表与批次计数，不返回规范计划、清单或发布身份。
 * @param id 活动身份
 * @param firmwareId 固件身份
 * @param status 活动状态
 * @param stateVersion 十进制CAS修订
 * @param targetCount 冻结目标数量，草稿为零
 * @param batchCount 冻结批次数量，草稿为零
 * @param createdAt 创建时间
 * @param updatedAt 最近转移时间
 * @param scheduledAt 首次排程时间，可空
 * @param cancelledAt 取消完成时间，可空
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "OtaCampaignSummaryResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record OtaCampaignSummaryResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID firmwareId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"DRAFT", "SCHEDULED", "RUNNING", "PAUSED", "CANCELLING", "CANCELLED", "COMPLETED"}) String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "0|[1-9][0-9]{0,18}") String stateVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "1000") int targetCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "1000") int batchCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "date-time") Instant scheduledAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "date-time") Instant cancelledAt) {
    /** 白名单投影；修订转十进制字符串避免前端整数精度损失。
     * @param value 仓储摘要投影
     * @return HTTP摘要响应
     */
    public static OtaCampaignSummaryResponse from(OtaCampaignRepository.Summary value) {
        return new OtaCampaignSummaryResponse(value.id(), value.firmwareId(), value.status(),
                Long.toString(value.stateVersion()), value.targetCount(), value.batchCount(), value.createdAt(),
                value.updatedAt(), value.scheduledAt(), value.cancelledAt());
    }
}
