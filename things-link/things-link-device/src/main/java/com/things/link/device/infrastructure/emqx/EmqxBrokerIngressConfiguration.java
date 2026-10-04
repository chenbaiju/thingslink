package com.things.link.device.infrastructure.emqx;

import com.things.link.support.mqtt.BrokerIngressConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 注册设备认证/ACL 所需的 durable ingress 服务身份环境绑定。 */
@Configuration(proxyBeanMethods = false)
@Import(BrokerIngressConfiguration.class)
public class EmqxBrokerIngressConfiguration {
}
