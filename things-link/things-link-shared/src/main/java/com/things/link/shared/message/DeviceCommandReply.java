package com.things.link.shared.message;

import java.time.Instant;
import java.util.UUID;

/**
 * 由已认证上行 Topic 确权后交给命令状态机的设备回复。
 *
 * @param messageId 设备生成的 UUIDv7 幂等键
 * @param tenantId Topic 派生租户 @param projectId Topic 派生项目
 * @param connectionDeviceId 发布回复的实际连接设备
 * @param commandId Topic 参数中的命令 ID
 * @param occurredAt 设备发生时间 @param receivedAt 平台接收时间
 * @param status ACK/SUCCESS/FAILED @param outputJson 输出对象 JSON
 * @param errorCode 设备错误码 @param message 中文或厂商诊断摘要 @param traceId 链路 ID
 */
public record DeviceCommandReply(UUID messageId, UUID tenantId, UUID projectId,
                                 UUID connectionDeviceId, UUID commandId,
                                 Instant occurredAt, Instant receivedAt, Status status,
                                 String outputJson, String errorCode, String message, String traceId) {
    /** 设备回复阶段。 */
    public enum Status { /** 已收到。 */ ACK, /** 执行成功。 */ SUCCESS, /** 执行失败。 */ FAILED }
}
