package com.things.link.telemetry.api.dto.response;

import com.things.link.telemetry.domain.HistoryAggregation;
import com.things.link.telemetry.domain.HistoryGranularity;
import com.things.link.telemetry.domain.PropertyHistoryResult;

import java.util.List;

/**
 * 属性历史聚合响应。
 *
 * @param requestedGranularity 请求粒度
 * @param actualGranularity 实际粒度；与请求不同时说明服务端为满足 2000 点上限进行了升级
 * @param aggregation 聚合函数
 * @param points 最多 2000 个、按时间正序排列的点
 */
public record PropertyHistoryResponse(HistoryGranularity requestedGranularity,
                                      HistoryGranularity actualGranularity,
                                      HistoryAggregation aggregation,
                                      List<PropertyHistoryPointResponse> points) {
    /** @return 领域结果对应的 HTTP DTO */
    public static PropertyHistoryResponse from(PropertyHistoryResult result) {
        return new PropertyHistoryResponse(result.requestedGranularity(), result.actualGranularity(),
                result.aggregation(), result.points().stream().map(PropertyHistoryPointResponse::from).toList());
    }
}
