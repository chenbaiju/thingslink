package com.things.link.shared.message;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * MQTT {@code up/property/report} 的设备侧 JSON 报文。
 *
 * <p>{@code messageId} 必须由设备生成并在重试时保持不变；平台不能在消费端重新生成，
 * 否则 QoS 1 的重复投递无法通过 inbox 去重。属性采用一个对象批量承载，保证同一次采样
 * 的多个属性可以在后续消费者中作为一个事务处理，而不会出现半条消息落库。</p>
 *
 * @param messageId 设备生成的 UUIDv7 消息标识
 * @param occurredAt 设备采集时刻，使用 RFC3339 UTC 序列化
 * @param modelVersion 设备本次上报使用的物模型语义版本
 * @param payload 非空属性键值对象
 */
public record DevicePropertyReport(UUID messageId, Instant occurredAt, String modelVersion,
                                   Map<String, Object> payload) {

    /** 属性标识符沿用物模型安全字符集，禁止借键名构造 JSON 路径歧义。 */
    private static final String PROPERTY_KEY_PATTERN = "[A-Za-z0-9_-]{1,64}";

    /**
     * 冻结设备报文的不可变约束。
     */
    public DevicePropertyReport {
        requireUuidV7(messageId);
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt 不能为空");
        }
        // 存量标量设备可在 X-01 §5.2 兼容窗口内省略 modelVersion，省略值由 device 域推断为已绑定初始版本。
        if (modelVersion != null && !modelVersion.matches("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)")) {
            throw new IllegalArgumentException("modelVersion 必须是 major.minor.patch");
        }
        if (payload == null || payload.isEmpty()) {
            throw new IllegalArgumentException("payload 必须包含至少一个属性");
        }
        if (payload.keySet().stream().anyMatch(key -> key == null || !key.matches(PROPERTY_KEY_PATTERN))) {
            throw new IllegalArgumentException("payload 包含非法属性标识符");
        }
        // 防御性复制避免发布后调用方继续修改 Map，造成日志内容与实际发送内容不一致。
        payload = StandardUplinkMessage.immutablePayload(payload);
    }

    /**
     * 校验设备消息使用 UUIDv7；版本位错误意味着设备 SDK 没有遵守不可回退的消息契约。
     *
     * @param value 待校验标识
     */
    private static void requireUuidV7(UUID value) {
        if (value == null || value.version() != 7) {
            throw new IllegalArgumentException("messageId 必须是 UUIDv7");
        }
    }
}
