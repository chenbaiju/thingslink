package com.things.link.access;

import com.things.link.iam.infrastructure.security.BrokerCallbackAuthenticationFilter;
import com.things.link.iam.infrastructure.security.BrokerCallbackProperties;
import com.things.link.support.observability.DataPlaneMetrics;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import tools.jackson.databind.ObjectMapper;

/** Broker 精确回调和设备动态注册的独立安全链；其他路径默认拒绝。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BrokerCallbackProperties.class)
public class AccessBrokerSecurityConfiguration {

    @Bean
    PasswordEncoder accessPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    SecurityFilterChain accessBrokerSecurityFilterChain(HttpSecurity http,
            BrokerCallbackProperties properties, ObjectMapper mapper, DataPlaneMetrics metrics) throws Exception {
        String[] callbacks = BrokerCallbackAuthenticationFilter.protectedPaths().toArray(String[]::new);
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(callbacks).permitAll()
                        .requestMatchers("/api/v1/emqx/register", "/actuator/health", "/actuator/prometheus")
                        .permitAll()
                        .anyRequest().denyAll())
                .addFilterBefore(new BrokerCallbackAuthenticationFilter(properties, mapper, metrics),
                        AuthorizationFilter.class)
                .build();
    }
}
