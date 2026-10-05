package com.things.link.ingestion.infrastructure.websocket;

import com.things.link.ingestion.application.RealtimeConnection;
import com.things.link.ingestion.application.RealtimePrincipal;
import com.things.link.ingestion.application.RealtimeSubscriptionRegistry;
import com.things.link.ingestion.application.RealtimeSubscriptionService;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /api/v1/realtime/ws} 的协议适配器；不承载订阅规则与 fanout 业务。
 */
public class RealtimeWebSocketHandler extends TextWebSocketHandler {

    /** 本机发送会话适配器；断开时必须用它从应用层索引删除。 */
    private final Map<String, RealtimeConnection> connections = new ConcurrentHashMap<>();

    /** 本机配额、订阅和队列服务。 */
    private final RealtimeSubscriptionRegistry registry;

    /** 文本协议和订阅授权服务。 */
    private final RealtimeSubscriptionService subscriptionService;

    /** 帧大小与单会话发送保护参数。 */
    private final RealtimeWebSocketProperties properties;

    /**
     * @param registry 本机注册表
     * @param subscriptionService 协议服务
     */
    public RealtimeWebSocketHandler(RealtimeSubscriptionRegistry registry,
                                    RealtimeSubscriptionService subscriptionService,
                                    RealtimeWebSocketProperties properties) {
        this.registry = registry;
        this.subscriptionService = subscriptionService;
        this.properties = properties;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Object principal = session.getAttributes().get(RealtimeHandshakeInterceptor.PRINCIPAL_ATTRIBUTE);
        if (!(principal instanceof RealtimePrincipal realtimePrincipal)) {
            close(session, 1008, "realtime authentication required");
            return;
        }
        session.setTextMessageSizeLimit(properties.maxTextFrameBytes());
        WebSocketSession protectedSession = new ConcurrentWebSocketSessionDecorator(
                session, Math.toIntExact(properties.sendTimeoutMillis()), properties.maxTextFrameBytes());
        RealtimeConnection connection = new RealtimeWebSocketSession(protectedSession, realtimePrincipal);
        // 注册表先从可信 projectId 解析项目所有者租户并申请共享套餐租约；拒绝路径已用 1008 关闭会话。
        if (registry.register(connection)) {
            connections.put(session.getId(), connection);
        }
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        RealtimeConnection connection = connections.get(session.getId());
        if (connection == null) {
            close(session, 1008, "realtime session unavailable");
            return;
        }
        subscriptionService.handle(connection, message.getPayload());
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        connections.remove(session.getId());
        registry.unregister(session.getId());
    }

    /** 使用稳定关闭原因，避免把底层异常内容回显给浏览器。 */
    private static void close(WebSocketSession session, int code, String reason) {
        try {
            session.close(new CloseStatus(code, reason));
        } catch (Exception ignored) {
            // 握手/断开竞态下会话可能已经关闭；没有额外清理对象。
        }
    }
}
