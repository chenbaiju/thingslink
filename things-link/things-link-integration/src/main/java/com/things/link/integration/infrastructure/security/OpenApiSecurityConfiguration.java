package com.things.link.integration.infrastructure.security;

import com.things.link.integration.application.ApiKeyAuthenticationService;
import com.things.link.integration.application.OpenApiRoute;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import tools.jackson.databind.ObjectMapper;
import java.util.List;

/** 与Console、App、设备链互斥；无JWT解码器、无session，未注册路由一律拒绝。 */
@Configuration(proxyBeanMethods=false)
public class OpenApiSecurityConfiguration {
    @Bean @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain openApiSecurityFilterChain(HttpSecurity http,ApiKeyAuthenticationService authentication,
            org.springframework.beans.factory.ObjectProvider<OpenApiRoute> routes,ObjectMapper json, com.things.link.project.application.ProjectRestQuotaAdmission quotas,
            @Value("${things-link.security.session.cookie-name:tc_refresh}") String consoleCookie)throws Exception{
        return http.securityMatcher(request->{String path=request.getRequestURI().substring(request.getContextPath().length());
                    return !path.equals("/api/open/v1/realtime/ws")&&(path.equals("/api/open/v1")||path.startsWith("/api/open/v1/"));})
                .csrf(csrf->csrf.disable())
                .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache->cache.disable())
                .authorizeHttpRequests(auth->auth.anyRequest().authenticated())
                .addFilterBefore(new OpenApiKeyFilter(authentication,routes.orderedStream().toList(),json,consoleCookie,quotas),AuthorizationFilter.class)
                .build();
    }
}
