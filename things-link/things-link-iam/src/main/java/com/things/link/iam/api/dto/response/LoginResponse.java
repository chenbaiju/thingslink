package com.things.link.iam.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 登录响应。
 *
 * <p>刻意<b>不返回</b>账号 ID、邮箱、角色等信息：它们都已经在令牌的声明里，
 * 前端解析令牌即可。响应体重复一遍只会造成两处数据可能不一致。
 *
 * @param accessToken 访问令牌
 * @param expiresAt   过期时刻，RFC3339 UTC 字符串（架构文档 11.1：
 *                    时间一律返回 UTC，本地化由前端做）
 */
@Schema(description = "登录响应")
public record LoginResponse(

        @Schema(description = "访问令牌，后续请求放在 Authorization: Bearer 头中")
        String accessToken,

        @Schema(description = "过期时刻（RFC3339 UTC）", example = "2026-08-01T09:15:00Z")
        Instant expiresAt) {
}
