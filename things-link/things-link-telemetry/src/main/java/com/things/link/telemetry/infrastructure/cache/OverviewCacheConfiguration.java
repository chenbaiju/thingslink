package com.things.link.telemetry.infrastructure.cache;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 装配概要缓存配置属性，避免把技术配置放进启动模块。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OverviewCacheProperties.class)
public class OverviewCacheConfiguration {
}
