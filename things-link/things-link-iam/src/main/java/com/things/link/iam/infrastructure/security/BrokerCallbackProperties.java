package com.things.link.iam.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Broker → 应用的回调鉴权配置（架构文档 8.3，S3.5-1）。
 *
 * @param secret 共享密钥。EMQX 在每个回调请求上带 {@code Authorization: Bearer <secret>}，
 *               应用逐字节比对。<b>生产必须从环境变量注入</b>，配置文件里的值会随代码进版本库。
 *               长度下限由 {@link BrokerCallbackAuthenticationFilter} 在启动时强制
 */
@ConfigurationProperties(prefix = "things-link.security.broker-callback")
public record BrokerCallbackProperties(String secret) {
}
