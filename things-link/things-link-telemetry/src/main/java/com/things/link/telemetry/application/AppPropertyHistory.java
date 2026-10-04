package com.things.link.telemetry.application;

import java.util.List;

/**
 * App 数据面可见的属性历史聚合投影。
 *
 * <p>从 domain {@link com.things.link.telemetry.domain.PropertyHistoryResult} 映射而来，粒度与
 * 聚合函数字符串化，这样 enduser 模块不必 import 本模块的 domain 枚举即可消费。
 *
 * @param requestedGranularity 客户端请求粒度
 * @param actualGranularity 服务端为满足 2000 点上限最终采用的粒度
 * @param aggregation 聚合函数
 * @param points 按时间正序排列的数据点
 */
public record AppPropertyHistory(String requestedGranularity, String actualGranularity,
                                 String aggregation, List<AppHistoryPoint> points) {
}
