package com.things.link.device.api.dto.response;

import com.things.link.device.domain.DeviceCredential;

import java.util.UUID;

/**
 * 凭据创建响应——唯一一次携带明文密钥的接口。
 *
 * <p>调用方必须在收到响应后立即保存明文，此后任何查询接口都不再返回它。</p>
 *
 * @param id 凭据 ID @param deviceId 设备 ID @param authType 认证类型 @param plainSecret 明文密钥
 */
public record CredentialCreatedResponse(UUID id, UUID deviceId, String authType, String plainSecret) {
    /** @param c 携带明文的凭据 @return 响应 */
    public static CredentialCreatedResponse from(DeviceCredential c) {
        return new CredentialCreatedResponse(c.id(), c.deviceId(), c.authType().name(), c.plainSecret());
    }
}
