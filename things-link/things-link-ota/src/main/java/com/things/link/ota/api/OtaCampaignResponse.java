package com.things.link.ota.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.things.link.ota.application.OtaCampaignService;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 管理端完整计划及当前冻结目标，不暴露私桶、URL和签名配置。
 * @param id 活动身份
 * @param firmwareId 固件身份
 * @param status 活动状态
 * @param stateVersion 十进制CAS修订
 * @param planSha256 规范计划摘要
 * @param plan 可审阅的完整计划
 * @param targetCount 已冻结目标数量
 * @param batchCount 已冻结批次数
 * @param jobs 已冻结作业及稳定批次，草稿为空
 * @param createdAt 创建时间
 * @param updatedAt 最近转移时间
 * @param scheduledAt 首次排程时间，可空
 * @param cancelledAt 取消完成时间，可空
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "OtaCampaignResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record OtaCampaignResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID firmwareId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"DRAFT", "SCHEDULED", "RUNNING", "PAUSED", "CANCELLING", "CANCELLED", "COMPLETED"}) String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "0|[1-9][0-9]{0,18}") String stateVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[0-9a-f]{64}") String planSha256,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OtaCampaignPlanBody plan,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "1000") int targetCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "1000") int batchCount,
        @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED), maxItems = 1000,
                schema = @Schema(implementation = Job.class)) List<Job> jobs,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "date-time") Instant scheduledAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "date-time") Instant cancelledAt) {
    /** 防止外部列表在序列化前被修改。 */
    public OtaCampaignResponse { jobs = List.copyOf(jobs); }
    /** 白名单投影，凭据代际也转成十进制字符串。 */
    public static OtaCampaignResponse from(OtaCampaignService.View view) {
        var c = view.campaign();
        return new OtaCampaignResponse(c.id(), c.firmwareId(), c.status(), Long.toString(c.stateVersion()),
                c.planSha256(), OtaCampaignPlanBody.from(view.plan()), c.targetCount(), c.batchCount(),
                view.jobs().stream().map(j -> new Job(j.id(), j.batchNumber(), j.deviceId(), j.deviceTypeId(),
                        j.thingModelVersionId(), Long.toString(j.credentialVersion()), j.status())).toList(),
                c.createdAt(), c.updatedAt(), c.scheduledAt(), c.cancelledAt());
    }
    /** 已预占或取消的真实作业，不是派发授权。
     * @param id 作业身份
     * @param batchNumber 从1开始的稳定批次
     * @param deviceId 目标设备
     * @param deviceTypeId 当时设备类型
     * @param thingModelVersionId 当时模型，可空
     * @param credentialVersion 当时凭据代际，不是当前授权
     * @param status 当前持久作业状态
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "OtaCampaignJobResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Job(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "1000") int batchNumber,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceTypeId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "uuid") UUID thingModelVersionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "0|[1-9][0-9]{0,18}") String credentialVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"PENDING", "CANCELLED", "SKIPPED_INELIGIBLE", "DISPATCHED", "RETRY_WAIT", "DOWNLOADING", "VERIFYING", "INSTALLING", "REBOOTING", "HEALTH_CHECKING", "CONFIRMING", "SUCCEEDED", "ROLLBACK_PENDING", "ROLLING_BACK", "ROLLED_BACK", "RECOVERY_REQUIRED", "FAILED", "TIMED_OUT"}) String status) { }
}
