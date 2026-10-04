package com.things.link.enduser.api.dto.response;

import com.things.link.enduser.application.DeviceShareIssuedToken;

import java.time.Instant;

/**
 * SHARE 令牌签发响应；令牌明文只在此响应出现一次。
 *
 * @param token 一次性明文令牌
 * @param targetRole MEMBER 或 READ_ONLY
 * @param expiresAt 失效时刻
 */
public record DeviceShareTokenResponse(
        String token,
        String targetRole,
        Instant expiresAt) {

    /**
     * 从应用结果创建响应。
     *
     * @param issued 签发结果
     * @return API 响应
     */
    public static DeviceShareTokenResponse from(DeviceShareIssuedToken issued) {
        return new DeviceShareTokenResponse(
                issued.token(), issued.targetRole().name(), issued.expiresAt());
    }
}
