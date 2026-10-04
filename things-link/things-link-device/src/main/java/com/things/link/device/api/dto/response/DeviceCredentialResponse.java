package com.things.link.device.api.dto.response;

import com.things.link.device.domain.DeviceCredential;

import java.time.Instant;
import java.util.UUID;

/** 凭据响应。不含明文密钥——明文仅在创建接口的响应中返回一次。 */
public record DeviceCredentialResponse(UUID id, UUID deviceId, String authType, String displayName,
                                       String serialNumber, Instant expiresAt, Instant lastUsedAt,
                                       Instant createdAt) {
    public static DeviceCredentialResponse from(DeviceCredential c) {
        return new DeviceCredentialResponse(c.id(), c.deviceId(), c.authType().name(), c.displayName(),
                c.serialNumber(), c.expiresAt(), c.lastUsedAt(), c.createdAt());
    }
}
