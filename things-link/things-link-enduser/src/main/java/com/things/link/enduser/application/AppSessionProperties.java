package com.things.link.enduser.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 终端用户（App）会话配置（ADR 0036）。
 *
 * <p>与控制台 {@code SessionProperties} 分开：App 刷新令牌走响应体（移动端安全存储），
 * 没有 Cookie，因此会话级只剩刷新令牌有效期一项。访问令牌签发配置在 infrastructure 层的
 * {@code AppJwtProperties}，拆分方式与 iam 的 SessionProperties / JwtProperties 同构 ——
 * 应用层只消费它需要的 TTL，不接触 JWT 实现细节。
 *
 * @param refreshTokenTtl 刷新令牌有效期。它是「多久不用就必须重新登录」的上限。
 *                        移动端典型为 30 天，长于控制台的 7 天 —— App 重登摩擦更高，
 *                        且令牌存系统安全存储，泄露面小于浏览器 Cookie
 */
@ConfigurationProperties(prefix = "things-link.security.app-session")
public record AppSessionProperties(Duration refreshTokenTtl) {

    public AppSessionProperties {
        refreshTokenTtl = refreshTokenTtl == null ? Duration.ofDays(30) : refreshTokenTtl;
    }

}
