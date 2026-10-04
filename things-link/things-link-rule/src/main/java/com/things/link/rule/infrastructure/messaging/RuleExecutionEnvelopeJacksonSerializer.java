package com.things.link.rule.infrastructure.messaging;

import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

/**
 * 使用项目统一的 Jackson 3 映射器编码规则恢复路径的消息。
 *
 * <p>Spring Kafka 4 自带的 {@code JsonSerializer} 仍基于 Jackson 2；规则消息的 payload 是 Jackson 3
 * {@code JsonNode}，Jackson 2 无法识别该类型，会把它当作普通 POJO 序列化成自省布尔标志
 * （{@code {"object":true,"nodeType":"OBJECT",...}}），静默丢弃真实载荷。这个窄序列化器只覆盖规则恢复路径
 * （重试信封与 DLQ 消息）的发布，不改变全局 Kafka 契约。</p>
 */
public final class RuleExecutionEnvelopeJacksonSerializer implements Serializer<Object> {

    /** Jackson 3 映射器；复用 Boot 配置以保留 UUID、Instant 与项目模块的统一行为。 */
    private final ObjectMapper objectMapper;

    /** @param objectMapper 项目统一 Jackson 3 映射器 */
    public RuleExecutionEnvelopeJacksonSerializer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * @param topic Kafka Topic，仅用于错误归因
     * @param data 待编码的信封或死信消息
     * @return 完整 Jackson 3 JSON 字节
     */
    @Override
    public byte[] serialize(String topic, Object data) {
        if (data == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsBytes(data);
        } catch (RuntimeException exception) {
            throw new SerializationException("规则恢复信封无法由 Jackson 3 编码，topic=" + topic, exception);
        }
    }
}
