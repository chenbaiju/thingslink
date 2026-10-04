package com.things.link.enduser.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * App 消费 SHARE 令牌请求。
 *
 * @param token 一次性明文令牌
 */
public record ConsumeDeviceShareRequest(
        @NotBlank @Size(max = 256) String token) {
}
