package com.things.link.rule.infrastructure.sandbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 只注册沙箱配置属性；S8-1A 不装配 Web、数据库或消息入口。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ScriptSandboxProperties.class)
public class RuleSandboxConfiguration {
}
