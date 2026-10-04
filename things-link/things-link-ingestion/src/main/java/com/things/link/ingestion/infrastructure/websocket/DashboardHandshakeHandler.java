package com.things.link.ingestion.infrastructure.websocket;

import com.things.link.ingestion.application.DashboardRealtimePrincipal;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/** 新看板握手只回选公开子协议，永不回显bearer凭据。 */
public class DashboardHandshakeHandler extends DefaultHandshakeHandler {
    /** 端点绑定的公开协议，不从请求选择身份种类。 */
    private final String protocol;

    /** 每个端点使用独立的公开协议。 */
    public DashboardHandshakeHandler(String protocol) {
        this.protocol = protocol;
    }

    /** 只使用已复验身份，不回退匿名或HTTP主体。 */
    @Override
    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler handler,
                                      Map<String, Object> attributes) {
        Object value = attributes.get(DashboardHandshakeInterceptor.PRINCIPAL_ATTRIBUTE);
        return value instanceof DashboardRealtimePrincipal identity ? identity : null;
    }

    /** 凭据只供拦截器读取，永不出现在协商结果。 */
    @Override
    protected String selectProtocol(List<String> requested, WebSocketHandler handler) {
        return requested.contains(protocol) ? protocol : null;
    }
}
