package com.things.link.telemetry.api.dto.response;

import com.things.link.shared.message.TransportProtocol;
import com.things.link.telemetry.domain.DeviceMessageLog;

import java.time.Instant;
import java.util.UUID;

/**
 * 控制台展示的设备消息日志。
 *
 * @param id 日志 ID
 * @param deviceId 设备 ID
 * @param messageId 消息 ID
 * @param protocol 传输协议
 * @param direction 消息方向
 * @param topic MQTT 消息主题 或 HTTP 路径
 * @param payloadSummary 脱敏载荷摘要
 * @param rawBytes 原始报文字节数
 * @param errorCode 可选处理错误码
 * @param ts 消息发生时刻
 * @param receivedAt 平台接收时刻
 * @param traceId 链路追踪 ID
 */
public record MessageLogResponse(UUID id, UUID deviceId, UUID messageId, TransportProtocol protocol,
                                 DeviceMessageLog.Direction direction, String topic, String payloadSummary,
                                 int rawBytes, String errorCode,
                                 Instant ts, Instant receivedAt, String traceId,
                                 String messageType, Instant acceptedAt, Instant parsedAt, Instant processedAt,
                                 Instant deliveredAt, Instant repliedAt, boolean truncated, boolean sampled) {
    /**
     * 把领域日志转换为 API 响应。
     *
     * @param log 领域日志
     * @return API 响应
     */
    public static MessageLogResponse from(DeviceMessageLog log) {
        return new MessageLogResponse(log.id(), log.deviceId(), log.messageId(), log.protocol(),
                log.direction(), log.topic(), log.payloadSummary(), log.rawBytes(),
                log.errorCode(), log.ts(), log.receivedAt(), log.traceId(), log.messageType(),
                log.acceptedAt(), log.parsedAt(), log.processedAt(), log.deliveredAt(), log.repliedAt(),
                log.truncated(), log.sampled());
    }
}
