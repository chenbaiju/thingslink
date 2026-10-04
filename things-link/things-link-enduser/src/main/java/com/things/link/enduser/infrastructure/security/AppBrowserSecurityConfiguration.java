package com.things.link.enduser.infrastructure.security;

import com.things.link.enduser.application.AppBrowserProperties;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.shared.error.ApiError;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.error.ErrorCode;
import com.things.link.support.trace.TraceContext;
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
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** 浏览器认证独立链；原App最高序链显式排除本namespace，不依赖相同Order的偶然顺序。 */
@Configuration(proxyBeanMethods = false)
public class AppBrowserSecurityConfiguration {
    /** 精确认证路径，不开放同名前缀的任意方法。 */
    private static final Set<String> PATHS = Set.of("/api/v1/app/browser-auth/login", "/api/v1/app/browser-auth/refresh", "/api/v1/app/browser-auth/logout");
    /** namespace内邻接请求也在本链拒绝，不能降到原App身份。 */
    public static boolean namespace(HttpServletRequest request) {
        String path = path(request);
        return path.equals("/api/v1/app/browser-auth") || path.startsWith("/api/v1/app/browser-auth/");
    }
    /** 只有三条POST交给严格Controller，Cookie不成为普通数据API认证方式。 */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 3)
    public SecurityFilterChain appBrowserSecurityFilterChain(HttpSecurity http, AppBrowserProperties properties, ObjectMapper mapper) throws Exception {
        return http.securityMatcher(AppBrowserSecurityConfiguration::namespace)
                .csrf(value -> value.disable()).cors(value -> value.disable()).httpBasic(value -> value.disable())
                .formLogin(value -> value.disable()).logout(value -> value.disable()).requestCache(value -> value.disable())
                .securityContext(value -> value.disable())
                .sessionManagement(value -> value.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(value -> value.requestMatchers(request -> "POST".equals(request.getMethod()) && PATHS.contains(path(request)))
                        .permitAll().anyRequest().denyAll())
                .addFilterBefore(new Boundary(properties, mapper), AuthorizationFilter.class).build();
    }
    /** 不把部署前缀当作业务路径的一部分。 */
    private static String path(HttpServletRequest request) { return request.getRequestURI().substring(request.getContextPath().length()); }

    /** 默认禁用/同源/JSON检查全部先于凭据解析和认证业务，错误均no-store。 */
    static final class Boundary extends OncePerRequestFilter {
        /** 已启动验证的独立配置。 */
        private final AppBrowserProperties properties;
        /** 安全链外仍沿统一公开错误结构。 */
        private final ObjectMapper mapper;
        /** 不注册Servlet Bean，避免链外第二次执行。 */
        Boundary(AppBrowserProperties properties, ObjectMapper mapper) { this.properties = properties; this.mapper = mapper; }
        /** 只检查原始头，不调用parameterMap触发表单解析。 */
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
            response.setHeader("Cache-Control", "no-store");
            ErrorCode denied = null;
            if (!"POST".equals(request.getMethod()) || !PATHS.contains(path(request))) denied = EndUserErrorCode.APP_BROWSER_ORIGIN_FORBIDDEN;
            else if (!properties.enabled()) denied = EndUserErrorCode.APP_BROWSER_UNAVAILABLE;
            else if (!origin(request, properties.origin())) denied = EndUserErrorCode.APP_BROWSER_ORIGIN_FORBIDDEN;
            else {
                List<String> types = Collections.list(request.getHeaders("Content-Type"));
                if (types.size() != 1 || !types.getFirst().matches("(?i)application/json(?:[ \t]*;[ \t]*charset[ \t]*=[ \t]*(?:utf-8|\"utf-8\"))?[ \t]*")
                        || request.getHeader("Content-Encoding") != null || request.getQueryString() != null) denied = CommonErrorCode.INVALID_PARAMETER;
                else if (request.getContentLengthLong() > 8192) denied = CommonErrorCode.PAYLOAD_TOO_LARGE;
            }
            if (denied == null) { chain.doFilter(request, response); return; }
            response.setStatus(denied.httpStatus());
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(mapper.writeValueAsString(new ApiError(denied.code(), denied.defaultMessage(), TraceContext.current(), List.of())));
        }
        /** 合同以scheme/host/有效port精确比较，不接受路径、userinfo、null或重复Origin。 */
        private static boolean origin(HttpServletRequest request, String configured) {
            List<String> values = Collections.list(request.getHeaders("Origin"));
            if (values.size() != 1) return false;
            try {
                URI actual = URI.create(values.getFirst());
                URI expected = URI.create(configured);
                return !values.getFirst().contains(",") && actual.getRawAuthority() != null && !actual.getRawAuthority().endsWith(":")
                        && actual.getPort() >= -1 && actual.getPort() != 0 && actual.getPort() <= 65535
                        && actual.getHost() != null && actual.getRawUserInfo() == null && actual.getRawQuery() == null
                        && actual.getRawFragment() == null && actual.getRawPath().isEmpty()
                        && actual.getScheme().equalsIgnoreCase(expected.getScheme()) && actual.getHost().equalsIgnoreCase(expected.getHost())
                        && port(actual) == port(expected);
            } catch (RuntimeException malformed) { return false; }
        }
        /** 默认端口与显式标准端口为同一个Origin，非法scheme仍由前置比较拒绝。 */
        private static int port(URI value) { return value.getPort() == -1 ? "https".equalsIgnoreCase(value.getScheme()) ? 443 : 80 : value.getPort(); }
    }
}
