package com.things.link.ota.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.things.link.ota.domain.OtaCampaignRepository;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 管理端设备作业详情：摘要字段加不可变转移时间线，不含租户、租约令牌或规范字节。
 *
 * <p>时间线按{@code toRevision}升序返回，与作业的{@code stateVersion}链一一对应；
 * 列表最多保留最早的200条，真实作业状态机远低于该上限，因此不会截断最新事实。
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
 * @param transitions 按修订升序的真实转移时间线
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "OtaDeviceJobDetailResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record OtaDeviceJobDetailResponse(
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
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "date-time") Instant nextAttemptAt,
        @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED), maxItems = 200,
                schema = @Schema(implementation = OtaDeviceJobTransitionResponse.class)) List<OtaDeviceJobTransitionResponse> transitions) {
    /** 防止外部列表在序列化前被修改。 */
    public OtaDeviceJobDetailResponse { transitions = List.copyOf(transitions); }
    /** 白名单投影，摘要字段与列表接口同口径。
     * @param value 仓储作业详情
     * @return HTTP作业详情
     */
    public static OtaDeviceJobDetailResponse from(OtaCampaignRepository.JobDetail value) {
        var job = value.summary();
        return new OtaDeviceJobDetailResponse(job.id(), job.batchNumber(), job.deviceId(), job.deviceTypeId(),
                job.thingModelVersionId(), Long.toString(job.credentialVersion()), job.status(), job.attemptNo(),
                Long.toString(job.stateVersion()), job.failureCode(), job.firstDispatchedAt(), job.dispatchedAt(),
                job.deadlineAt(), job.nextAttemptAt(), value.transitions().stream()
                        .map(OtaDeviceJobTransitionResponse::from).toList());
    }
}
