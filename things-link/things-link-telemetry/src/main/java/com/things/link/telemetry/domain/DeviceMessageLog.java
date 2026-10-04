package com.things.link.telemetry.domain;

import com.things.link.shared.message.TransportProtocol;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备原始消息日志。
 *
 * @param id 日志 ID
 * @param projectId 项目 ID
 * @param deviceId 设备 ID
 * @param messageId 标准消息信封 ID
 * @param tenantId 租户 ID
 * @param protocol 传输协议
 * @param direction 消息方向
 * @param topic MQTT Topic 或 HTTP 路径
 * @param payloadSummary 脱敏后的载荷摘要
 * @param rawBytes 原始报文字节数
 * @param errorCode 可选处理错误码
 * @param ts 消息发生时刻
 * @param receivedAt 平台接收时刻
 * @param traceId 链路追踪 ID
 * @param createdAt 日志落库时刻
 * @param messageType 消息类型；历史行为空
 * @param acceptedAt 受理时刻
 * @param parsedAt 解析时刻；尚未单独记录的阶段为空
 * @param processedAt 处理完成时刻
 * @param deliveredAt 投递时刻；上行不适用时为空
 * @param repliedAt 回复时刻；无回复时为空
 * @param truncated 摘要是否被截断
 * @param sampled 是否因采样只留标记
 */
public record DeviceMessageLog(UUID id, UUID projectId, UUID deviceId, UUID messageId, UUID tenantId,
                               TransportProtocol protocol, Direction direction, String topic, String payloadSummary,
                               int rawBytes, String errorCode, Instant ts, Instant receivedAt,
                               String traceId, Instant createdAt, String messageType, Instant acceptedAt,
                               Instant parsedAt, Instant processedAt, Instant deliveredAt, Instant repliedAt,
                               boolean truncated, boolean sampled) {

    /**
     * 兼容只关心接入事实的调用方（历史行与既有用例）：调试时间线字段留空、标记为未截断未采样。
     *
     * @param id 日志 ID
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param messageId 消息 ID
     * @param tenantId 租户 ID
     * @param protocol 传输协议
     * @param direction 方向
     * @param topic 主题或路径
     * @param payloadSummary 摘要
     * @param rawBytes 原始字节数
     * @param errorCode 错误码
     * @param ts 发生时刻
     * @param receivedAt 接收时刻
     * @param traceId 链路标识
     * @param createdAt 落库时刻
     */
    public DeviceMessageLog(UUID id, UUID projectId, UUID deviceId, UUID messageId, UUID tenantId,
                            TransportProtocol protocol, Direction direction, String topic, String payloadSummary,
                            int rawBytes, String errorCode, Instant ts, Instant receivedAt,
                            String traceId, Instant createdAt) {
        this(id, projectId, deviceId, messageId, tenantId, protocol, direction, topic, payloadSummary, rawBytes,
                errorCode, ts, receivedAt, traceId, createdAt, null, receivedAt, null, createdAt, null, null,
                false, false);
    }
    /** 消息相对平台的流向。 */
    public enum Direction {
        /** 设备到平台。 */ UP,
        /** 平台到设备。 */ DOWN
    }
}
