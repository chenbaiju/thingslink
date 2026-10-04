package com.things.link.telemetry.domain;

import java.util.List;

/**
 * 带实际粒度的历史查询结果。
 *
 * @param requestedGranularity 客户端请求粒度
 * @param actualGranularity 服务端为满足 2000 点上限最终采用的粒度
 * @param aggregation 聚合函数
 * @param points 按时间正序排列的数据点
 */
public record PropertyHistoryResult(HistoryGranularity requestedGranularity,
                                    HistoryGranularity actualGranularity,
                                    HistoryAggregation aggregation,
                                    List<PropertyHistoryPoint> points) {
}
