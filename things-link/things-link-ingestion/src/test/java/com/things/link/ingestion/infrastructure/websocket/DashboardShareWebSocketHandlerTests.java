package com.things.link.ingestion.infrastructure.websocket;

import com.things.link.dashboard.application.DashboardSharePrincipal;
import com.things.link.dashboard.application.sharing.DashboardShareSecurityEvents;
import com.things.link.ingestion.application.DashboardRealtimeConnection;
import com.things.link.ingestion.application.DashboardRealtimePrincipal;
import com.things.link.ingestion.application.DashboardRealtimeRegistry;
import com.things.link.ingestion.application.DashboardShareConnectionLease;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 传输最低层验证关闭原因、二进制拒绝与单次脱敏诊断；真实租约由网络集成验证。 */
class DashboardShareWebSocketHandlerTests {
    /** 共享注册表替身仅捕获连接适配器。 */
    private final DashboardRealtimeRegistry registry = mock(DashboardRealtimeRegistry.class);
    /** 租约真实Redis行为另有独立集成，不在此伪造跨实例证明。 */
    private final DashboardShareConnectionLease leases = mock(DashboardShareConnectionLease.class);
    /** 固定事件输出用于断言尝试UTF-8字节。 */
    private final DashboardShareSecurityEvents events = mock(DashboardShareSecurityEvents.class);
    /** 被测薄传输边界。 */
    private final DashboardShareWebSocketHandler handler = new DashboardShareWebSocketHandler(registry, leases, events);
    /** 已授权分享测试身份。 */
    private final DashboardSharePrincipal principal = new DashboardSharePrincipal(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, Instant.now().plusSeconds(60), "NONE", "a".repeat(64));

    /** 容器先行1009必须清注册且仅写一次事件，不能变成通用1000成功。 */
    @Test void containerFrameLimitClosesAndRecordsOnlyOnce() {
        WebSocketSession session = session();
        handler.afterConnectionEstablished(session);
        handler.afterConnectionClosed(session, new CloseStatus(1009));
        handler.afterConnectionClosed(session, new CloseStatus(1009));
        verify(events).record(eq(DashboardShareSecurityEvents.Type.WS_CLOSE), eq(DashboardShareSecurityEvents.Outcome.DENIED),
                eq(DashboardShareSecurityEvents.Reason.RESPONSE_LIMIT), nullable(String.class),
                eq(DashboardShareSecurityEvents.Route.PROPERTIES), eq(principal.shareId()), eq(principal.projectId()), eq(0L), anyLong());
        verify(session).setTextMessageSizeLimit(32768);
        verify(session).setBinaryMessageSizeLimit(32768);
    }

    /** 二进制不读取内容、不产生可排队错误文本，立即1008并移除注册。 */
    @Test void binaryFrameIsRejectedWithoutAnErrorQueue() throws Exception {
        WebSocketSession session = session();
        handler.afterConnectionEstablished(session);
        handler.handleBinaryMessage(session, new BinaryMessage(new byte[] {1}));
        verify(registry).unregister("connection");
        ArgumentCaptor<CloseStatus> status = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(status.capture());
        assertThat(status.getValue().getCode()).isEqualTo(1008);
    }

    /** 多字节文本按编码尝试计数，传输失败关闭不能被unregister掩盖为成功。 */
    @Test void transportErrorPreservesFailureAndAttemptedUtf8Bytes() throws Exception {
        WebSocketSession session = session();
        handler.afterConnectionEstablished(session);
        ArgumentCaptor<DashboardRealtimeConnection> connection = ArgumentCaptor.forClass(DashboardRealtimeConnection.class);
        verify(registry).register(connection.capture());
        connection.getValue().sendText("中文");
        handler.handleTransportError(session, new IllegalStateException("secret diagnostic"));
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        verify(events).record(eq(DashboardShareSecurityEvents.Type.WS_CLOSE), eq(DashboardShareSecurityEvents.Outcome.ERROR),
                eq(DashboardShareSecurityEvents.Reason.UNAVAILABLE), nullable(String.class),
                eq(DashboardShareSecurityEvents.Route.PROPERTIES), eq(principal.shareId()), eq(principal.projectId()), eq(6L), anyLong());
    }

    /** 真实Servlet适配前的会话替身只为薄Handler生命周期测试。 */
    private WebSocketSession session() {
        WebSocketSession session = mock(WebSocketSession.class);
        HashMap<String, Object> attributes = new HashMap<>();
        attributes.put(DashboardHandshakeInterceptor.PRINCIPAL_ATTRIBUTE, DashboardRealtimePrincipal.share(principal));
        attributes.put(DashboardShareHandshakeInterceptor.LEASE_ATTRIBUTE, new DashboardShareConnectionLease.Lease(principal.shareId(), "member"));
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getId()).thenReturn("connection");
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
