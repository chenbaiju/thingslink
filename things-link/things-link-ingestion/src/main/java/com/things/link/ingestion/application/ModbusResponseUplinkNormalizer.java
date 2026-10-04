package com.things.link.ingestion.application;

import com.things.link.shared.message.ModbusResponse;
import com.things.link.shared.message.RawUplinkMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 把网关的 {@code up/modbus/response} 解析为 {@link ModbusResponse}。
 *
 * <p>归属字段只采用 raw 信封已确权的身份；payload 仅提供 requestId、状态与寄存器数据，禁止自报网关。
 * 非 Modbus 响应类型返回空，由 {@link RawUplinkMessageNormalizer} 继续处理。</p>
 */
public class ModbusResponseUplinkNormalizer {

    /** Modbus 响应的 §5.3 上行 Topic 类型。 */
    private static final String MODBUS_RESPONSE = "modbus/response";

    /** Jackson 由 Boot 统一配置。 */
    private final ObjectMapper objectMapper;

    /** @param objectMapper 应用统一 JSON 映射器 */
    public ModbusResponseUplinkNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析 Modbus 响应；非 Modbus 响应类型返回空。
     *
     * @param raw 接入层确权后的原始信封
     * @return Modbus 响应信封，非响应类型时为空
     * @throws InvalidUplinkMessageException payload 违反 Modbus 响应契约时抛出（进入 DLQ）
     */
    public Optional<ModbusResponse> tryNormalize(RawUplinkMessage raw) {
        MqttUplinkTopic topic = MqttUplinkTopic.parse(raw.topic()).orElse(null);
        if (topic == null || !MODBUS_RESPONSE.equals(topic.messageType())) {
            return Optional.empty();
        }
        try {
            JsonNode payload = objectMapper.readTree(raw.payload());
            UUID requestId = uuid(payload, "requestId");
            ModbusResponse.Status status = status(payload);
            List<Integer> data = data(payload);
            String errorCode = text(payload, "errorCode");
            return Optional.of(new ModbusResponse(requestId, raw.tenantId(), raw.projectId(), raw.deviceId(),
                    status, data, errorCode, raw.receivedAt(), raw.traceId()));
        } catch (InvalidUplinkMessageException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidUplinkMessageException("Modbus 响应 payload 不合法", exception);
        }
    }

    /** @return 必填 UUIDv7 requestId */
    private static UUID uuid(JsonNode payload, String field) {
        String value = text(payload, field);
        if (value == null) {
            throw new InvalidUplinkMessageException("Modbus 响应缺少字段: " + field);
        }
        try {
            UUID id = UUID.fromString(value);
            if (id.version() != 7) {
                throw new InvalidUplinkMessageException("Modbus 响应 requestId 必须是 UUIDv7");
            }
            return id;
        } catch (IllegalArgumentException exception) {
            throw new InvalidUplinkMessageException("Modbus 响应字段不是 UUID: " + field, exception);
        }
    }

    /** @return 响应状态 */
    private static ModbusResponse.Status status(JsonNode payload) {
        String value = text(payload, "status");
        if (value == null) {
            throw new InvalidUplinkMessageException("Modbus 响应缺少字段: status");
        }
        try {
            return ModbusResponse.Status.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new InvalidUplinkMessageException("Modbus 响应 status 非法: " + value, exception);
        }
    }

    /** @return 寄存器/位单元值数组 */
    private static List<Integer> data(JsonNode payload) {
        JsonNode node = payload == null ? null : payload.get("data");
        if (node == null || !node.isArray()) {
            throw new InvalidUplinkMessageException("Modbus 响应缺少 data 数组");
        }
        List<Integer> values = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isIntegralNumber()) {
                throw new InvalidUplinkMessageException("Modbus 响应 data 必须为整数数组");
            }
            values.add(element.asInt());
        }
        return values;
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
