package com.things.link.ota.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.things.link.ota.application.OtaCampaignPlanCodec;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** 可审阅的闭集活动计划；不表示已经具备设备派发资格。
 * @param contractVersion 合同版本
 * @param firmwareId 固定固件身份
 * @param deviceIds 规范排序的显式目标
 * @param batchSize 稳定批大小
 * @param notBefore 秒精度UTC最早启动时间
 * @param executionPolicy 冻结执行策略
 */
@Schema(name = "OtaCampaignPlanBody", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record OtaCampaignPlanBody(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"tc-ota-campaign-plan/v1"}) String contractVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID firmwareId,
        @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED), minItems = 1, maxItems = 1000, uniqueItems = true, schema = @Schema(type = "string", format = "uuid")) List<UUID> deviceIds,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "1000") int batchSize,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z") String notBefore,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Policy executionPolicy) {
    /** 列表不能在响应构造后发生漂移。 */
    public OtaCampaignPlanBody { deviceIds = List.copyOf(deviceIds); }
    /** 将已严格解析的规范计划逐字段投影为HTTP合同。 */
    public static OtaCampaignPlanBody from(OtaCampaignPlanCodec.Decoded decoded) {
        var p = decoded.value(); var e = p.executionPolicy(); var t = e.stageTimeoutSeconds();
        return new OtaCampaignPlanBody(p.contractVersion(), p.firmwareId(), p.deviceIds(), p.batchSize(),
                p.notBefore().toString(), new Policy(e.maxConcurrentDownloads(), e.maxDownloadBytesPerSecond(),
                e.downloadRetryLimit(), e.retryBackoffSeconds(), e.healthWindowSeconds(), e.pauseMinEvaluated(),
                e.pauseFailureCount(), e.pauseFailureRateBps(), e.batchMinSuccessRateBps(), e.requireManualBatchApproval(),
                new StageTimeouts(t.dispatched(), t.downloading(), t.verifying(), t.installing(), t.rebooting(),
                        t.healthChecking(), t.confirming(), t.rollbackPending(), t.rollingBack())));
    }
    /** 全部显式策略；跨字段约束由严格Codec执行。
     * @param maxConcurrentDownloads 下载并发上限
     * @param maxDownloadBytesPerSecond 下载预算字节每秒
     * @param downloadRetryLimit 额外重试次数
     * @param retryBackoffSeconds 固定重试退避秒数
     * @param healthWindowSeconds 连续健康窗口秒数
     * @param pauseMinEvaluated 暂停最小样本
     * @param pauseFailureCount 暂停失败数阈值
     * @param pauseFailureRateBps 暂停失败率基点
     * @param batchMinSuccessRateBps 批次成功门槛基点
     * @param requireManualBatchApproval 下一批是否须人工放行
     * @param stageTimeoutSeconds 每阶段总超时预算
     */
    @Schema(name = "OtaCampaignExecutionPolicy", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Policy(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "1000") int maxConcurrentDownloads,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "1073741824") long maxDownloadBytesPerSecond,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "10") int downloadRetryLimit,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "3600") int retryBackoffSeconds,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "86400") int healthWindowSeconds,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "1000") int pauseMinEvaluated,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "1000") int pauseFailureCount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "10000") int pauseFailureRateBps,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "10000") int batchMinSuccessRateBps,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean requireManualBatchApproval,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) StageTimeouts stageTimeoutSeconds) { }
    /** 秒单位阶段预算；刷写后超时仍须对账，不能据此盲重试。
     * @param dispatched DISPATCHED阶段总预算
     * @param downloading DOWNLOADING阶段总预算
     * @param verifying VERIFYING阶段总预算
     * @param installing INSTALLING阶段总预算
     * @param rebooting REBOOTING阶段总预算
     * @param healthChecking HEALTH_CHECKING阶段总预算
     * @param confirming CONFIRMING阶段总预算
     * @param rollbackPending ROLLBACK_PENDING阶段总预算
     * @param rollingBack ROLLING_BACK阶段总预算
     */
    @Schema(name = "OtaCampaignStageTimeouts", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record StageTimeouts(
            @JsonProperty("DISPATCHED") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "86400") int dispatched,
            @JsonProperty("DOWNLOADING") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "86400") int downloading,
            @JsonProperty("VERIFYING") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "86400") int verifying,
            @JsonProperty("INSTALLING") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "86400") int installing,
            @JsonProperty("REBOOTING") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "86400") int rebooting,
            @JsonProperty("HEALTH_CHECKING") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "86400") int healthChecking,
            @JsonProperty("CONFIRMING") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "86400") int confirming,
            @JsonProperty("ROLLBACK_PENDING") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "86400") int rollbackPending,
            @JsonProperty("ROLLING_BACK") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "86400") int rollingBack) { }
}
