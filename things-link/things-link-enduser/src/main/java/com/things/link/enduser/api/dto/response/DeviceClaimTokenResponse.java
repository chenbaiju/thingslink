package com.things.link.enduser.api.dto.response;

import com.things.link.enduser.application.DeviceClaimIssuedToken;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 一次性 CLAIM 令牌签发响应。
 *
 * @param token     明文令牌；服务端不会再次返回
 * @param expiresAt 失效时刻（UTC）
 */
public record DeviceClaimTokenResponse(
        @Schema(description = "一次性认领令牌，仅本次响应可见") String token,
        Instant expiresAt) {

    /**
     * 从应用层签发结果建立 API 响应。
     *
     * @param issuedToken 签发结果
     * @return API 响应
     */
    public static DeviceClaimTokenResponse from(DeviceClaimIssuedToken issuedToken) {
        return new DeviceClaimTokenResponse(issuedToken.token(), issuedToken.expiresAt());
    }
}
