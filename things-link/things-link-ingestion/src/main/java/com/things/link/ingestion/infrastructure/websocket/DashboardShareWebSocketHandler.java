package com.things.link.ingestion.infrastructure.websocket;

import com.things.link.dashboard.application.sharing.DashboardShareSecurityEvents;
import com.things.link.ingestion.application.DashboardRealtimeConnection;
import com.things.link.ingestion.application.DashboardRealtimePrincipal;
import com.things.link.ingestion.application.DashboardRealtimeRegistry;
import com.things.link.ingestion.application.DashboardShareConnectionLease;
import com.things.link.support.trace.TraceContext;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 分享仅适配独立握手身份，发送/队列/确权仍由Console/App共享注册核心负责。 */
public final class DashboardShareWebSocketHandler extends TextWebSocketHandler {
    /** 单消息32KiB；容器先行拒绝超大聚合消息1009，应用可观测违规1008。 */
    private static final int MAX_FRAME_BYTES = 32 * 1024;
    /** 保持既有5秒发送截止，不让慢连接无限占用共享工作槽。 */
    private static final int SEND_TIMEOUT_MILLIS = 5000;
    /** 连接局部状态不进入全局未知身份键空间。 */
    private static final String CONNECTION_ATTRIBUTE = DashboardShareWebSocketHandler.class.getName();
    /** 同核注册表保证项目池和物理池真实共享。 */
    private final DashboardRealtimeRegistry registry;
    /** 注册前失败也必须清理握手租约。 */
    private final DashboardShareConnectionLease leases;
    /** 单连接一次关闭事件，固定字段而非原URI或底层错误文本。 */
    private final DashboardShareSecurityEvents events;

    /** 显式传入共享核心及独立租约、诊断。 */
    public DashboardShareWebSocketHandler(DashboardRealtimeRegistry registry, DashboardShareConnectionLease leases,
                                          DashboardShareSecurityEvents events) {
        this.registry = registry;
        this.leases = leases;
        this.events = events;
    }

