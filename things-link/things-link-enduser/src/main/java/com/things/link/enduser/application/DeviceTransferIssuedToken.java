package com.things.link.enduser.application;

import java.time.Instant;

/**
 * 一次性主控转移令牌签发结果。
 *
 * @param token 只在本次响应出现的明文令牌
 * @param expiresAt 失效时刻（UTC）
 */
public record DeviceTransferIssuedToken(String token, Instant expiresAt) {
}
