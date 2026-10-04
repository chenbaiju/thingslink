package com.things.link.enduser.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * App 消费 CLAIM 令牌请求。
 *
 * <p>只限制为空与异常大输入，不在 DTO 暴露合法令牌格式；任意不匹配明文都进入统一 60012，
 * 避免请求校验器变成令牌格式探针。
 *
 * @param token 一次性认领令牌明文
 */
public record ConsumeDeviceClaimRequest(
        @NotBlank @Size(max = 128) String token) {
}
