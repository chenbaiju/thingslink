package com.things.link.enduser.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** 浏览器响应只返回访问令牌与实际身份，refresh只通过独立HttpOnly Cookie交付。 */
@Schema(description = "浏览器会话；不包含刷新凭据")
public record AppBrowserSessionResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String accessToken,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant accessExpiresAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID appUserId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID projectId) {
    /** 禁止默认诊断展开访问凭据。 */
    @Override public String toString() { return "AppBrowserSessionResponse[REDACTED]"; }
}
