package com.things.link.enduser.infrastructure.security;

import com.things.link.enduser.application.PushTokenCipher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** 装配 ADR 0051 的 PUSH token 加密边界。 */
@Configuration
@EnableConfigurationProperties({PushTokenEncryptionProperties.class, com.things.link.enduser.application.PushInstallationConfiguration.class})
public class PushTokenEncryptionConfiguration {

    /** 仓库公开的 32 字节开发 key；生产 profile 不能携带它，即使它不是 active key。 */
    static final String DEVELOPMENT_KEY = "ZGV2LW9ubHktcHVzaC10b2tlbi1rZXktMzJieXRlcyE=";

    /**
     * 校验完整密钥环并创建加密器；配置错误在启动期暴露。
     *
     * @param properties 版本化密钥环
     * @param environment 当前 Spring profile 环境
     * @return AES-GCM 加密端口
     */
    @Bean
    public PushTokenCipher pushTokenCipher(PushTokenEncryptionProperties properties, Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod", "production"))
                && properties.keys() != null
                && properties.keys().containsValue(DEVELOPMENT_KEY)) {
            throw new IllegalStateException("生产环境禁止使用仓库公开的 PUSH token 开发密钥");
        }
        return AesGcmPushTokenCipher.from(properties);
    }
}
