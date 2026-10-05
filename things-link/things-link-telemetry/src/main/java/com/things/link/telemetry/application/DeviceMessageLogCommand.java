package com.things.link.telemetry.application;

import com.things.link.shared.message.TransportProtocol;
import com.things.link.telemetry.domain.DeviceMessageLog;

import java.time.Instant;
import java.util.UUID;

/**
 * 接入层写入消息日志的应用命令。
 *
 * <p>租户 ID 不由调用者提供，而是根据 {@code projectId/deviceId} 从设备档案派生，避免
 * 被错误或恶意的消息信封伪造归属。</p>
 *
 * @param projectId 项目 ID
 * @param deviceId 设备 ID
 * @param messageId 标准消息信封 ID
 * @param protocol 传输协议
 * @param direction 消息方向
 * @param topic MQTT 消息主题 或 HTTP 路径
 * @param payloadSummary 已脱敏的载荷摘要
 * @param rawBytes 原始报文字节数
 * @param errorCode 可选处理错误码
 * @param occurredAt 消息发生时刻
 * @param receivedAt 平台接收时刻
 * @param traceId 链路追踪 ID
 * @param messageType 消息类型（业务语义，如 {@code PROPERTY_REPORT}）；未知时为空
 * @param deliveredAt 投递阶段时刻；上行或不适用时为空
 * @param repliedAt 设备回复阶段时刻；无回复时为空
 */
public record DeviceMessageLogCommand(UUID projectId, UUID deviceId, UUID messageId,
                                      TransportProtocol protocol, DeviceMessageLog.Direction direction,
                                      String topic, String payloadSummary, int rawBytes, String errorCode,
                                      Instant occurredAt, Instant receivedAt, String traceId, String messageType,
                                      Instant deliveredAt, Instant repliedAt) {

    /**
     * 兼容不带消息类型的调用方：类型留空，由查询面按"未知类型"处理。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param messageId 消息 ID
     * @param protocol 传输协议
     * @param direction 方向
     * @param topic 主题或路径
     * @param payloadSummary 摘要
     * @param rawBytes 原始字节数
     * @param errorCode 错误码
     * @param occurredAt 发生时刻
     * @param receivedAt 接收时刻
     * @param traceId 链路标识
     */
    public DeviceMessageLogCommand(UUID projectId, UUID deviceId, UUID messageId, TransportProtocol protocol,
                                   DeviceMessageLog.Direction direction, String topic, String payloadSummary,
                                   int rawBytes, String errorCode, Instant occurredAt, Instant receivedAt,
                                   String traceId) {
        this(projectId, deviceId, messageId, protocol, direction, topic, payloadSummary, rawBytes, errorCode,
                occurredAt, receivedAt, traceId, null, null, null);
    }

    /**
     * 兼容不带阶段时刻的调用方。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param messageId 消息 ID
     * @param protocol 传输协议
     * @param direction 方向
     * @param topic 主题或路径
     * @param payloadSummary 摘要
     * @param rawBytes 原始字节数
     * @param errorCode 错误码
     * @param occurredAt 发生时刻
     * @param receivedAt 接收时刻
     * @param traceId 链路标识
     * @param messageType 消息类型
     */
    public DeviceMessageLogCommand(UUID projectId, UUID deviceId, UUID messageId, TransportProtocol protocol,
                                   DeviceMessageLog.Direction direction, String topic, String payloadSummary,
                                   int rawBytes, String errorCode, Instant occurredAt, Instant receivedAt,
                                   String traceId, String messageType) {
        this(projectId, deviceId, messageId, protocol, direction, topic, payloadSummary, rawBytes, errorCode,
                occurredAt, receivedAt, traceId, messageType, null, null);
    }
}
