package com.things.link.iam.infrastructure.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.things.link.iam.application.SessionProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * JWT 的签发与校验装配。
 *
 * <h2>为什么用对称密钥（HMAC）而不是 RSA</h2>
 * 当前签发方与校验方是同一个服务，对称密钥足够且省去密钥对管理。
 * 等到设备接入层等其他服务也要独立校验令牌时（S3+），再换成 RSA/EC ——
 * 那时校验方只需要公钥，不必持有能伪造令牌的私钥。换算法只影响本类。
 *
 * <h2>为什么走标准的 JwtEncoder / JwtDecoder</h2>
 * 自己拼接与解析 JWT 是最容易出安全漏洞的地方之一：算法混淆（把 RS256 令牌
 * 当 HS256 用公钥验签）、忘记校验过期、忘记校验签名，都曾是真实的 CVE。
 * 标准实现默认做对了这些。
 */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, SessionProperties.class, BrokerCallbackProperties.class})
public class JwtConfiguration {

    /**
     * HMAC-SHA256 要求密钥长度不短于 256 位（32 字节）。
     *
     * <p>短于此长度时 Nimbus 会直接拒绝，但报错信息不易理解，
     * 因此在这里显式检查并给出可操作的提示。
     */
    private static final int MIN_SECRET_BYTES = 32;

    private final JwtProperties properties;

    public JwtConfiguration(JwtProperties properties) {
        this.properties = properties;
    }

    /**
     * 口令编码器。
     *
     * <p>{@code DelegatingPasswordEncoder} 按哈希前缀分发校验，因此库里可以同时
     * 存在 {@code {bcrypt}} 与 {@code {argon2}} 两种哈希 —— 将来换算法不需要
     * 强制全员重置密码（ADR 0009）。
     *
     * @return 口令编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * 签名密钥。
     *
     * @return HMAC 密钥
     * @throws IllegalStateException 密钥缺失或过短
     */
    @Bean
    public SecretKeySpec jwtSecretKey() {
        String secret = properties.secret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "缺少 things-link.security.jwt.secret。生产环境必须从环境变量注入，"
                            + "不要写进配置文件 —— 它会随代码进版本库。");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "things-link.security.jwt.secret 至少需要 %d 字节（当前 %d）。"
                            .formatted(MIN_SECRET_BYTES, keyBytes.length)
                            + "HMAC-SHA256 的安全性直接取决于密钥长度，短密钥可被暴力破解。");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    /**
     * 令牌签发器。
     *
     * @param key 签名密钥
     * @return JwtEncoder
     */
    @Bean
    public JwtEncoder jwtEncoder(@Qualifier("jwtSecretKey") SecretKeySpec key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    /**
     * 令牌校验器。
     *
     * <p>显式指定 {@link MacAlgorithm#HS256}：不限定算法会让解码器接受令牌头里
     * 声明的任意算法，这正是算法混淆攻击的入口。
     *
     * @param key 签名密钥
     * @return JwtDecoder
     */
    @Bean
    public JwtDecoder jwtDecoder(@Qualifier("jwtSecretKey") SecretKeySpec key) {
        return NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

}
