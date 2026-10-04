package com.things.link.ingestion.infrastructure.websocket;

import com.things.link.ingestion.application.RealtimePrincipal;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * 只协商公开协议版本，并把拦截器已验证身份设为 WebSocket Principal。
 */
public class RealtimeHandshakeHandler extends DefaultHandshakeHandler {

    /** {@inheritDoc} */
    @Override
    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler webSocketHandler,
                                      Map<String, Object> attributes) {
        Object principal = attributes.get(RealtimeHandshakeInterceptor.PRINCIPAL_ATTRIBUTE);
        if (principal instanceof RealtimePrincipal realtimePrincipal) {
            return realtimePrincipal;
        }
        // beforeHandshake 已拒绝没有身份的请求；此处仍 fail-closed，不能交给父类生成匿名 Principal。
        return null;
    }

    /** {@inheritDoc} */
    @Override
    protected String selectProtocol(List<String> requestedProtocols, WebSocketHandler webSocketHandler) {
        return requestedProtocols.contains(RealtimeHandshakeInterceptor.APPLICATION_PROTOCOL)
                ? RealtimeHandshakeInterceptor.APPLICATION_PROTOCOL : null;
    }
}
