package com.things.link.iam.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 重发验证邮件请求。
 *
 * <p>接口无论邮箱是否存在都返回 204，理由见
 * {@code EmailVerificationService#resendRegistrationVerification} ——
 * 它是<b>无需登录</b>的公开接口，区分开就是一个比注册接口更便宜的账号枚举通道。
 *
 * @param email 目标邮箱
 */
@Schema(description = "重发验证邮件请求")
public record ResendVerificationRequest(

        @Schema(description = "邮箱", example = "owner@example.com")
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 255, message = "邮箱过长")
        String email) {
}
