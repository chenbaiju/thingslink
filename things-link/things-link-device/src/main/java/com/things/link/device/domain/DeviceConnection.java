package com.things.link.device.domain;

import com.things.link.shared.message.TransportProtocol;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备连接会话记录。
 *
 * @param id 记录 ID @param tenantId 租户 ID @param projectId 项目 ID @param deviceId 设备 ID
 * @param sessionId MQTT Client ID @param protocol 传输协议 @param brokerNode Broker 节点
 * @param clientIp 设备 IP @param connectedAt 连接时刻 @param disconnectedAt 断开时刻
 * @param disconnectReason 断开原因 @param createdAt 创建时间
 */
public record DeviceConnection(UUID id, UUID tenantId, UUID projectId, UUID deviceId,
                               String sessionId, TransportProtocol protocol, String brokerNode,
                               String clientIp, Instant connectedAt, Instant disconnectedAt,
                               String disconnectReason, Instant createdAt) {
}
