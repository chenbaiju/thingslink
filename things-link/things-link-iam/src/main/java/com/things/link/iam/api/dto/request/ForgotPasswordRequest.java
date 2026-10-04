package com.things.link.iam.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 找回密码请求。
 *
 * <p>接口无论邮箱是否注册、是否已验证、账号是否被停用都返回 204，
 * 理由见 {@code PasswordResetService#requestReset} —— 这个接口不需要登录，
 * 区分开不仅会泄露「这个邮箱注册过」，还会连带泄露账号状态。
 *
 * @param email 目标邮箱
 */
@Schema(description = "找回密码请求")
public record ForgotPasswordRequest(

        @Schema(description = "邮箱", example = "owner@example.com")
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 255, message = "邮箱过长")
        String email) {
}
