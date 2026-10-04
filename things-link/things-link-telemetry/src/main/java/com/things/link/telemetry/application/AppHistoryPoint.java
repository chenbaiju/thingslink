package com.things.link.telemetry.application;

import java.time.Instant;

/**
 * App 数据面可见的单个历史点。
 *
 * @param ts 原始采集时刻或聚合桶起点
 * @param value 数值结果
 * @param sampleCount 该点覆盖的原始样本数；原始点固定为 1
 */
public record AppHistoryPoint(Instant ts, double value, long sampleCount) {
}
