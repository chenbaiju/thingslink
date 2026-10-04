package com.things.link.ingestion.infrastructure.websocket;

import com.things.link.ingestion.application.DashboardRealtimeConnection;
import com.things.link.ingestion.application.DashboardRealtimePrincipal;
import com.things.link.ingestion.application.DashboardRealtimeRegistry;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** 两个新端点共用的传输适配；订阅和dirty键规则全部留在application核心。 */
public class DashboardWebSocketHandler extends TextWebSocketHandler {
    /** ADR0099单个UTF-8帧最大32KiB。 */
    private static final int MAX_FRAME_BYTES = 32768;
    /** 底层发送最大等待5秒，慢客户端不得无限占用发送线程。 */
    private static final int SEND_TIMEOUT_MILLIS = 5000;
    /** 共享的连接、授权与提示核心。 */
    private final DashboardRealtimeRegistry registry;

    /** 仅独立App v2装配打开双域订阅。 */
    private final boolean dashboardV2;

    /** 端点实例共享同一注册表，不能建立两套无关配额与队列。 */
    public DashboardWebSocketHandler(DashboardRealtimeRegistry registry) {
        this(registry, false);
    }

    /** 显式冻结协议版本，不能由客户端帧自行升级旧连接。 */
    public DashboardWebSocketHandler(DashboardRealtimeRegistry registry, boolean dashboardV2) {
        this.registry = registry;
        this.dashboardV2 = dashboardV2;
    }

    /** 身份缺失时直接拒绝；注册表再次复验当前身份并应用连接上限。 */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Object identity = session.getAttributes().get(DashboardHandshakeInterceptor.PRINCIPAL_ATTRIBUTE);
        if (!(identity instanceof DashboardRealtimePrincipal principal) || (dashboardV2 && !principal.app())) {
            close(session, 1008, "dashboard authentication required");
            return;
        }
        session.setTextMessageSizeLimit(MAX_FRAME_BYTES);
        registry.register(new DashboardWebSocketSession(new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIMEOUT_MILLIS, MAX_FRAME_BYTES), principal, dashboardV2));
    }

    /** 完整消息交给核心统一检查UTF-8字节与一次订阅约束。 */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        registry.receive(session.getId(), message.getPayload());
    }

    /** 正常断开也必须释放配额、索引与待发送dirty键。 */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(session.getId());
    }

    /** 底层I/O错误不等待未来close回调才清理。 */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        registry.unregister(session.getId());
        close(session, 1011, "dashboard transport unavailable");
    }

    /** 关闭原因固定，避免泄露JWT、设备或底层异常内容。 */
    private static void close(WebSocketSession session, int code, String reason) {
        try {
            session.close(new CloseStatus(code, reason));
        } catch (Exception ignored) {
            // 并发断开没有可恢复传输；注册表清理已独立执行。
        }
    }

    /** 薄会话适配器，不在传输层缓存业务属性值。 */
    private record DashboardWebSocketSession(WebSocketSession session, DashboardRealtimePrincipal principal, boolean dashboardV2)
            implements DashboardRealtimeConnection {
        /** 物理会话ID作为唯一连接索引。 */
        @Override
        public String id() {
            return session.getId();
        }

        /** 仅发送核心已经授权的有界帧。 */
        @Override
        public void sendText(String payload) throws Exception {
            session.sendMessage(new TextMessage(payload));
        }

        /** 核心拒绝时关闭物理连接，稳定状态码保留。 */
        @Override
        public void close(int code, String reason) {
            DashboardWebSocketHandler.close(session, code, reason);
        }
    }
}
