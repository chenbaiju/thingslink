package com.things.link.ingestion.infrastructure;

import com.things.link.support.mqtt.BrokerIngressConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 注册 生产环境 MQTT 接入 所需的 Broker URI 与服务身份环境绑定。 */
@Configuration(proxyBeanMethods = false)
@Import(BrokerIngressConfiguration.class)
@EnableConfigurationProperties(BrokerHandoffQualificationProperties.class)
public class BrokerHandoffConfiguration {
}
