package com.things.link.rule.infrastructure.messaging;

import com.things.link.rule.application.queue.RuleExecutionEnvelope;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;

import java.util.Objects;

/**
 * 使用项目统一的 Jackson 3 映射器读取规则重试信封。
 *
 * <p>Spring Kafka 4 自带的 {@code JsonDeserializer} 仍基于 Jackson 2；规则消息的 payload 已迁移为
 * Jackson 3 {@code JsonNode}，直接混用会在重启恢复时把合法 retry 记录卡在反序列化阶段。这个窄适配器只负责
 * 两个固定 retry Topic 的信封，不改变全局 Kafka 契约，也不开放任意受信包。</p>
 */
public final class RuleExecutionEnvelopeJacksonDeserializer implements Deserializer<Object> {

    /** Jackson 3 映射器；复用 Boot 配置以保留 UUID、Instant 与项目模块的统一行为。 */
    private final ObjectMapper objectMapper;

    /** @param objectMapper 项目统一 Jackson 3 映射器 */
    public RuleExecutionEnvelopeJacksonDeserializer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").rebuild()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    }

    /**
     * @param topic Kafka Topic，仅用于错误归因
     * @param data 信封 JSON 字节
     * @return 完整且已执行构造器身份校验的规则信封
     */
    @Override
    public Object deserialize(String topic, byte[] data) {
        if (data == null) {
            return null;
        }
        try {
            return objectMapper.readValue(data, RuleExecutionEnvelope.class);
        } catch (RuntimeException exception) {
            // Kafka 只识别 SerializationException 为反序列化失败；保留 cause 供 ErrorHandlingDeserializer 落证据。
            throw new SerializationException("规则重试信封无法由 Jackson 3 解码，topic=" + topic, exception);
        }
    }
}
