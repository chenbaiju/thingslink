package com.things.link.enduser.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/** 浏览器登录闭集合同，实际解析由有界严格解析器执行。 */
@Schema(description = "浏览器登录请求；不接收tenantId或refreshToken", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record AppBrowserLoginRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 64) String projectKey,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 64) String username,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 128, accessMode = Schema.AccessMode.WRITE_ONLY) String password,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, pattern = "^be_[A-Za-z0-9_-]{22}$") String browserEpoch) {
    /** 防止默认record泄露密码。 */
    @Override public String toString() { return "AppBrowserLoginRequest[REDACTED]"; }
}