    /** 注册表取得租约所有权；未携带真实分享身份绝不改用session账号。 */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Object raw = session.getAttributes().get(DashboardHandshakeInterceptor.PRINCIPAL_ATTRIBUTE);
        Object leaseValue = session.getAttributes().get(DashboardShareHandshakeInterceptor.LEASE_ATTRIBUTE);
        DashboardShareConnectionLease.Lease lease = leaseValue instanceof DashboardShareConnectionLease.Lease value ? value : null;
        if (!(raw instanceof DashboardRealtimePrincipal principal) || !principal.share() || lease == null
                || !lease.shareId().equals(principal.sharePrincipal().shareId())) {
            leases.release(lease);
            closeSocket(session, 1008, "share authentication required");
            return;
        }
        session.setTextMessageSizeLimit(MAX_FRAME_BYTES);
        session.setBinaryMessageSizeLimit(MAX_FRAME_BYTES);
        Connection connection = new Connection(new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIMEOUT_MILLIS, MAX_FRAME_BYTES), principal, lease);
        session.getAttributes().put(CONNECTION_ATTRIBUTE, connection);
        try {
            registry.register(connection);
        } catch (RuntimeException failure) {
            leases.release(lease);
            connection.close(1011, "share registration unavailable");
        }
    }

    /** 完整文本进入唯一核心，未经验证的正文不记日志。 */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        registry.receive(session.getId(), message.getPayload());
    }

    /** 分享协议仅文本，二进制不解析正文或产生可排队错误消息。 */
    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        terminate(session, 1008, "share binary frame forbidden");
    }

    /** 容器先行1009也先隔离索引并释放租约，不能被通用unregister的1000掩盖。 */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        terminate(session, status.getCode(), "share connection closed");
        session.getAttributes().remove(CONNECTION_ATTRIBUTE);
    }

    /** I/O失败不等待浏览器断网回调；保留1011而非注册表通用正常断开代码。 */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable failure) {
        terminate(session, 1011, "share transport unavailable");
    }

    /** 先冻结实际观察原因，再幂等触发核心索引/队列/配额清理。 */
    private void terminate(WebSocketSession session, int code, String reason) {
        Object raw = session.getAttributes().get(CONNECTION_ATTRIBUTE);
        if (raw instanceof Connection connection) {
            connection.observe(code, reason);
            registry.unregister(session.getId());
            // 核心若已移除或注册失败，也必须完成一次脱敏关闭事件。
            connection.close(code, reason);
        } else {
            registry.unregister(session.getId());
            closeSocket(session, code, reason);
        }
    }

    /** 固定原因不带异常原文或任何身份，已断开也不影响核心清理。 */
    private static void closeSocket(WebSocketSession session, int code, String reason) {
        try {
            session.close(new CloseStatus(code, reason));
        } catch (Exception ignored) {
            // 核心资源回收独立于网络关闭成功。
        }
    }

    /** 租约只通过内部连接端口交给Registry，计数代表编码尝试而非远端确认收到。 */
    private final class Connection implements DashboardRealtimeConnection {
        /** 有界且串行的实际传输适配器。 */
        private final WebSocketSession session;
        /** 经过真实握手复验的独立分享身份。 */
        private final DashboardRealtimePrincipal principal;
        /** 升级前已申请的跨实例两连接租约。 */
        private final DashboardShareConnectionLease.Lease lease;
        /** 每条连接只记录一次终态。 */
        private final AtomicBoolean closed = new AtomicBoolean();
        /** 在尝试写socket之前累计UTF-8字节，写失败也不虚报零。 */
        private final AtomicLong attemptedBytes = new AtomicLong();
        /** 单调时间避免系统时钟回拨产生负耗时。 */
        private final long started = System.nanoTime();
        /** 容器或传输先行关闭原因优先于通用unregister的1000。 */
        private volatile CloseStatus observed;

        /** 把会话局部资源一次移交核心，无静态会话Map。 */
        Connection(WebSocketSession session, DashboardRealtimePrincipal principal, DashboardShareConnectionLease.Lease lease) {
            this.session = session;
            this.principal = principal;
            this.lease = lease;
        }

        /** 容器生成的物理ID不来自订阅正文。 */
        @Override public String id() { return session.getId(); }
        /** 独立身份不得退回HTTP账号。 */
        @Override public DashboardRealtimePrincipal principal() { return principal; }
        /** 续期与释放由共享注册表统一执行。 */
        @Override public DashboardShareConnectionLease.Lease shareLease() { return lease; }

        /** 只发送核心已经确权和扣预算的文本；发送失败仍保守记录编码尝试。 */
        @Override
        public void sendText(String payload) throws Exception {
            if (closed.get()) throw new IllegalStateException("分享连接已关闭");
            attemptedBytes.addAndGet(payload.getBytes(StandardCharsets.UTF_8).length);
            session.sendMessage(new TextMessage(payload));
        }

        /** 容器观察先于通用unregister；仅保留固定内部原因而非浏览器自带reason。 */
        synchronized void observe(int code, String reason) {
            if (observed == null) observed = new CloseStatus(code, reason);
        }

        /** 终态事件不含secret/hash/属性值，重复网络回调不能重复写WS_CLOSE。 */
        @Override
        public void close(int code, String reason) {
            if (!closed.compareAndSet(false, true)) return;
            CloseStatus actual = observed;
            int effectiveCode = actual == null ? code : actual.getCode();
            String effectiveReason = actual == null ? reason : actual.getReason();
            closeSocket(session, effectiveCode, effectiveReason);
            boolean normal = effectiveCode == 1000 || effectiveCode == 1001;
            boolean denied = effectiveCode == 1008 || effectiveCode == 1009 || effectiveCode == 1013;
            events.record(DashboardShareSecurityEvents.Type.WS_CLOSE,
                    normal ? DashboardShareSecurityEvents.Outcome.ALLOWED : denied
                            ? DashboardShareSecurityEvents.Outcome.DENIED : DashboardShareSecurityEvents.Outcome.ERROR,
                    normal ? DashboardShareSecurityEvents.Reason.SUCCESS : effectiveCode == 1009
                            ? DashboardShareSecurityEvents.Reason.RESPONSE_LIMIT : denied
                            ? DashboardShareSecurityEvents.Reason.SCOPE_DENIED : DashboardShareSecurityEvents.Reason.UNAVAILABLE,
                    TraceContext.current(), DashboardShareSecurityEvents.Route.PROPERTIES,
                    principal.sharePrincipal().shareId(), principal.projectId(), attemptedBytes.get(),
                    (System.nanoTime() - started) / 1_000_000);
        }
    }
}
