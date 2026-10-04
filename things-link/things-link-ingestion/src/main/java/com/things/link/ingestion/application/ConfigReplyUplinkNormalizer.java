package com.things.link.ingestion.application;

import com.things.link.shared.message.DeviceConfigReply;
import com.things.link.shared.message.RawUplinkMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 把网关的 {@code up/config/reply} 解析为 {@link DeviceConfigReply}。
 *
 * <p>归属字段（tenant/project/gateway）只采用 raw 信封已确权的身份；payload 仅提供配置类型、已应用版本与
 * 结果，禁止自报网关。非配置回执类型返回空，由 {@link RawUplinkMessageNormalizer} 继续处理。</p>
 */
public class ConfigReplyUplinkNormalizer {

    /** 配置回执的 §5.3 上行 Topic 类型。 */
    private static final String CONFIG_REPLY = "config/reply";

    /** Jackson 由 Boot 统一配置。 */
    private final ObjectMapper objectMapper;

    /** @param objectMapper 应用统一 JSON 映射器 */
    public ConfigReplyUplinkNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析配置回执；非配置回执类型返回空。
     *
     * @param raw 接入层确权后的原始信封
     * @return 配置回执信封，非配置回执类型时为空
     * @throws InvalidUplinkMessageException payload 违反配置回执契约时抛出（进入 DLQ）
     */
    public Optional<DeviceConfigReply> tryNormalize(RawUplinkMessage raw) {
        MqttUplinkTopic topic = MqttUplinkTopic.parse(raw.topic()).orElse(null);
        if (topic == null || !CONFIG_REPLY.equals(topic.messageType())) {
            return Optional.empty();
        }
        try {
            JsonNode payload = objectMapper.readTree(raw.payload());
            UUID messageId = uuid(payload, "messageId");
            String configType = text(payload, "configType");
            int version = payload.path("version").asInt(0);
            DeviceConfigReply.Status status = status(payload);
            String errorCode = text(payload, "errorCode");
            String message = text(payload, "message");
            Instant occurredAt = occurredAt(payload);
            return Optional.of(new DeviceConfigReply(messageId, raw.tenantId(), raw.projectId(), raw.deviceId(),
                    configType, version, status, errorCode, message, occurredAt, raw.receivedAt(), raw.traceId()));
        } catch (InvalidUplinkMessageException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidUplinkMessageException("配置回执 payload 不合法", exception);
        }
    }

    /** @return 必填 UUIDv7 字段，缺失或非法按契约错误处理 */
    private static UUID uuid(JsonNode payload, String field) {
        String value = text(payload, field);
        if (value == null) {
            throw new InvalidUplinkMessageException("配置回执缺少字段: " + field);
        }
        try {
            UUID id = UUID.fromString(value);
            if (id.version() != 7) {
                throw new InvalidUplinkMessageException("配置回执 messageId 必须是 UUIDv7");
            }
            return id;
        } catch (IllegalArgumentException exception) {
            throw new InvalidUplinkMessageException("配置回执字段不是 UUID: " + field, exception);
        }
    }

    /** @return 回执状态，非法按契约错误处理 */
    private static DeviceConfigReply.Status status(JsonNode payload) {
        String value = text(payload, "status");
        if (value == null) {
            throw new InvalidUplinkMessageException("配置回执缺少字段: status");
        }
        try {
            return DeviceConfigReply.Status.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new InvalidUplinkMessageException("配置回执 status 非法: " + value, exception);
        }
    }

    /** @return 必填 RFC3339 时刻 */
    private static Instant occurredAt(JsonNode payload) {
        String value = text(payload, "occurredAt");
        if (value == null) {
            throw new InvalidUplinkMessageException("配置回执缺少字段: occurredAt");
        }
        try {
            return Instant.parse(value);
        } catch (Exception exception) {
            throw new InvalidUplinkMessageException("配置回执 occurredAt 不是 RFC3339 时刻", exception);
        }
    }

    /** @return 可空文本字段，缺失返回 null */
    private static String text(JsonNode payload, String field) {
        JsonNode node = payload == null ? null : payload.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asString() : node.toString();
    }
}
