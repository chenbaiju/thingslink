package com.things.link.telemetry.application;

import java.util.List;

/**
 * 完整有界版本化历史投影，来源版本相同时间点不得合并或丢弃。
 * @param requestedGranularity 原请求粒度
 * @param actualGranularity 预算升级后的实际粒度
 * @param aggregation 实际聚合操作
 * @param points 最多2000点的完整结果
 */
public record AppVersionedPropertyHistory(String requestedGranularity, String actualGranularity,
        String aggregation, List<AppVersionedHistoryPoint> points) {
    /** 固定结果集合，避免调用方在响应生成期间改变历史事实。 */
    public AppVersionedPropertyHistory {
        points = List.copyOf(points);
    }
}
