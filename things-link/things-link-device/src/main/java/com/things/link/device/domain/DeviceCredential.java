package com.things.link.device.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备认证凭据。
 *
 * <p>库中仅存 SHA-256 哈希，明文密钥只在 {@link #plainSecret()} 非空时可用——
 * 即创建凭据的返回值中携带一次，之后任何查询都不再返回明文。</p>
 *
 * @param id 凭据 ID @param tenantId 租户 ID @param projectId 项目 ID @param deviceId 设备 ID
 * @param authType 认证类型 @param credentialHash SHA-256 十六进制摘要 @param displayName 展示名称
 * @param serialNumber 证书序列号 @param expiresAt 过期时间 @param lastUsedAt 最后使用时刻
 * @param plainSecret 明文密钥（仅创建时非空） @param createdAt 创建时间
 */
public record DeviceCredential(UUID id, UUID tenantId, UUID projectId, UUID deviceId,
                               AuthType authType, String credentialHash, String displayName,
                               String serialNumber, Instant expiresAt, Instant lastUsedAt,
                               String plainSecret, Instant createdAt) {

    /** 设备认证方式。 */
    public enum AuthType { /** 一机一密令牌。 */ ACCESS_TOKEN, /** X.509 客户端证书。 */ X509_CERTIFICATE }
}
