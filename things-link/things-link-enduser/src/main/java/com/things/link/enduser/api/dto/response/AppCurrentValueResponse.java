package com.things.link.enduser.api.dto.response;

import com.things.link.device.application.AppCurrentValue;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * App 设备单属性当前值响应。
 *
 * @param propertyKey 物模型属性键
 * @param value       当前 JSON 值
 * @param occurredAt  设备采集时刻（RFC3339 UTC）
 * @param reportedRevision 正Long字符串接受序号；历史未知为null
 * @param thingModelVersionId 属性写入时模型来源；未知为null
 */
@Schema(description = "App 设备当前值")
public record AppCurrentValueResponse(
        @Schema(description = "物模型属性键") String propertyKey,
        @Schema(description = "当前 JSON 值") JsonNode value,
        @Schema(description = "设备采集时刻（RFC3339 UTC）") String occurredAt,
        @Schema(description = "属性接受序号，正Long字符串；未知为null", types = {"string", "null"},
                pattern = "[1-9][0-9]{0,18}") String reportedRevision,
        @Schema(description = "属性写入时模型来源；未知为null", types = {"string", "null"},
                format = "uuid") UUID thingModelVersionId) {

    /** @param value device 模块数据面投影 @return App 响应 */
    public static AppCurrentValueResponse from(AppCurrentValue value) {
        return new AppCurrentValueResponse(
                value.propertyKey(),
                value.value(),
                value.occurredAt() == null ? null : value.occurredAt().toString(),
                value.reportedRevision(), value.thingModelVersionId());
    }
}
