package com.things.link.shared.message;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox 交给通知投递消费者的最小请求信封。
 *
 * <p>消息不携带邮箱、Webhook URL、正文或任何密钥；消费者按 {@code deliveryId} 在告警域的 项目 RLS 事实表读取快照。这样 Kafka
 * 保留期和死信消息都不会成为敏感投递内容的副本。
 *
 * @param eventId Outbox 事件 ID，消费者至少一次去重键
 * @param tenantId 租户归属 ID
 * @param projectId 项目隔离轴
 * @param deliveryId 告警域投递意图 ID，也是 Kafka 分区键
 * @param alarmInstanceId 关联事故 ID，仅用于受控诊断
 * @param alarmEventId 触发该投递的 ACTIVATED 事件 ID
 * @param attemptNo 业务外部投递序号；首次为 1
 * @param requestedAt 请求创建 UTC 时间
 * @param traceId 全链路追踪 ID
 */
public record NotificationDeliveryRequest(
        UUID eventId,
        UUID tenantId,
        UUID projectId,
        UUID deliveryId,
        UUID alarmInstanceId,
        UUID alarmEventId,
        int attemptNo,
        Instant requestedAt,
        String traceId) {

    /** Outbox 路由使用的稳定事件类型；业务写侧与 support 发布器必须引用同一常量。 */
    public static final String EVENT_TYPE = "ALARM_NOTIFICATION_DELIVERY_REQUEST";
}
