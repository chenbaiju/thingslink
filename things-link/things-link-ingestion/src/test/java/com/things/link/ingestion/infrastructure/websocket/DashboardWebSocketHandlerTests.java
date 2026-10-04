package com.things.link.ingestion.infrastructure.websocket;

import com.things.link.ingestion.application.DashboardRealtimeConnection;
import com.things.link.ingestion.application.DashboardRealtimePrincipal;
import com.things.link.ingestion.application.DashboardRealtimeRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 传输错误和正常断开都必须释放应用层连接，且不允许匿名会话进入核心。 */
class DashboardWebSocketHandlerTests {
    /** 缺失握手身份不能注册或接收业务帧。 */
    @Test
    void rejectsMissingHandshakeIdentity() throws Exception {
        DashboardRealtimeRegistry registry = mock(DashboardRealtimeRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(Map.of());
        new DashboardWebSocketHandler(registry).afterConnectionEstablished(session);
        verify(session).close(new CloseStatus(1008, "dashboard authentication required"));
        verifyNoInteractions(registry);
    }

    /** 已验证身份通过薄适配进入同一核心，UTF-8帧上限对齐合同。 */
    @Test
    void registersIdentityAndDelegatesMessages() throws Exception {
        DashboardRealtimeRegistry registry = mock(DashboardRealtimeRegistry.class);
        DashboardWebSocketHandler handler = new DashboardWebSocketHandler(registry);
        WebSocketSession session = mock(WebSocketSession.class);
        DashboardRealtimePrincipal principal = new DashboardRealtimePrincipal(false, UUID.randomUUID(),
                UUID.randomUUID(), null, 0L, Instant.now().plusSeconds(60));
        when(session.getAttributes()).thenReturn(Map.of(DashboardHandshakeInterceptor.PRINCIPAL_ATTRIBUTE, principal));
        when(session.getId()).thenReturn("connection");
        handler.afterConnectionEstablished(session);
        ArgumentCaptor<DashboardRealtimeConnection> connection = ArgumentCaptor.forClass(DashboardRealtimeConnection.class);
        verify(registry).register(connection.capture());
        assertThat(connection.getValue().principal()).isEqualTo(principal);
        assertThat(connection.getValue().id()).isEqualTo("connection");
        verify(session).setTextMessageSizeLimit(32768);
        handler.handleTextMessage(session, new TextMessage("{}"));
        verify(registry).receive("connection", "{}");
    }

    /** 协议版本由专用handler固定，Console身份不得进入App双域入口。 */
    @Test
    void dashboardV2MarksOnlyAppConnections() throws Exception {
        DashboardRealtimeRegistry registry = mock(DashboardRealtimeRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("v2");
        DashboardRealtimePrincipal app = new DashboardRealtimePrincipal(true, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, Instant.now().plusSeconds(60));
        when(session.getAttributes()).thenReturn(Map.of(DashboardHandshakeInterceptor.PRINCIPAL_ATTRIBUTE, app));
        new DashboardWebSocketHandler(registry, true).afterConnectionEstablished(session);
        ArgumentCaptor<DashboardRealtimeConnection> captured = ArgumentCaptor.forClass(DashboardRealtimeConnection.class);
        verify(registry).register(captured.capture()); assertThat(captured.getValue().dashboardV2()).isTrue();
        WebSocketSession console = mock(WebSocketSession.class);
        when(console.getAttributes()).thenReturn(Map.of(DashboardHandshakeInterceptor.PRINCIPAL_ATTRIBUTE,
                new DashboardRealtimePrincipal(false, app.subjectId(), app.tenantId(), null, 0, app.expiresAt())));
        new DashboardWebSocketHandler(registry, true).afterConnectionEstablished(console);
        verify(console).close(new CloseStatus(1008, "dashboard authentication required"));
    }

    /** 传输错误立即清理，不依赖容器未来一定触发close回调。 */
    @Test
    void releasesConnectionOnTransportErrorAndClose() throws Exception {
        DashboardRealtimeRegistry registry = mock(DashboardRealtimeRegistry.class);
        DashboardWebSocketHandler handler = new DashboardWebSocketHandler(registry);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("broken");
        handler.handleTransportError(session, new IOException("private transport detail"));
        verify(registry).unregister("broken");
        verify(session).close(new CloseStatus(1011, "dashboard transport unavailable"));
        when(session.getId()).thenReturn("closed");
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        verify(registry).unregister("closed");
    }
}
