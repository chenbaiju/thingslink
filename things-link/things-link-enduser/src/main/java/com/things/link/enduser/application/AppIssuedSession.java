package com.things.link.enduser.application;

import java.time.Instant;

/**
 * 一次 App 登录或刷新的产物：一对令牌。
 *
 * <p>与控制台不同，App 刷新令牌<b>走响应体</b>而非 HttpOnly Cookie：目标客户端是移动端，
 * 令牌存系统安全存储（Keychain / Keystore），没有浏览器 Cookie 机制可用（ADR 0036）。
 * 因此 {@code refreshToken} 明文会出现在本类型里，交由控制器写进响应体 —— 但仍<b>绝不能
 * 进日志或任何持久化以外的落盘</b>。
 *
 * @param accessToken      访问令牌
 * @param refreshToken     刷新令牌明文。服务端此后只保存它的哈希，这是它唯一一次出现
 * @param refreshExpiresAt 刷新令牌过期时刻
 */
public record AppIssuedSession(
        AppAccessToken accessToken,
        String refreshToken,
        Instant refreshExpiresAt, AppSessionIdentity identity) {

    /** 兼容内部旧测试与适配器；缺少身份的会话不能注册安装。 */
    public AppIssuedSession(AppAccessToken accessToken, String refreshToken, Instant refreshExpiresAt) {
        this(accessToken, refreshToken, refreshExpiresAt, null);
    }

    /**
     * 屏蔽两类令牌明文。
     *
     * <p>record 的默认实现会打印全部字段，一处 {@code log.debug("{}", session)} 就把一个
     * 长期有效的凭据写进了日志 —— 而日志通常比数据库更容易被读到。
     */
    @Override
    public String toString() {
        return "AppIssuedSession[accessToken=***, refreshToken=***, refreshExpiresAt="
                + refreshExpiresAt + "]";
    }

}
