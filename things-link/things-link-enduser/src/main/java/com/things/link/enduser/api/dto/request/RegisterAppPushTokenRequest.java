package com.things.link.enduser.api.dto.request;

import com.things.link.enduser.domain.AppPushToken;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 注册或轮换 App 安装实例 PUSH token。
 *
 * @param installationId 客户端稳定安装实例 UUID
 * @param provider 原生厂商通道；MOCK 只用于确定性测试
 * @param token 厂商 token 明文，只在本次请求内出现
 */
public record RegisterAppPushTokenRequest(
        @NotNull UUID installationId,
        @NotNull AppPushToken.Provider provider,
        @NotBlank @Size(max = 4096) String token) {
}
