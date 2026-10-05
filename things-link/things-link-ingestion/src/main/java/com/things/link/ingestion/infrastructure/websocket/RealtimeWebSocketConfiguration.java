package com.things.link.ingestion.infrastructure.websocket;

import com.things.link.ingestion.application.RealtimeSubscriptionRegistry;
import com.things.link.ingestion.application.RealtimeSubscriptionService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * S4-4 原生 WebSocket 装配。
 *
 * <p>端点不使用 STOMP/SockJS：本项目只需要有界 JSON 增量订阅，额外协议会扩大浏览器与服务端
 * 攻击面且不解决断线补拉。跨实例扇出仍由后续 Redis Pub/Sub 监听器调用 application fanout 入口。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@EnableConfigurationProperties(RealtimeWebSocketProperties.class)
public class RealtimeWebSocketConfiguration implements WebSocketConfigurer {

    /** JWT 子协议握手校验器。 */
    private final RealtimeHandshakeInterceptor handshakeInterceptor;

    /** 应用服务装配所需依赖。 */
    private final RealtimeSubscriptionRegistry registry;

    /** 订阅协议服务。 */
    private final RealtimeSubscriptionService subscriptionService;

    /** 已冻结的帧大小、发送时限与会话配额。 */
    private final RealtimeWebSocketProperties properties;

    /**
     * @param handshakeInterceptor JWT 握手校验器
     * @param registry 本机注册表
     * @param subscriptionService 订阅消息服务
     */
    public RealtimeWebSocketConfiguration(RealtimeHandshakeInterceptor handshakeInterceptor,
                                          RealtimeSubscriptionRegistry registry,
                                          RealtimeSubscriptionService subscriptionService,
                                          RealtimeWebSocketProperties properties) {
        this.handshakeInterceptor = handshakeInterceptor;
        this.registry = registry;
        this.subscriptionService = subscriptionService;
        this.properties = properties;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new RealtimeWebSocketHandler(this.registry, subscriptionService, properties),
                        "/api/v1/realtime/ws")
                // 默认仅同源。Vite 开发代理应以 WebSocket 升级代理保持此约束，生产同站部署亦相同。
                .addInterceptors(handshakeInterceptor)
                .setHandshakeHandler(new RealtimeHandshakeHandler());
    }


    /**
     * 描述原生 HTTP 升级入口，生产关闭文档时不创建此文档 Bean。
     *
     * @return 只维护契约、不注册运行路由的文档修正器
     */
    @org.springframework.context.annotation.Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "springdoc.api-docs.enabled", havingValue = "true", matchIfMissing = true)
    public org.springdoc.core.customizers.OpenApiCustomizer consoleRealtimeHandshakeDocumentation() {
        return api -> {
            com.things.link.support.openapi.HttpTransportOpenApiDocumentation.webSocket(api,
                    "/api/v1/realtime/ws", "upgradeConsoleRealtime", "建立控制台实时连接",
                    "仅同源；原控制台 JWT 经握手校验项目成员及设备访问范围。",
                    "按顺序发送 tc-v1 与 bearer.<控制台JWT>，禁止通过 URL 查询串传递 JWT", false);
        };
    }
}
