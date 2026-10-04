package com.things.link.enduser.api.dto.response;

import com.things.link.telemetry.application.AppPropertyHistory;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * App 属性历史聚合响应。
 *
 * @param requestedGranularity 请求粒度
 * @param actualGranularity    实际粒度
 * @param aggregation          聚合函数
 * @param points               按时间正序排列的数据点
 */
@Schema(description = "App 属性历史")
public record AppHistoryResponse(
        @Schema(description = "请求粒度", example = "RAW") String requestedGranularity,
        @Schema(description = "实际粒度", example = "ONE_MINUTE") String actualGranularity,
        @Schema(description = "聚合函数", example = "AVG") String aggregation,
        @Schema(description = "按时间正序排列的数据点") List<AppHistoryPointResponse> points) {

    /** @param history telemetry 模块数据面投影 @return App 响应 */
    public static AppHistoryResponse from(AppPropertyHistory history) {
        return new AppHistoryResponse(
                history.requestedGranularity(),
                history.actualGranularity(),
                history.aggregation(),
                history.points().stream().map(AppHistoryPointResponse::from).toList());
    }
}
