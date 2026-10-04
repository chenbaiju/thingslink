package com.things.link.device.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备类型自定义数据流。
 * @param id 数据流 ID @param tenantId 租户 ID @param projectId 项目 ID
 * @param deviceTypeId 设备类型 ID @param streamKey Topic 中使用的稳定标识符 @param name 名称
 * @param format 消息格式 @param mqttTopicAdvanced 是否启用高级 Topic
 * @param publishTopic 上行 Topic @param subscribeTopic 下行 Topic @param createdAt 创建时刻
 */
public record DeviceDataStream(UUID id, UUID tenantId, UUID projectId, UUID deviceTypeId,
                               String streamKey, String name, Format format, boolean mqttTopicAdvanced,
                               String publishTopic, String subscribeTopic, Instant createdAt) {
    /**
     * 平台支持的数据流消息格式；**当前仅控制面格式枚举，运行时消费尚未实现**
     * （ARCHITECTURE_GAPS G-13）。V1 范围已冻结为 TECHNICAL_DEFERRED（PS-039，S15），
     * 控制面公开契约已下线，本枚举在 V1 内不再对外暴露；S15 接线时按新契约恢复。
     */
    public enum Format { /** HEX 表示的任意二进制消息。 */ HEX, /** Plaintext 文本消息。 */ TEXT, /** JSON 格式消息。 */ JSON,
        /** Modbus RTU。 */ MODBUS_RTU, /** Modbus TCP。 */ MODBUS_TCP,
        /** 电力行业 645-2007。 */ DLT645_2007, /** 环保 HJ212-2017。 */ HJ212_2017 }
}
