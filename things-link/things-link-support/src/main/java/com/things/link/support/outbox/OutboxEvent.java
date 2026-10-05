package com.things.link.support.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 与业务事实同事务保存的待发布事件。
 *
 * <p>该值对象故意不包含业务领域类型：support 只负责可靠投递，不得反向依赖设备、遥测或告警模块。
 * {@code partitionKey} 必须由调用方按其顺序性契约给定；设备命令应使用实际路由设备的稳定 ID，
 * 否则 Kafka 分区会打破同设备命令的先后关系。</p>
 *
 * @param id 事件 UUIDv7 主键
 * @param tenantId 归属租户 ID
 * @param projectId RLS 项目隔离轴
 * @param aggregateType 产生事件的业务聚合类型
 * @param aggregateId 产生事件的业务聚合 ID
 * @param eventType 冻结事件类型，由发布器映射到受控 Kafka 主题与消息契约
 * @param destinationTopic 由固定路由目录派生并持久化的 Kafka Topic
 * @param partitionKey Kafka 分区键
 * @param payload 待反序列化为冻结消息契约的 JSON 对象文本
 * @param traceId 产生事件时继承的链路追踪 ID
 * @param availableAt 首次或下一次可投递时刻
 */
public record OutboxEvent(
        UUID id,
        UUID tenantId,
        UUID projectId,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String destinationTopic,
        String partitionKey,
        String payload,
        String traceId,
        Instant availableAt) {

    /**
     * 在进入业务事务前拒绝不完整或不可序列化的事件，避免留下永远无法投递的脏记录。
     */
    public OutboxEvent {
        Objects.requireNonNull(id, "Outbox 事件 ID 不能为空");
        Objects.requireNonNull(tenantId, "Outbox 租户 ID 不能为空");
        Objects.requireNonNull(projectId, "Outbox 项目 ID 不能为空");
        Objects.requireNonNull(aggregateId, "Outbox 聚合 ID 不能为空");
        Objects.requireNonNull(availableAt, "Outbox 可投递时刻不能为空");
        aggregateType = requireText(aggregateType, "聚合类型", 64);
        eventType = requireText(eventType, "事件类型", 64);
        destinationTopic = requireText(destinationTopic, "Kafka 目标 Topic", 128);
        String expectedTopic = OutboxRouteCatalog.topicFor(eventType);
        if (!expectedTopic.equals(destinationTopic)) {
            throw new IllegalArgumentException("Outbox 事件类型与目标 Topic 不匹配");
        }
        partitionKey = requireText(partitionKey, "Kafka 分区键", 128);
        payload = requireText(payload, "Outbox 载荷", 1_048_576);
        traceId = requireText(traceId, "链路追踪 ID", 64);
    }

    /**
     * 保持业务调用方只声明事件类型；目标 Topic 由 support 白名单派生，禁止业务输入注入。
     *
     * @param id 事件 ID
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param aggregateType 聚合类型
     * @param aggregateId 聚合 ID
     * @param eventType 事件类型
     * @param partitionKey Kafka 分区键
     * @param payload JSON 载荷
     * @param traceId 追踪标识
     * @param availableAt 可投递时间
     */
    public OutboxEvent(
            UUID id,
            UUID tenantId,
            UUID projectId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String partitionKey,
            String payload,
            String traceId,
            Instant availableAt) {
        this(id, tenantId, projectId, aggregateType, aggregateId, eventType,
                OutboxRouteCatalog.topicFor(eventType), partitionKey, payload, traceId, availableAt);
    }

    /**
     * 校验数据库列上限内的非空文本。
     *
     * @param value 待校验文本
     * @param field 中文字段名
     * @param maxLength 数据库列最大长度
     * @return 去除两端空白后的稳定文本
     */
    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + "长度超过 " + maxLength);
        }
        return normalized;
    }
}
