package com.things.link.ingestion.infrastructure.websocket;

import com.things.link.enduser.application.AppRealtimeAccessService;
import com.things.link.ingestion.application.DashboardRealtimeRegistry;
import com.things.link.project.application.AccountDirectory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** ADR0099的新端点装配，与旧Console设备详情协议独立。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DashboardWebSocketProperties.class)
public class DashboardWebSocketConfiguration implements WebSocketConfigurer {
    /** 共享有界失效提示核心。 */
    private final DashboardRealtimeRegistry registry;
    /** 严格App身份握手。 */
    private final DashboardHandshakeInterceptor appHandshake;
    /** ADR0109独立协议复用App可信Origin和身份配置。 */
    private final DashboardHandshakeInterceptor appDashboardHandshake;
    /** 仅在握手确认账号有效性的Console身份握手。 */
    private final DashboardHandshakeInterceptor consoleHandshake;
    /** 两个宿主分别配置精确来源。 */
    private final DashboardWebSocketProperties properties;

    /** 按bean名字绑定独立decoder，避免导入其他模块infrastructure类型。 */
    public DashboardWebSocketConfiguration(DashboardRealtimeRegistry registry,
            @Qualifier("appJwtDecoder") JwtDecoder appDecoder,
            @Qualifier("jwtDecoder") JwtDecoder consoleDecoder,
            @Value("${things-link.security.app-jwt.issuer}") String appIssuer,
            @Value("${things-link.security.jwt.issuer}") String consoleIssuer,
            AppRealtimeAccessService appAccess, AccountDirectory accounts, DashboardWebSocketProperties properties) {
        this.registry = registry;
        this.properties = properties;
        this.appHandshake = new DashboardHandshakeInterceptor(true, appDecoder, appIssuer,
                properties.appAllowedOrigins(), appAccess, accounts);
        this.appDashboardHandshake = new DashboardHandshakeInterceptor(true, appDecoder, appIssuer,
                properties.appAllowedOrigins(), appAccess, accounts, DashboardHandshakeInterceptor.APP_DASHBOARD_PROTOCOL);
        this.consoleHandshake = new DashboardHandshakeInterceptor(false, consoleDecoder, consoleIssuer,
                properties.consoleAllowedOrigins(), appAccess, accounts);
    }

    /** 精确路径与精确Origin双重注册，不给/ws/**增加兜底匿名入口。 */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry handlers) {
        handlers.addHandler(new DashboardWebSocketHandler(registry), "/ws/app/properties")
                .addInterceptors(appHandshake)
                .setHandshakeHandler(new DashboardHandshakeHandler(DashboardHandshakeInterceptor.APP_PROTOCOL))
                .setAllowedOrigins(properties.appAllowedOrigins().toArray(String[]::new));
        handlers.addHandler(new DashboardWebSocketHandler(registry, true), "/ws/app/dashboard")
                .addInterceptors(appDashboardHandshake)
                .setHandshakeHandler(new DashboardHandshakeHandler(DashboardHandshakeInterceptor.APP_DASHBOARD_PROTOCOL))
                .setAllowedOrigins(properties.appAllowedOrigins().toArray(String[]::new));
        handlers.addHandler(new DashboardWebSocketHandler(registry), "/ws/dashboard/properties")
                .addInterceptors(consoleHandshake)
                .setHandshakeHandler(new DashboardHandshakeHandler(DashboardHandshakeInterceptor.CONSOLE_PROTOCOL))
                .setAllowedOrigins(properties.consoleAllowedOrigins().toArray(String[]::new));
    }

    /**
     * 描述原生 HTTP 升级入口，生产关闭文档时不创建此文档 Bean。
     *
     * @return 只维护契约、不注册运行路由的文档修正器
     */
    @org.springframework.context.annotation.Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "springdoc.api-docs.enabled", havingValue = "true", matchIfMissing = true)
    public org.springdoc.core.customizers.OpenApiCustomizer dashboardHandshakeDocumentation() {
        return api -> {
            com.things.link.support.openapi.HttpTransportOpenApiDocumentation.webSocket(api,
                    "/ws/app/properties", "upgradeAppProperties", "建立 App 属性实时连接",
                    "必须提供精确允许的 Origin；使用 App JWT 并校验当前绑定设备资格。",
                    "发送 tc.app.properties.v1 与 bearer.<App JWT>", true);
            com.things.link.support.openapi.HttpTransportOpenApiDocumentation.webSocket(api,
                    "/ws/app/dashboard", "upgradeAppDashboard", "建立 App 看板实时连接",
                    "必须提供精确允许的 Origin；使用 App JWT，按看板协议重新校验授权。",
                    "发送 tc.app.dashboard.v2 与 bearer.<App JWT>", true);
            com.things.link.support.openapi.HttpTransportOpenApiDocumentation.webSocket(api,
                    "/ws/dashboard/properties", "upgradeConsoleDashboard", "建立控制台看板实时连接",
                    "必须提供精确允许的 Origin；使用控制台 JWT 和当前项目成员身份。",
                    "发送 tc.dashboard.properties.v1 与 bearer.<控制台JWT>", true);
        };
    }
}
