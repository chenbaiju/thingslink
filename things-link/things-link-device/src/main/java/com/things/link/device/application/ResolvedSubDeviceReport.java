package com.things.link.device.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 通过归属复核、交由 ingestion 拆分到 normalized 的子设备批量上报条目。
 *
 * <p>只暴露跨模块构造标准信封所需的稳定标量，不泄露 device domain 聚合；{@code deviceId} 由
 * device 模块从 deviceKey 解析，ingestion 不得自行映射设备标识。</p>
 *
 * @param deviceId 已复核归属的子设备 ID
 * @param messageId 网关为本次上报生成的 UUIDv7 消息标识
 * @param occurredAt 子设备采集时刻
 * @param modelVersion 子设备声明的写入时物模型版本
 * @param payload 已通过结构校验、待物模型校验的属性对象
 * @param rawBytes 按原始顺序均分的字节份额
 */
public record ResolvedSubDeviceReport(UUID deviceId, UUID messageId, Instant occurredAt, String modelVersion,
                                      Map<String, Object> payload, int rawBytes) {
}
