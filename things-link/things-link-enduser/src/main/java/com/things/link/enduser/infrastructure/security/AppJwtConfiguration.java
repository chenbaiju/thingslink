package com.things.link.enduser.infrastructure.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.things.link.enduser.application.AppSessionProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * App JWT 的签发与校验装配（ADR 0036）。
 *
 * <h2>与控制台 {@code JwtConfiguration} 的 bean 必须重名错开</h2>
 * 两个模块同在一个应用上下文里（bootstrap 扫描 {@code com.things.link} 全树），
 * 控制台已有 {@code jwtSecretKey}/{@code jwtEncoder}/{@code jwtDecoder} 三个 bean。
 * Boot 4 默认禁用 bean 覆盖，重名会在启动时报「A bean with that name has already been
 * defined」。因此这里全部加 {@code appJwt} 前缀，注入处用 {@link Qualifier} 显式点名。
 *
 * <p><b>不</b>重新声明 {@code passwordEncoder}：enduser 复用 iam 提供的
 * {@code DelegatingPasswordEncoder}（随 starter-security 传递），App 与控制台口令哈希
 * 同构，改算法只动一处。
 *
 * <p>对称密钥（HMAC）而非 RSA 的理由、以及为什么走标准 JwtEncoder/JwtDecoder，均与控制台
 * 一致 —— 见 {@code JwtConfiguration} 类注释。
 */
@Configuration
@EnableConfigurationProperties({AppJwtProperties.class, AppSessionProperties.class})
public class AppJwtConfiguration {

    /**
     * HMAC-SHA256 要求密钥长度不短于 256 位（32 字节）。
     *
     * <p>短于此长度时 Nimbus 会直接拒绝，但报错信息不易理解，
     * 因此在这里显式检查并给出可操作的提示。
     */
    private static final int MIN_SECRET_BYTES = 32;

    private final AppJwtProperties properties;

    public AppJwtConfiguration(AppJwtProperties properties) {
        this.properties = properties;
    }

    /**
     * App 签名密钥。
     *
     * @return HMAC 密钥
     * @throws IllegalStateException 密钥缺失或过短
     */
    @Bean
    public SecretKeySpec appJwtSecretKey() {
        String secret = properties.secret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "缺少 things-link.security.app-jwt.secret。生产环境必须从环境变量注入，"
                            + "不要写进配置文件 —— 它会随代码进版本库。");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "things-link.security.app-jwt.secret 至少需要 %d 字节（当前 %d）。"
                            .formatted(MIN_SECRET_BYTES, keyBytes.length)
                            + "HMAC-SHA256 的安全性直接取决于密钥长度，短密钥可被暴力破解。");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    /**
     * App 令牌签发器。
     *
     * @param key App 签名密钥
     * @return JwtEncoder
     */
    @Bean
    public JwtEncoder appJwtEncoder(@Qualifier("appJwtSecretKey") SecretKeySpec key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    /**
     * App 令牌校验器。
     *
     * <p>显式指定 {@link MacAlgorithm#HS256}：不限定算法会让解码器接受令牌头里声明的
     * 任意算法，这正是算法混淆攻击的入口。密钥与控制台不同，因此两类令牌在签名校验
     * 阶段即互相拒绝（ADR 0036 的双向互斥）。
     *
     * @param key App 签名密钥
     * @return JwtDecoder
     */
    @Bean
    public JwtDecoder appJwtDecoder(@Qualifier("appJwtSecretKey") SecretKeySpec key) {
        return NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

}
