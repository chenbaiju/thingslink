package com.things.link.dashboard.infrastructure.security;

import com.things.link.dashboard.application.DashboardShareRuntimeService;
import com.things.link.dashboard.application.sharing.DashboardShareProtectionService;
import com.things.link.dashboard.application.sharing.DashboardShareRuntimeProperties;
import com.things.link.dashboard.application.sharing.DashboardShareSecurityEvents;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import tools.jackson.databind.ObjectMapper;

/** ADR0101：匿名capability完全独立安全链，不继承Console CORS/JWT/Session或App角色。 */
@Configuration(proxyBeanMethods = false)
public class DashboardShareSecurityConfiguration {
    /** namespace所有邻接路由先被本链捕获；只有已实施的七条GET/POST精确开放且在自有过滤器内验capability。 */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public SecurityFilterChain dashboardShareSecurityFilterChain(HttpSecurity http,
            DashboardShareRuntimeService runtime, DashboardShareProtectionService protection,
            DashboardShareRuntimeProperties properties, DashboardShareSecurityEvents events, ObjectMapper mapper) throws Exception {
        DashboardShareAuthenticationFilter filter = new DashboardShareAuthenticationFilter(
                runtime, protection, properties, events, mapper);
        return http.securityMatcher(request -> {
                    String path = request.getRequestURI().substring(request.getContextPath().length());
                    return path.equals("/api/v1/shares") || path.startsWith("/api/v1/shares/");
                })
                // 无Cookie鉴权，也不开CORS；浏览器同源带来的Cookie不读取、不落Session。
                .csrf(value -> value.disable()).cors(value -> value.disable())
                .httpBasic(value -> value.disable()).formLogin(value -> value.disable())
                .logout(value -> value.disable()).requestCache(value -> value.disable())
                .securityContext(value -> value.disable())
                .sessionManagement(value -> value.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(value -> value
                        .requestMatchers(DashboardShareAuthenticationFilter::isReadableRoute).permitAll()
                        .anyRequest().denyAll())
                // 过滤器不是Servlet Bean，只在这一条链运行一次；身份保存在请求属性而非账户上下文。
                .addFilterBefore(filter, AuthorizationFilter.class)
                .build();
    }
}
