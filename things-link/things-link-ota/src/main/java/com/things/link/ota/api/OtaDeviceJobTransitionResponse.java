package com.things.link.ota.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.things.link.ota.domain.OtaCampaignRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** 管理端设备作业的不可变转移事实，是审计时间线而不是可推导的展示状态。
 * @param fromStatus 转移前状态
 * @param toStatus 转移后状态
 * @param fromRevision 期望业务修订，十进制字符串
 * @param toRevision 新业务修订，十进制字符串
 * @param actorKind 真实主体类型
 * @param actorId 真实管理账号，SYSTEM转移为空
 * @param occurredAt 数据库业务转移时间
 * @param reason 稳定转移原因，可空
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "OtaDeviceJobTransitionResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record OtaDeviceJobTransitionResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String fromStatus,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String toStatus,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "0|[1-9][0-9]{0,18}") String fromRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "0|[1-9][0-9]{0,18}") String toRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"SYSTEM", "MANAGEMENT"}) String actorKind,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "uuid") UUID actorId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant occurredAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, maxLength = 256) String reason) {
    /** 白名单投影；修订转十进制字符串避免前端整数精度损失。
     * @param value 仓储转移事实
     * @return HTTP转移响应
     */
    public static OtaDeviceJobTransitionResponse from(OtaCampaignRepository.JobTransition value) {
        return new OtaDeviceJobTransitionResponse(value.fromStatus(), value.toStatus(),
                Long.toString(value.fromRevision()), Long.toString(value.toRevision()), value.actorKind(),
                value.actorId(), value.occurredAt(), value.reason());
    }
}
