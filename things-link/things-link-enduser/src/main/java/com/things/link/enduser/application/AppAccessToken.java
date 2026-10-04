package com.things.link.enduser.application;

import java.time.Instant;

/**
 * 签发出的 App 访问令牌。
 *
 * @param value     令牌串
 * @param expiresAt 过期时刻（UTC）
 */
public record AppAccessToken(String value, Instant expiresAt) {
}
