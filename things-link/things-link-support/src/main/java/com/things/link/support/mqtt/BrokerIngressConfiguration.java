package com.things.link.support.mqtt;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 统一注册 Broker ingress 环境绑定与首订阅就绪状态，供认证和消费模块共享同一实例。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BrokerIngressProperties.class)
public class BrokerIngressConfiguration {
    /** @return 应用进程内唯一的 ingress 就绪状态 */
    @Bean
    public BrokerIngressReadiness brokerIngressReadiness() {
        return new BrokerIngressReadiness();
    }
}
