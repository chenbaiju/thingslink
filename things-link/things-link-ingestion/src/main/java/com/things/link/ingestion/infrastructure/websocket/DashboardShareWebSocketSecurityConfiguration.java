package com.things.link.ingestion.infrastructure.websocket;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** 分享WS独立namespace链早于Console兜底；不读取Cookie/Authorization、不签发Session。 */
@Configuration(proxyBeanMethods = false)
public class DashboardShareWebSocketSecurityConfiguration {
    /** 精确GET交给真实capability握手，其余namespace路径与方法全部拒绝。 */
    @Bean @Order(Ordered.HIGHEST_PRECEDENCE+2)
    public SecurityFilterChain dashboardShareWebSocketSecurityFilterChain(HttpSecurity http)throws Exception {
        return http.securityMatcher(request->{String path = path(request);return path.equals("/ws/shares") || path.startsWith("/ws/shares/");})
                .csrf(value->value.disable()).cors(value->value.disable()).httpBasic(value->value.disable())
                .formLogin(value->value.disable()).logout(value->value.disable()).requestCache(value->value.disable())
                .securityContext(value->value.disable()).sessionManagement(value->value.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(value->value.requestMatchers(request->DashboardShareHandshakeInterceptor.matches(request.getMethod(), path(request)))
                        .permitAll().anyRequest().denyAll())
                .addFilterBefore(new NoStore(), AuthorizationFilter.class).build();
    }
    /** 部署contextPath不属于业务路由。 */
    private static String path(HttpServletRequest request){return request.getRequestURI().substring(request.getContextPath().length());}
    /** 包括403邻接拒绝均no-store，不产生凭据相关缓存或CORS头。 */
    private static final class NoStore extends OncePerRequestFilter {
        /** 先写no-store再交安全链，拒绝也不能漏掉。 */
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws IOException, ServletException{response.setHeader("Cache-Control","no-store");chain.doFilter(request, response);}
    }
}
