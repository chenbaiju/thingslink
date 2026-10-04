package com.things.link.enduser.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/** refresh使用当前代次，logout使用退出前捕获代次；正文不接受refreshToken。 */
@Schema(description = "浏览器会话期望代次", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record AppBrowserEpochRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, pattern = "^be_[A-Za-z0-9_-]{22}$") String browserEpoch) { }
