package com.things.link.enduser.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * PUSH token 版本化密钥环配置。
 *
 * @param activeKeyId 当前写入密钥 ID
 * @param keys keyId 到 Base64 32 字节密钥的映射
 */
@ConfigurationProperties("things-link.enduser.push-token-encryption")
public record PushTokenEncryptionProperties(String activeKeyId, Map<String, String> keys) {
}
