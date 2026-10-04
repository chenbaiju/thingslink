package com.things.link.shared.message;

import java.time.Instant;
import java.util.UUID;

/**
 * 网关对已应用配置版本的 ACK（{@code up/config/reply}）。
 *
 * <p>网关用 {@code version} 上报已应用版本，平台据此诊断「落后于当前发布版本」并重推最新配置。
 * {@code status} 为 {@code APPLIED} / {@code REJECTED}；失败时携带 {@code errorCode}/{@code message}。</p>
 *
 * @param messageId 网关生成的 UUIDv7 幂等键
 * @param tenantId 网关所属租户（接入确权派生）
 * @param projectId 网关所属项目（接入确权派生）
 * @param gatewayId 已认证的网关设备 ID
 * @param configType 配置类型
 * @param version 已应用版本
 * @param status 应用结果
 * @param errorCode 拒绝原因稳定码，仅 REJECTED 时非空
 * @param message 拒绝诊断摘要，仅 REJECTED 时非空
 * @param occurredAt 网关应用时刻
 * @param receivedAt 平台接收时刻
 * @param traceId 链路追踪标识
 */
public record DeviceConfigReply(UUID messageId, UUID tenantId, UUID projectId, UUID gatewayId,
                                String configType, int version, Status status, String errorCode, String message,
                                Instant occurredAt, Instant receivedAt, String traceId) {

    /** Modbus 点位映射配置类型。 */
    public static final String CONFIG_TYPE = "MODBUS_POINT_MAPPING";

    /** 应用结果。 */
    public enum Status { /** 已应用。 */ APPLIED, /** 应用被拒绝。 */ REJECTED }

    /**
     * 冻结回执信封的必填字段与不可变性。
     */
    public DeviceConfigReply {
        if (messageId == null || messageId.version() != 7) {
            throw new IllegalArgumentException("messageId 必须是 UUIDv7");
        }
        if (tenantId == null || projectId == null || gatewayId == null) {
            throw new IllegalArgumentException("配置回执归属不能为空");
        }
        if (configType == null || configType.isBlank() || status == null) {
            throw new IllegalArgumentException("配置回执类型与状态不能为空");
        }
        if (version < 0) {
            throw new IllegalArgumentException("配置版本不能为负数");
        }
        if (occurredAt == null || receivedAt == null || traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("配置回执时间与 traceId 不能为空");
        }
        boolean rejected = status == Status.REJECTED;
        if (rejected && (errorCode == null || errorCode.isBlank())) {
            throw new IllegalArgumentException("拒绝回执必须携带 errorCode");
        }
        if (!rejected && (errorCode != null || message != null)) {
            throw new IllegalArgumentException("应用回执不得携带 errorCode 或 message");
        }
    }
}
