package com.things.link.ota.api;

import com.things.link.ota.application.OtaTypeBaselineService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 已登记类型声明的公开元数据，不代表设备或制造证据已完成验收。
 * @param revision 领域修订十进制字符串
 * @param baselineHash 规范基线SHA256
 * @param baseline 完整受控类型声明
 * @param registeredAt 首次登记时间
 * @param updatedAt 最近登记版本时间
 */
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"revision", "baselineHash", "baseline", "registeredAt", "updatedAt"})
public record OtaTypeBaselineResponse(
        @Schema(pattern = "[1-9][0-9]{0,18}") String revision,
        @Schema(pattern = "[0-9a-f]{64}") String baselineHash,
        OtaTypeBaselineBody baseline, Instant registeredAt, Instant updatedAt) {
    /** 只由已经校验持久字节的应用快照生成typed响应。 */
    public static OtaTypeBaselineResponse from(OtaTypeBaselineService.Snapshot snapshot) {
        return new OtaTypeBaselineResponse(Long.toString(snapshot.state().revision()), snapshot.state().baselineHash(),
                OtaTypeBaselineBody.from(snapshot.decoded()), snapshot.state().createdAt(), snapshot.state().updatedAt());
    }
}
