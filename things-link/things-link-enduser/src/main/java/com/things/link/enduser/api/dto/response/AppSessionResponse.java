package com.things.link.enduser.api.dto.response;

import com.things.link.enduser.application.AppIssuedSession;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * App 会话响应（登录 / 刷新）。
 *
 * <p>与控制台 {@code LoginResponse} 的差异：<b>刷新令牌也在响应体里</b> —— App 没有
 * Cookie，令牌必须明文返回给客户端存安全存储（Keychain / Keystore）。因此这个类型携带
 * 长期凭据，绝不能进日志，见 {@link #toString()}。
 *
 * @param accessToken      访问令牌，后续请求放 {@code Authorization: Bearer} 头
 * @param refreshToken     刷新令牌明文。服务端此后只保存它的哈希，这是它唯一一次出现
 * @param accessExpiresAt  访问令牌过期时刻（RFC3339 UTC）
 * @param refreshExpiresAt 刷新令牌过期时刻（RFC3339 UTC）
 */
@Schema(description = "App 会话响应")
public record AppSessionResponse(

        @Schema(description = "访问令牌，后续请求放在 Authorization: Bearer 头中")
        String accessToken,

        @Schema(description = "刷新令牌，存客户端安全存储（Keychain / Keystore）")
        String refreshToken,

        @Schema(description = "访问令牌过期时刻（RFC3339 UTC）", example = "2026-08-01T09:15:00Z")
        Instant accessExpiresAt,

        @Schema(description = "刷新令牌过期时刻（RFC3339 UTC）", example = "2026-09-01T09:15:00Z")
        Instant refreshExpiresAt,

        @Schema(description = "服务器签发的会话关联，安装绑定不可自报身份")
        com.things.link.enduser.application.AppSessionIdentity identity) {

    /**
     * 从应用层会话结果构造响应。
     *
     * @param session 应用层会话结果
     * @return 响应体
     */
    public static AppSessionResponse from(AppIssuedSession session) {
        return new AppSessionResponse(
                session.accessToken().value(),
                session.refreshToken(),
                session.accessToken().expiresAt(),
                session.refreshExpiresAt(), session.identity());
    }

    /**
     * 屏蔽两类令牌明文。
     *
     * <p>本类型同时持有访问令牌与刷新令牌，是最不该进日志的对象。任何
     * {@code log.debug("{}", response)} 都会把一对长期凭据写进日志文件。
     */
    @Override
    public String toString() {
        return "AppSessionResponse[accessToken=***, refreshToken=***, accessExpiresAt="
                + accessExpiresAt + ", refreshExpiresAt=" + refreshExpiresAt + "]";
    }

}
