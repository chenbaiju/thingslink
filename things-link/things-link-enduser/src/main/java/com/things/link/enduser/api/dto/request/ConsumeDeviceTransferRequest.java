package com.things.link.enduser.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * App 接收主控转移请求。
 *
 * <p>不暴露合法令牌格式；任意不匹配输入都由状态机合并为 60016，避免参数校验器成为
 * TRANSFER 凭据格式探针。
 *
 * @param token 一次性 TRANSFER 令牌明文
 */
public record ConsumeDeviceTransferRequest(
        @NotBlank @Size(max = 128) String token) {
}
