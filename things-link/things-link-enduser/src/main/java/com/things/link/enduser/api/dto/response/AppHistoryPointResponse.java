package com.things.link.enduser.api.dto.response;

import com.things.link.telemetry.application.AppHistoryPoint;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * App 历史曲线单点响应。
 *
 * @param ts          原始采集时刻或聚合桶起点（RFC3339 UTC）
 * @param value       数值结果
 * @param sampleCount 覆盖的原始样本数
 */
@Schema(description = "App 历史数据点")
public record AppHistoryPointResponse(
        @Schema(description = "采集时刻或聚合桶起点（RFC3339 UTC）") String ts,
        @Schema(description = "数值结果") double value,
        @Schema(description = "覆盖的原始样本数") long sampleCount) {

    /** @param point telemetry 模块数据面投影 @return App 响应 */
    public static AppHistoryPointResponse from(AppHistoryPoint point) {
        return new AppHistoryPointResponse(
                point.ts() == null ? null : point.ts().toString(),
                point.value(),
                point.sampleCount());
    }
}
