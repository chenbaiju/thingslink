package com.things.link.ingestion.infrastructure.protocol.http;

import com.things.link.ingestion.application.access.DeviceAccessAuthBudget;
import com.things.link.ingestion.application.access.DeviceAccessDeviceAuthenticator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 设备面 HTTPS 接入的安全链：与管理 API（{@code /api/v1/**}）和 Broker 回调（{@code /api/v1/emqx/**}）分离。
 *
 * <p>分离不是洁癖：管理面用 JWT、Broker 回调共用密钥，而设备面用一机一密凭据。三条链共用一条过滤器链会让
 * 「设备令牌被当成无效 Bearer」或「管理面请求进入设备认证」这类串线在排障时看起来像凭据坏了。这里用
 * {@code securityMatcher} 独占 {@code /device-access/**}，并显式不装配 JWT 资源服务器；设备认证由自己的
 * 过滤器完成，未认证请求在读业务代码之前就被拒。</p>
 *
 * <p>整条链由 {@code things-link.access.http.enabled} 控制：未开启该协议的实例不注册这些端点，符合接入合同
 * §8.4「未开启某协议的实例不得接受该协议的连接」。</p>
 */
@ConditionalOnProperty(name = "things-link.deployment.role",
        havingValue = "device-access", matchIfMissing = true)
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "things-link.access.http", name = "enabled", havingValue = "true")
public class DeviceAccessHttpSecurityConfiguration {

    /** 设备面根路径，与管理面、Broker 回调路径互不包含。 */
    public static final String DEVICE_ACCESS_ROOT = "/device-access/**";

    /**
     * 创建设备面安全链。
     *
     * @param http Spring Security 构建器
     * @param authenticator 设备认证端口
     * @param authBudget 认证面预算
     * @param allowInsecureLoopback 仅测试：允许非 TLS 回环请求
     * @return 只匹配设备路径的安全链
     * @throws Exception 装配失败
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain deviceAccessSecurityFilterChain(
            HttpSecurity http,
            DeviceAccessDeviceAuthenticator authenticator,
            DeviceAccessAuthBudget authBudget,
            @Value("${things-link.access.http.allow-insecure-loopback:false}") boolean allowInsecureLoopback)
            throws Exception {
        return http
                .securityMatcher(DEVICE_ACCESS_ROOT)
                // 设备面没有 Cookie 会话，CSRF 令牌无处存放也无意义；凭据在请求头里逐次校验。
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.OPTIONS, DEVICE_ACCESS_ROOT).permitAll()
                        .anyRequest().permitAll())
                .addFilterBefore(new DeviceAccessHttpAuthenticationFilter(authenticator, authBudget,
                        allowInsecureLoopback), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
