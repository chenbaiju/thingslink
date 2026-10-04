package com.things.link.iam.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT 签发配置。
 *
 * @param secret          HMAC 签名密钥。<b>生产必须从环境变量注入</b>，
 *                        绝不能用配置文件里的开发默认值 —— 密钥泄露等于任何人都能
 *                        伪造任意用户的令牌。长度下限见 {@link JwtConfiguration}
 * @param accessTokenTtl  访问令牌有效期。架构文档 7.3 要求「短时有效」：
 *                        令牌一旦签发就无法撤销（无状态校验不查库），所以有效期
 *                        就是账号被停用后仍能访问的最长时间窗口
 * @param issuer          签发者标识，写入 {@code iss} 声明
 */
@ConfigurationProperties(prefix = "things-link.security.jwt")
public record JwtProperties(
        String secret,
        Duration accessTokenTtl,
        String issuer) {

    /**
     * 紧凑构造器：为可选项提供默认值。
     */
    public JwtProperties {
        accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(15) : accessTokenTtl;
        issuer = issuer == null || issuer.isBlank() ? "things-link" : issuer;
    }

}
