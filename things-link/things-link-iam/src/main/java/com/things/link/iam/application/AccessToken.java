package com.things.link.iam.application;

import java.time.Instant;

/**
 * 签发出的访问令牌。
 *
 * @param value     令牌串
 * @param expiresAt 过期时刻（UTC）
 */
public record AccessToken(String value, Instant expiresAt) {
}
