package com.things.link.ota.api;

import com.things.link.ota.domain.OtaFirmwareLifecycleState;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * 公开生命周期投影，不携带对象位置、根配置或签名回执。
 * @param firmwareId 固件身份
 * @param status 当前固件状态
 * @param revision 当前修订十进制字符串
 * @param deprecation 可空退役记录
 * @param revocation 可空撤销记录
 */
@Schema(name = "OtaFirmwareLifecycleResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record OtaFirmwareLifecycleResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID firmwareId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"DRAFT", "VERIFYING", "READY", "CANCELLED", "DEPRECATED", "REVOKED"}) String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "0|[1-9][0-9]{0,18}") String revision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"object", "null"}) Transition deprecation,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"object", "null"}) Transition revocation) {
    /** 显式投影当前固件和转移记录。 */
    public static OtaFirmwareLifecycleResponse from(OtaFirmwareLifecycleState state) {
        return new OtaFirmwareLifecycleResponse(state.firmware().id(), state.firmware().status(),
                Long.toString(state.firmware().revision()), transition(state.deprecation()), transition(state.revocation()));
    }
    /** 未发生的事件保留null，不虚构原因或时间。 */
    private static Transition transition(OtaFirmwareLifecycleState.Transition value) {
        return value == null ? null : new Transition(value.reason(), value.actorId(), value.occurredAt());
    }
    /**
     * 首次发生后不可改写的转移事实。
     * @param reason 明确操作原因
     * @param actorId 服务端认证操作者
     * @param occurredAt UTC微秒精度发生时刻
     */
    @Schema(name = "OtaFirmwareLifecycleTransition", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Transition(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 512) String reason,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID actorId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time") Instant occurredAt) { }
}
