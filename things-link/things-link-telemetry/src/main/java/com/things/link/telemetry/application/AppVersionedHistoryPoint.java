package com.things.link.telemetry.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 数据运行合同§3.4的单个版本化历史事实；旧来源不伪装成当前模型。
 * @param ts 原始时刻或UTC聚合桶
 * @param value 数值历史事实
 * @param sampleCount 真实样本数
 * @param thingModelVersionId 来源模型版本，LEGACY_UNVERSIONED时可空
 * @param modelVersion 来源版本文本或LEGACY_UNVERSIONED
 */
public record AppVersionedHistoryPoint(Instant ts, double value, long sampleCount,
        UUID thingModelVersionId, String modelVersion) {
}
