package com.things.link.shared.message;

import java.util.UUID;

/**
 * Broker认证时签发并随原始消息保留的设备身份事实，不等于消费时仍有效的授权。
 *
 * @param tenantId 认证时权威租户
 * @param projectId 认证时权威项目
 * @param deviceId 连接设备，不能用网关载荷中的子设备替换
 * @param credentialVersion 本次实际校验的凭据代际，不能补查当前版本
 */
public record AuthenticatedDeviceIdentity(UUID tenantId, UUID projectId, UUID deviceId,
                                          long credentialVersion) {
    /** 拒绝不完整或溢出后的负代际，历史零版本仍可无损传递。 */
    public AuthenticatedDeviceIdentity {
        if (tenantId == null || projectId == null || deviceId == null || credentialVersion < 0) {
            throw new IllegalArgumentException("认证设备身份与非负凭据代际必须完整");
        }
    }
}
