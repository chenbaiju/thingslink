package com.things.link.ingestion.infrastructure.websocket;

import com.things.link.dashboard.application.DashboardShareRuntimeService;
import com.things.link.dashboard.application.sharing.DashboardShareProtectionService;
import com.things.link.dashboard.application.sharing.DashboardShareRuntimeProperties;
import com.things.link.dashboard.application.sharing.DashboardShareSecurityEvents;
import com.things.link.ingestion.application.DashboardRealtimeRegistry;
import com.things.link.ingestion.application.DashboardShareConnectionLease;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** 独立分享端点只复用提示核心，绝不挂到App/Console握手与Origin白名单。 */
@Configuration(proxyBeanMethods = false)
public class DashboardShareWebSocketConfiguration implements WebSocketConfigurer {
    /** 共享有界发送核心。 */
    private final DashboardRealtimeRegistry registry;
    /** 单一受管Origin，不添加通配来源。 */
    private final DashboardShareRuntimeProperties properties;
    /** 真实分享能力握手。 */
    private final DashboardShareHandshakeInterceptor handshake;
    /** 两连接租约供传输注册失败清理。 */
    private final DashboardShareConnectionLease leases;
    /** 关闭诊断与握手使用同一固定字段出口。 */
    private final DashboardShareSecurityEvents events;
    /** 明确传入分享公开领域端口，不借ConsoleJWT。 */
    public DashboardShareWebSocketConfiguration(DashboardRealtimeRegistry registry, DashboardShareRuntimeProperties properties,
            DashboardShareRuntimeService runtime, DashboardShareProtectionService protection,
            DashboardShareConnectionLease leases, DashboardShareSecurityEvents events) {
        this.registry = registry;
        this.properties = properties;
        this.leases = leases;
        this.events = events;
        handshake = new DashboardShareHandshakeInterceptor(runtime, protection, properties, leases, events);
    }
    /** wildcard仅匹配单路径段，安全链和拦截器继续验证规范UUID及精确GET。 */
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry handlers) {
        handlers.addHandler(new DashboardShareWebSocketHandler(registry, leases, events),"/ws/shares/*/properties")
                .addInterceptors(handshake)
                .setHandshakeHandler(new DashboardHandshakeHandler(DashboardShareHandshakeInterceptor.PROTOCOL))
                .setAllowedOrigins(properties.enabled()?new String[]{properties.hostOrigin()}:new String[0]);
    }

    /**
     * 描述原生 HTTP 升级入口，生产关闭文档时不创建此文档 Bean。
     *
     * @return 只维护契约、不注册运行路由的文档修正器
     */
    @org.springframework.context.annotation.Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "springdoc.api-docs.enabled", havingValue = "true", matchIfMissing = true)
    public org.springdoc.core.customizers.OpenApiCustomizer shareHandshakeDocumentation() {
        return api -> {
            com.things.link.support.openapi.HttpTransportOpenApiDocumentation.webSocket(api,
                    "/ws/shares/*/properties", "upgradeShareProperties", "建立匿名分享属性实时连接",
                    "默认关闭；启用后必须匹配配置的宿主 Origin、分享能力与连接租约；不借用 Console JWT。",
                    "发送 tc.share.properties.v1 与 share.<分享能力>，凭据必须为规范 sh_ 格式", true);
        };
    }
}
