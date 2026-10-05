package com.things.link.ingestion.infrastructure.websocket;

import com.things.link.ingestion.application.RealtimeConnection;
import com.things.link.ingestion.application.RealtimePrincipal;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Spring {@link WebSocketSession} 到应用层会话端口的薄适配器。
 */
public class RealtimeWebSocketSession implements RealtimeConnection {

    /** Spring 容器提供的物理 WebSocket 会话。 */
    private final WebSocketSession session;

    /** 握手阶段已经签名校验的身份。 */
    private final RealtimePrincipal principal;

    /**
     * @param session 物理会话
     * @param principal 已认证身份
     */
    public RealtimeWebSocketSession(WebSocketSession session, RealtimePrincipal principal) {
        this.session = session;
        this.principal = principal;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public String id() {
        return session.getId();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public RealtimePrincipal principal() {
        return principal;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void sendText(String payload) throws Exception {
        session.sendMessage(new TextMessage(payload));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void close(int statusCode, String reason) {
        try {
            session.close(new CloseStatus(statusCode, reason));
        } catch (Exception ignored) {
            // 连接已经断开时关闭失败没有可恢复动作；注册表仍会清理本机索引。
        }
    }
}
