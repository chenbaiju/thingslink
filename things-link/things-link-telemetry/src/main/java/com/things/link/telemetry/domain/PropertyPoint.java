package com.things.link.telemetry.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备属性时序数据点。
 *
 * @param projectId 项目 ID
 * @param deviceId 设备 ID
 * @param propertyKey 属性标识符
 * @param ts 采集时刻
 * @param messageId 关联消息 ID
 * @param dataType 写入时物模型数据类型
 * @param thingModelVersionId 写入时不可变物模型版本 ID；存量点为空
 * @param modelVersion 写入时可读语义版本；存量点为空
 * @param valueDouble Number 值
 * @param valueText Text/Enum 值
 * @param valueBool Switch 值
 * @param valueJson 复杂值
 * @param quality 质量标记
 */
public record PropertyPoint(UUID projectId, UUID deviceId, String propertyKey, Instant ts, UUID messageId,
                            String dataType, UUID thingModelVersionId, String modelVersion,
                            Double valueDouble, String valueText, Boolean valueBool, String valueJson, short quality) {
}
