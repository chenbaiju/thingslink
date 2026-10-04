package com.things.link.ingestion.application;

import com.things.link.shared.message.DeviceTopologyMessage;
import com.things.link.shared.message.RawUplinkMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

/**
 * 把网关的拓扑/子设备上下行报文解析为 {@link DeviceTopologyMessage}。
 *
 * <p>归属字段（tenant/project/gateway）只采用 raw 信封已确权的身份；payload 仅提供子设备标识与
 * 注册附加字段，禁止自报网关。非拓扑类型返回空，由 {@link RawUplinkMessageNormalizer} 继续处理。</p>
 */
public class TopologyUplinkMessageNormalizer {

    /** Jackson 由 Boot 统一配置，确保与其余数据面使用同一解析规则。 */
    private final ObjectMapper objectMapper;

    /** @param objectMapper 应用统一 JSON 映射器 */
    public TopologyUplinkMessageNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析拓扑消息；非拓扑类型返回空。
     *
     * @param raw 接入层确权后的原始信封
     * @return 拓扑消息信封，非拓扑类型时为空
     * @throws InvalidUplinkMessageException payload 违反拓扑报文契约时抛出（进入 DLQ）
     */
    public Optional<DeviceTopologyMessage> tryNormalize(RawUplinkMessage raw) {
        MqttUplinkTopic topic = MqttUplinkTopic.parse(raw.topic()).orElse(null);
        if (topic == null) {
            return Optional.empty();
        }
        DeviceTopologyMessage.Type type = mapType(topic.messageType());
        if (type == null) {
            return Optional.empty();
        }
        try {
            JsonNode payload = objectMapper.readTree(raw.payload());
            UUID messageId = uuid(payload, "messageId");
            String subDeviceKey = text(payload, "subDeviceKey");
            String name = text(payload, "name");
            String deviceTypeKey = text(payload, "deviceTypeKey");
            return Optional.of(new DeviceTopologyMessage(messageId, raw.tenantId(), raw.projectId(),
                    raw.deviceId(), type, subDeviceKey, name, deviceTypeKey, raw.receivedAt(), raw.traceId()));
        } catch (InvalidUplinkMessageException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidUplinkMessageException("拓扑消息 payload 不合法: " + topic.messageType(), exception);
        }
    }

    /** 把 §5.3 上行 Topic 的 messageType 映射到拓扑消息类型。 */
    private static DeviceTopologyMessage.Type mapType(String messageType) {
        return switch (messageType) {
            case "topo/add" -> DeviceTopologyMessage.Type.TOPO_ADD;
            case "topo/delete" -> DeviceTopologyMessage.Type.TOPO_DELETE;
            case "sub/login" -> DeviceTopologyMessage.Type.SUB_DEVICE_LOGIN;
            case "sub/logout" -> DeviceTopologyMessage.Type.SUB_DEVICE_LOGOUT;
            case "sub/register" -> DeviceTopologyMessage.Type.SUB_DEVICE_REGISTER;
            default -> null;
        };
    }

    /** @return 必填 UUID 字段，缺失或非法时按报文契约错误处理 */
    private static UUID uuid(JsonNode payload, String field) {
        String value = text(payload, field);
        if (value == null) {
            throw new InvalidUplinkMessageException("拓扑消息缺少字段: " + field);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InvalidUplinkMessageException("拓扑消息字段不是 UUID: " + field, exception);
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
