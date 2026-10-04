package com.things.link.iam.application;

import java.time.Instant;

/**
 * 一次登录或刷新的产物：一对令牌。
 *
 * <p>两者的<b>投递方式不同</b>，这不是实现细节而是安全设计（决策 #8 / ADR 0010）：
 * <ul>
 *   <li>访问令牌走响应体，由前端存在内存里，随页面刷新丢失</li>
 *   <li>刷新令牌走 HttpOnly Cookie，JavaScript 读不到，因此 XSS 偷不走</li>
 * </ul>
 * 所以 {@code refreshToken} 的明文虽然出现在本类型里，但只允许交给写 Cookie 的那一处
 * （{@code RefreshTokenCookie}），<b>绝不能进响应体、日志或任何可被脚本读到的地方</b>。
 *
 * @param accessToken       访问令牌
 * @param refreshToken      刷新令牌明文。服务端此后只保存它的哈希，这是它唯一一次出现
 * @param refreshExpiresAt  刷新令牌过期时刻，用于设置 Cookie 的 Max-Age
 */
public record IssuedSession(
        AccessToken accessToken,
        String refreshToken,
        Instant refreshExpiresAt) {

    /**
     * 屏蔽刷新令牌明文。
     *
     * <p>record 的默认实现会打印全部字段，一处 {@code log.debug("{}", session)}
     * 就把一个长期有效的凭据写进了日志 —— 而日志通常比数据库更容易被读到，
     * 也更少被当成敏感数据对待。{@code LoginCommand} 出于同样的理由重写了它。
     */
    @Override
    public String toString() {
        return "IssuedSession[accessToken=***, refreshToken=***, refreshExpiresAt="
                + refreshExpiresAt + "]";
    }

}
