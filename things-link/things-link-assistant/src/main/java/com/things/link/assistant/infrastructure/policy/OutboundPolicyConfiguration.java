package com.things.link.assistant.infrastructure.policy;

import com.things.link.assistant.application.OutboundPropertyPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

/** 服务端受控配置，缺省不批准任何属性；无热更新或推断规则。 */
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(OutboundPolicyConfiguration.Properties.class)
public class OutboundPolicyConfiguration {
    @ConfigurationProperties(prefix="things-link.assistant.outbound",ignoreUnknownFields=false)
    public static class Properties {
        private List<OutboundPropertyPolicy.Binding> bindings = List.of();
        public List<OutboundPropertyPolicy.Binding> getBindings() { return bindings; }
        public void setBindings(List<OutboundPropertyPolicy.Binding> value) { bindings = List.copyOf(value); }
        @Override public String toString() { return "OutboundPolicyProperties[REDACTED]"; }
    }
    @Bean OutboundPropertyPolicy outboundPropertyPolicy(Properties properties) {
        return new OutboundPropertyPolicy(properties.getBindings());
    }
}
