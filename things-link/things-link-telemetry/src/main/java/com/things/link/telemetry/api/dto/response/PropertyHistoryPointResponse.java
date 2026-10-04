package com.things.link.telemetry.api.dto.response;

import com.things.link.telemetry.domain.PropertyHistoryPoint;

import java.time.Instant;
import java.util.UUID;

/**
 * 历史曲线数据点。
 *
 * @param ts 原始采集时刻或聚合桶起点
 * @param value 数值
 * @param sampleCount 覆盖的原始样本数
 * @param thingModelVersionId 写入时物模型版本 ID
 * @param modelVersion 可读版本号或 LEGACY_UNVERSIONED
 */
public record PropertyHistoryPointResponse(Instant ts, double value, long sampleCount,
                                           UUID thingModelVersionId, String modelVersion) {
    /** @return 领域结果对应的 HTTP DTO */
    public static PropertyHistoryPointResponse from(PropertyHistoryPoint point) {
        return new PropertyHistoryPointResponse(point.ts(), point.value(), point.sampleCount(),
                point.thingModelVersionId(), point.modelVersion());
    }
}
