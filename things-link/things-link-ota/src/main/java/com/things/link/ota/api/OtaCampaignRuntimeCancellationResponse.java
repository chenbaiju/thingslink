package com.things.link.ota.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.things.link.ota.domain.OtaCampaignRuntimeCancellation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** 运行取消请求与当前未决责任投影，不把请求时间当作安全完成时间。
 * @param requestedRevision 请求前的十进制状态修订
 * @param requestedFromStatus 发起取消时的运行状态
 * @param requestedAt 首次取消请求时间
 * @param requestedBy 真实管理操作者
 * @param reason 首次取消原因
 * @param cancelledPendingCount 取消的未派发作业数
 * @param unresolvedCount 仍待安全收束的已派发责任数
 * @param completedAt 安全完成时间，未完成为null
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "OtaCampaignRuntimeCancellationResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"requestedRevision", "requestedFromStatus", "requestedAt", "requestedBy", "reason",
                "cancelledPendingCount", "unresolvedCount", "completedAt"})
public record OtaCampaignRuntimeCancellationResponse(
        @Schema(pattern = "0|[1-9][0-9]{0,18}") String requestedRevision,
        @Schema(allowableValues = {"RUNNING", "PAUSED"}) String requestedFromStatus,
        @Schema(format = "date-time") Instant requestedAt,
        @Schema(format = "uuid") UUID requestedBy,
        @Schema(minLength = 1, maxLength = 256) String reason,
        @Schema(minimum = "0", maximum = "1000") long cancelledPendingCount,
        @Schema(minimum = "0", maximum = "1000") long unresolvedCount,
        @Schema(types = {"string", "null"}, format = "date-time") Instant completedAt) {
    /** 严格白名单，旧活动不存在运行取消请求时保持null。 */
    public static OtaCampaignRuntimeCancellationResponse from(OtaCampaignRuntimeCancellation value) {
        if (value == null) return null;
        return new OtaCampaignRuntimeCancellationResponse(Long.toString(value.requestedRevision()),
                value.requestedFromStatus(), value.requestedAt(), value.requestedBy(), value.reason(),
                value.cancelledPendingCount(), value.unresolvedCount(), value.completedAt());
    }
}
