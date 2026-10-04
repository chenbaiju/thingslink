package com.things.link.enduser.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * App 访问令牌（JWT）签发配置（ADR 0036）。
 *
 * <p>与控制台 {@code JwtProperties} 各自独立：App 令牌用独立的 HMAC 密钥与 issuer，
 * 实现两类令牌的密码学互斥 —— 控制台 decoder 对 App 令牌签名校验失败，反之亦然。
 * 若复用同一密钥，互斥就退化成「靠 issuer 字符串区分」，而 issuer 只是 JWT 里的
 * 一条可被伪造方任意填写的声明，不是安全边界。
 *
 * @param secret         HMAC 签名密钥。<b>生产必须从环境变量注入</b>，绝不能沿用
 *                       application.yml 里的开发默认值 —— 密钥泄露等于任何人都能
 *                       伪造任意终端用户的令牌
 * @param accessTokenTtl 访问令牌有效期。短时有效（默认 15 分钟）的意义：令牌无法撤销，
 *                       有效期就是账号停用后仍能访问的最长窗口。App 端「停用」的立即生效
 *                       靠刷新时的回库复验（{@code AppSessionService}），不是靠缩短令牌
 * @param issuer         签发者标识，写入 {@code iss} 声明，与控制台 issuer 不同
 */
@ConfigurationProperties(prefix = "things-link.security.app-jwt")
public record AppJwtProperties(
        String secret,
        Duration accessTokenTtl,
        String issuer) {

    public AppJwtProperties {
        accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(15) : accessTokenTtl;
        issuer = issuer == null || issuer.isBlank() ? "things-link-app" : issuer;
    }

}
