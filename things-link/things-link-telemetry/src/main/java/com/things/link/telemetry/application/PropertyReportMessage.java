package com.things.link.telemetry.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 标准化后的单属性上行消息。
 *
 * <p>该类型是 ingestion 模块调用 telemetry 的内部应用契约，不是控制台 HTTP DTO。
 * {@code messageId} 必须沿用接入层消息信封中的 ID，不能在消费端重新生成，否则 Kafka
 * 至少一次投递无法去重（架构文档第 5.1 节）。</p>
 *
 * @param messageId 上行消息 ID
 * @param projectId 项目 ID
 * @param deviceId 设备 ID
 * @param propertyKey 属性标识符
 * @param value 属性值
 * @param occurredAt 设备端采集时刻
 */
public record PropertyReportMessage(UUID messageId, UUID projectId, UUID deviceId,
                                    String propertyKey, Object value, Instant occurredAt) {
}
