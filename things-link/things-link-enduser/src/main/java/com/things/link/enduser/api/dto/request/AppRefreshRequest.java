package com.things.link.enduser.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * App 刷新 / 注销请求体。
 *
 * <p>刷新令牌走<b>请求体</b>而非 Cookie（ADR 0036）：目标客户端是移动端，令牌存系统
 * 安全存储，没有浏览器 Cookie 机制可用。
 *
 * <p>刻意<b>不加</b> {@code @NotBlank}：缺失 / 空令牌与「令牌无效」对服务端不可区分，
 * 也不该区分 —— 两者处置都是重新登录（60007）或幂等返回（注销 204）。校验在这里抛 400
 * 反而会在「参数缺失」与「凭据错误」之间造出一条本不存在的边界。
 *
 * @param refreshToken 刷新令牌明文。刷新端点必填；注销端点可空（幂等）
 */
@Schema(description = "App 刷新 / 注销请求")
public record AppRefreshRequest(

        @Schema(description = "刷新令牌（存客户端安全存储）")
        String refreshToken) {

    @Override
    public String toString() {
        return "AppRefreshRequest[refreshToken=***]";
    }

}
