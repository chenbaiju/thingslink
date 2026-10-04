package com.things.link.telemetry.infrastructure.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 项目概要派生缓存配置。 */
@ConfigurationProperties(prefix = "things-link.overview")
public class OverviewCacheProperties {
    /** 30 秒在概要新鲜度与高频聚合成本间取平衡，允许配置但必须保持短 TTL。 */
    private Duration cacheTtl = Duration.ofSeconds(30);

    /** @return 概要缓存 TTL */
    public Duration getCacheTtl() {
        return cacheTtl;
    }

    /**
     * @param cacheTtl 概要缓存 TTL，必须位于 1 秒到 5 分钟
     */
    public void setCacheTtl(Duration cacheTtl) {
        if (cacheTtl == null || cacheTtl.compareTo(Duration.ofSeconds(1)) < 0
                || cacheTtl.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("概要缓存 TTL 必须在 1 秒到 5 分钟之间");
        }
        this.cacheTtl = cacheTtl;
    }
}
