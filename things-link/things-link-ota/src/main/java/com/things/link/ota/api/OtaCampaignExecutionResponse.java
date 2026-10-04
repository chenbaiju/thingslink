package com.things.link.ota.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.things.link.ota.domain.OtaCampaignRuntime;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** 运行状态只表示持久准入，不代表Broker投递、设备安装或下载授权。
 * @param id 活动身份
 * @param status 当前活动状态
 * @param stateVersion 十进制状态修订
 * @param startedAt 首次启动时间，可空
 * @param currentBatch 当前批次，可空
 * @param pauseKind 暂停类型，可空
 * @param pauseReason 暂停原因，可空
 * @param pausedAt 暂停时间，可空
 * @param pauseActorId 真实管理操作者，系统暂停为空
 * @param pauseJobId 触发暂停作业，可空
 * @param pendingCount 待准入作业数
 * @param dispatchedCount 已持久通知意图数
 * @param skippedCount 跳过作业数
 * @param batchProgress 冻结批序和完成统计
 * @param runtimeCancellation 运行取消请求与安全责任，未请求时为空
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "OtaCampaignExecutionResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"id", "status", "stateVersion", "startedAt", "currentBatch", "pauseKind", "pauseReason",
                "pausedAt", "pauseActorId", "pauseJobId", "pendingCount", "dispatchedCount", "skippedCount", "runtimeCancellation", "batchProgress"})
public record OtaCampaignExecutionResponse(
        @Schema(format = "uuid") UUID id,
        @Schema(allowableValues = {"DRAFT", "SCHEDULED", "RUNNING", "PAUSED", "CANCELLING", "CANCELLED", "COMPLETED"}) String status,
        @Schema(pattern = "0|[1-9][0-9]{0,18}") String stateVersion,
        @Schema(types = {"string", "null"}, format = "date-time") Instant startedAt,
        @Schema(types = {"integer", "null"}, minimum = "1", maximum = "1000") Integer currentBatch,
        @Schema(types = {"string", "null"}) String pauseKind,
        @Schema(types = {"string", "null"}, minLength = 1, maxLength = 256) String pauseReason,
        @Schema(types = {"string", "null"}, format = "date-time") Instant pausedAt,
        @Schema(types = {"string", "null"}, format = "uuid") UUID pauseActorId,
        @Schema(types = {"string", "null"}, format = "uuid") UUID pauseJobId,
        @Schema(minimum = "0", maximum = "1000") long pendingCount,
        @Schema(minimum = "0", maximum = "1000") long dispatchedCount,
        @Schema(minimum = "0", maximum = "1000") long skippedCount,
        OtaCampaignRuntimeCancellationResponse runtimeCancellation, OtaCampaignBatchProgressResponse batchProgress) {
    /** 明确白名单投影，不包含原计划、对象地址或租约令牌。 */
    public static OtaCampaignExecutionResponse from(OtaCampaignRuntime value) {
        var campaign = value.campaign();
        return new OtaCampaignExecutionResponse(campaign.id(), campaign.status(), Long.toString(campaign.stateVersion()),
                value.startedAt(), value.currentBatch(), value.pauseKind(), value.pauseReason(), value.pausedAt(),
                value.pauseActorId(), value.pauseJobId(), value.pendingCount(), value.dispatchedCount(), value.skippedCount(),
                OtaCampaignRuntimeCancellationResponse.from(value.runtimeCancellation()),
                OtaCampaignBatchProgressResponse.from(value.batchProgress()));
    }
}
