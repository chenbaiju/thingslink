package com.things.link.ota.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.things.link.ota.domain.OtaCampaignRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** 管理端设备作业摘要白名单，只暴露运行状态机事实，不含租户、租约令牌、规范字节或资格报告。
 *
 * <p>{@code status}闭集与{@code ota_device_job_runtime_values_ck}（V20260913_0960）逐字一致：
 * 新增作业状态必须先改数据库约束，再同步该枚举，避免契约比数据库事实更窄。
 * @param id 作业身份
 * @param batchNumber 从1开始的稳定批次
 * @param deviceId 目标设备
 * @param deviceTypeId 冻结时的设备类型
 * @param thingModelVersionId 冻结时绑定的物模型版本，可空
 * @param credentialVersion 冻结时凭据代际，不是当前授权
 * @param status 当前持久作业状态
 * @param attemptNo 当前尝试代次，首次派发为一
 * @param stateVersion 独立业务修订，十进制字符串
 * @param failureCode 稳定失败或跳过原因，可空
 * @param firstDispatchedAt 首次成功派发时间，重试不刷新，可空
 * @param dispatchedAt 当前尝试派发时间，可空
 * @param deadlineAt 当前阶段期限，可空
 * @param nextAttemptAt 下次可领取时间，可空
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "OtaDeviceJobSummaryResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record OtaDeviceJobSummaryResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "1000") int batchNumber,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceTypeId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "uuid") UUID thingModelVersionId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "0|[1-9][0-9]{0,18}") String credentialVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"PENDING", "CANCELLED",
                "SKIPPED_INELIGIBLE", "DISPATCHED", "RETRY_WAIT", "DOWNLOADING", "VERIFYING", "INSTALLING",
                "REBOOTING", "HEALTH_CHECKING", "CONFIRMING", "SUCCEEDED", "ROLLBACK_PENDING", "ROLLING_BACK",
                "ROLLED_BACK", "RECOVERY_REQUIRED", "FAILED", "TIMED_OUT"}) String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") int attemptNo,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "0|[1-9][0-9]{0,18}") String stateVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, maxLength = 128) String failureCode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "date-time") Instant firstDispatchedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "date-time") Instant dispatchedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "date-time") Instant deadlineAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "date-time") Instant nextAttemptAt) {
    /** 白名单投影；修订与代际转十进制字符串避免前端整数精度损失。
     * @param value 仓储作业摘要
     * @return HTTP作业摘要
     */
    public static OtaDeviceJobSummaryResponse from(OtaCampaignRepository.JobSummary value) {
        return new OtaDeviceJobSummaryResponse(value.id(), value.batchNumber(), value.deviceId(), value.deviceTypeId(),
                value.thingModelVersionId(), Long.toString(value.credentialVersion()), value.status(), value.attemptNo(),
                Long.toString(value.stateVersion()), value.failureCode(), value.firstDispatchedAt(), value.dispatchedAt(),
                value.deadlineAt(), value.nextAttemptAt());
    }
}
