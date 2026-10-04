package com.things.link.device.domain;

import com.things.link.shared.message.TransportProtocol;

import java.time.Instant;
import java.util.UUID;

/**
 * 接入层已受理的一次设备尝试。
 *
 * <p>幂等键是 {@code (deviceId, messageId)}：{@code messageId} 由设备生成，只有与设备绑定才可信；
 * 载荷摘要用于区分「同一次尝试的重放」与「同一个 messageId 换了载荷」。</p>
 *
 * @param deviceId 已认证设备 ID
 * @param messageId 设备生成的 UUIDv7 消息标识
 * @param tenantId 归属租户
 * @param projectId 归属项目
 * @param protocol 首次受理使用的传输协议，仅用于诊断与计量
 * @param payloadDigest 业务载荷 SHA-256 十六进制摘要
 * @param receivedAt 首次受理时接入面收到报文的平台时刻
 * @param acceptedAt 受理事实写入时刻（数据库时钟）；尚未写入的候选尝试为空
 */
public record DeviceAccessRequest(UUID deviceId, UUID messageId, UUID tenantId, UUID projectId,
                                  TransportProtocol protocol, String payloadDigest,
                                  Instant receivedAt, Instant acceptedAt) {

    /** 冻结接入受理事实的必填字段与摘要形状。 */
    public DeviceAccessRequest {
        if (deviceId == null || messageId == null || tenantId == null || projectId == null) {
            throw new IllegalArgumentException("接入受理事实归属不能为空");
        }
        if (protocol == null || protocol == TransportProtocol.MQTT) {
            throw new IllegalArgumentException("接入受理事实只记录 HTTP／TCP／CoAP 尝试");
        }
        if (payloadDigest == null || !payloadDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("载荷摘要必须是 SHA-256 十六进制");
        }
        if (receivedAt == null) {
            throw new IllegalArgumentException("首次接收时刻不能为空");
        }
    }
}
