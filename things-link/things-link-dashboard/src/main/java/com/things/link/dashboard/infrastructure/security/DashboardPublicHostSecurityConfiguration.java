package com.things.link.dashboard.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** 公开静态文件独立无状态链，邻接方法拒绝，不消费Console或App身份。 */
@Configuration(proxyBeanMethods = false)
public class DashboardPublicHostSecurityConfiguration {
    /** @param http 本链构造器 @return 仅GET/HEAD可进入白名单控制器 @throws Exception 安全配置失败 */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 4)
    public SecurityFilterChain dashboardPublicHostSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher(request -> {
                    String path = request.getRequestURI().substring(request.getContextPath().length());
                    return path.equals("/app") || path.startsWith("/app/");
                })
                .csrf(value -> value.disable()).cors(value -> value.disable())
                .httpBasic(value -> value.disable()).formLogin(value -> value.disable()).logout(value -> value.disable())
                .requestCache(value -> value.disable()).securityContext(value -> value.disable())
                .sessionManagement(value -> value.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(value -> value.requestMatchers(HttpMethod.GET, "/app", "/app/**").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/app", "/app/**").permitAll().anyRequest().denyAll())
                .exceptionHandling(value -> value.authenticationEntryPoint((request, response, failure) -> {
                    response.setHeader("Cache-Control", "no-store"); response.setStatus(403);
                }).accessDeniedHandler((request, response, failure) -> {
                    response.setHeader("Cache-Control", "no-store"); response.setStatus(403);
                })).build();
    }
}
