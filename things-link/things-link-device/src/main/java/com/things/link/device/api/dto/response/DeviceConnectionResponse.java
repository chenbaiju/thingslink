package com.things.link.device.api.dto.response;

import com.things.link.device.domain.DeviceConnection;
import com.things.link.shared.message.TransportProtocol;

import java.time.Instant;
import java.util.UUID;

/** 设备连接历史响应，逐字段映射已有连接事实，不推断当前在线状态。 */
public record DeviceConnectionResponse(UUID id, UUID deviceId, String sessionId, TransportProtocol protocol,
                                       String brokerNode, String clientIp,
                                       Instant connectedAt, Instant disconnectedAt,
                                       String disconnectReason, Instant createdAt) {
    public static DeviceConnectionResponse from(DeviceConnection c) {
        return new DeviceConnectionResponse(c.id(), c.deviceId(), c.sessionId(), c.protocol(),
                c.brokerNode(), c.clientIp(), c.connectedAt(), c.disconnectedAt(),
                c.disconnectReason(), c.createdAt());
    }
}
