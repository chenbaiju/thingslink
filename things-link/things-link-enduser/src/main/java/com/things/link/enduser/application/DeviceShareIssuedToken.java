package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppUserDevice;

import java.time.Instant;

/**
 * SHARE 签发结果；明文令牌只允许在本对象中出现一次。
 *
 * @param token 一次性明文令牌
 * @param targetRole 消费成功后的 MEMBER 或 READ_ONLY
 * @param expiresAt 失效时刻
 */
public record DeviceShareIssuedToken(
        String token,
        AppUserDevice.RelationRole targetRole,
        Instant expiresAt) {
}
