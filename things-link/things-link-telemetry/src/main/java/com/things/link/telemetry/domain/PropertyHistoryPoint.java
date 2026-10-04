package com.things.link.telemetry.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 属性历史查询结果点。
 *
 * @param ts 原始采集时刻或聚合桶起点
 * @param value 数值结果
 * @param sampleCount 该点覆盖的原始样本数；原始点固定为 1
 * @param thingModelVersionId 写入时物模型版本 ID；存量未版本化点为空
 * @param modelVersion 可读版本号；存量未版本化点标记为 LEGACY_UNVERSIONED
 */
public record PropertyHistoryPoint(Instant ts, double value, long sampleCount,
                                   UUID thingModelVersionId, String modelVersion) {
}
