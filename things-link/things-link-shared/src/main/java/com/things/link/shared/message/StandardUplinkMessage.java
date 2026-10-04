package com.things.link.shared.message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 协议解析完成后的统一上行消息信封。
 *
 * <p>本类型对应架构文档第 5 节的统一信封。它与 MQTT DTO 分离，使后续 HTTP、TCP、CoAP
 * 接入只需转换到同一契约，物模型校验、时序落库和规则处理不感知传输协议。</p>
 *
 * @param messageId 沿用设备报文的 UUIDv7，禁止消费端重新生成
 * @param tenantId 设备档案所属租户
 * @param projectId 设备所属项目
 * @param deviceId 设备 ID
 * @param gatewayId 可选网关设备 ID，直连设备为空
 * @param protocol 传输协议
 * @param direction 消息方向，本类型固定为 UP
 * @param type 标准消息类型
 * @param modelVersion 设备声明的写入时物模型语义版本；存量标量设备可在兼容窗口内省略（空值由 device 域推断）
 * @param occurredAt 设备侧发生时刻
 * @param receivedAt 平台接收时刻；不要求晚于 occurredAt，因为设备时钟不可信
 * @param traceId 全链路追踪标识
 * @param rawBytes 解码前原始 payload 字节数；计费口径不能用规范化 JSON 长度替代
 * @param payload 标准化业务载荷
 */
public record StandardUplinkMessage(UUID messageId, UUID tenantId, UUID projectId, UUID deviceId, UUID gatewayId,
                                    TransportProtocol protocol, Direction direction, Type type, String modelVersion,
                                    Instant occurredAt, Instant receivedAt, String traceId, int rawBytes,
                                    Map<String, Object> payload) {

    /** 消息方向；上行信封仍保留方向字段，以与后续通用日志和规则契约对齐。 */
    public enum Direction {
        /** 设备到平台的上行方向。 */
        UP
    }

    /** S3-11A 首先冻结标准属性上报；其他类型随对应纵向切片追加。 */
    public enum Type {
        /** 设备主动上报一个或多个属性当前值。 */
        PROPERTY_REPORT
    }

    /**
     * 冻结标准消息信封的必填字段与不可变性。
     */
    public StandardUplinkMessage {
        if (messageId == null || messageId.version() != 7) {
            throw new IllegalArgumentException("messageId 必须是 UUIDv7");
        }
        if (tenantId == null || projectId == null || deviceId == null) {
            throw new IllegalArgumentException("设备归属不能为空");
        }
        if (protocol == null || direction != Direction.UP || type == null) {
            throw new IllegalArgumentException("消息分类不完整");
        }
        // 存量标量设备可在 X-01 §5.2 兼容窗口内省略；省略由 device 域解析为已绑定初始版本并打点。
        if (modelVersion != null && !modelVersion.matches("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)")) {
            throw new IllegalArgumentException("modelVersion 必须是 major.minor.patch");
        }
        if (occurredAt == null || receivedAt == null || traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("消息时间与 traceId 不能为空");
        }
        if (rawBytes < 0) {
            throw new IllegalArgumentException("原始 payload 字节数不能为负数");
        }
        if (payload == null || payload.isEmpty()) {
            throw new IllegalArgumentException("标准 payload 不能为空");
        }
        // 标准消息投递后不得被消费者之间共享修改，否则重试结果会依赖处理顺序。
        payload = immutablePayload(payload);
    }

    /**
     * 兼容非原始接入测试与内部构造点；没有 raw 信封的调用方必须显式得到零字节而非估算 JSON。
     *
     * @param messageId 消息 UUIDv7
     * @param tenantId 所属租户
     * @param projectId 所属项目
     * @param deviceId 所属设备
     * @param gatewayId 可选网关
     * @param protocol 传输协议
     * @param direction 消息方向
     * @param type 消息类型
     * @param occurredAt 设备发生时刻
     * @param receivedAt 平台接收时刻
     * @param traceId 链路标识
     * @param payload 标准业务载荷
     */
    public StandardUplinkMessage(UUID messageId, UUID tenantId, UUID projectId, UUID deviceId, UUID gatewayId,
                                 TransportProtocol protocol, Direction direction, Type type, String modelVersion,
                                 Instant occurredAt, Instant receivedAt, String traceId, Map<String, Object> payload) {
        this(messageId, tenantId, projectId, deviceId, gatewayId, protocol, direction, type, modelVersion,
                occurredAt, receivedAt, traceId, 0, payload);
    }

    /**
     * 递归冻结属性 Map/List；顶层 {@code Map.copyOf} 无法阻止规则处理器修改嵌套对象。
     * @param source 待冻结属性对象
     * @return 保留 LIST 顺序和重复项的深度不可变快照
     */
    public static Map<String, Object> immutablePayload(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, immutableValue(value)));
        return Map.copyOf(result);
    }

    /** 递归复制 JSON 兼容值；非容器标量本身不可变。 */
    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                if (!(key instanceof String text)) {
                    throw new IllegalArgumentException("复合属性对象键必须是字符串");
                }
                result.put(text, immutableValue(nested));
            });
            return Map.copyOf(result);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(immutableValue(item)));
            return List.copyOf(result);
        }
        return value;
    }
}
