package com.things.link.shared.message;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * CURRENT属性业务事务已接纳的不可变事件，不代表自动化已经执行。
 * @param schemaVersion 固定协议版本1
 * @param sourceEventId 原始上行messageId，重试不得重生成
 * @param tenantId 持久设备租户
 * @param projectId 项目隔离轴
 * @param deviceId 实际设备及Kafka分区键
 * @param modelVersion device裁决后的语义版本，包含兼容推断结果
 * @param occurredAt 原设备事件时间
 * @param acceptedAt 业务事务中数据库观察到的接纳时间
 * @param payload 已通过校验的处理后属性快照
 * @param traceId 原链路标识
 */
public record AutomationPropertyAccepted(int schemaVersion, UUID sourceEventId, UUID tenantId,
        UUID projectId, UUID deviceId, String modelVersion, Instant occurredAt, Instant acceptedAt,
        Map<String, Object> payload, String traceId) {
    /** 固定Outbox事件类型。 */
    public static final String EVENT_TYPE = "AUTOMATION_PROPERTY_ACCEPTED";
    /** 固定内部Topic，部署创建后才能启用生产者。 */
    public static final String TOPIC = "tc.rule.automation.property.accepted";
    /** 固定聚合，避免同ID的其他业务事件混入。 */
    public static final String AGGREGATE_TYPE = "AUTOMATION_PROPERTY";

    /** 反序列化也必须经过完整信封校验；不能从缺字段消息猜测归属。 */
    public AutomationPropertyAccepted {
        if (schemaVersion != 1 || sourceEventId == null || sourceEventId.version() != 7
                || tenantId == null || projectId == null || deviceId == null
                || occurredAt == null || acceptedAt == null || traceId == null
                || traceId.isBlank() || traceId.length() > 64
                || modelVersion == null || !modelVersion.matches("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)")
                || payload == null || payload.isEmpty()) {
            throw new IllegalArgumentException("自动化属性事件信封不完整或版本不支持");
        }
        payload = StandardUplinkMessage.immutablePayload(payload);
    }
}
