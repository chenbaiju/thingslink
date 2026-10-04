package com.things.link.ingestion.infrastructure.websocket;

import com.things.link.dashboard.application.DashboardSharePrincipal;
import com.things.link.dashboard.application.DashboardShareRuntimeService;
import com.things.link.dashboard.application.sharing.DashboardShareProtectionService;
import com.things.link.dashboard.application.sharing.DashboardShareRuntimeProperties;
import com.things.link.dashboard.application.sharing.DashboardShareSecurityEvents;
import com.things.link.ingestion.application.DashboardRealtimePrincipal;
import com.things.link.ingestion.application.DashboardShareConnectionLease;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.trace.TraceContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** 分享合同第4/5节：源保护先于能力查库，升级之前取得独立两连接租约。 */
public final class DashboardShareHandshakeInterceptor implements HandshakeInterceptor {
    /** 只回选公开协议，私有share.secret子协议不得出现在响应。 */
    public static final String PROTOCOL = "tc.share.properties.v1";
    /** 已申请租约经握手属性移交实际连接，不能等待SUBSCRIBE才计数。 */
    public static final String LEASE_ATTRIBUTE = DashboardShareConnectionLease.Lease.class.getName();
    /** 请求局部生命周期用于afterHandshake处理升级失败，非全局未知身份Map。 */
    private static final String STATE_ATTRIBUTE = DashboardShareHandshakeInterceptor.class.getName();
    /** 独立路径只接受规范小写UUID。 */
    private static final Pattern PATH = Pattern.compile("^/ws/shares/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/properties$");
    /** 能力领域的真实数据库定位端口。 */
    private final DashboardShareRuntimeService runtime;
    /** 固定来源键空间与16在途许可，不读取代理转发头。 */
    private final DashboardShareProtectionService protection;
    /** 默认禁用和受管Origin与HTTP能力共享配置。 */
    private final DashboardShareRuntimeProperties properties;
    /** 分享租约与旧账号租约分开计量。 */
    private final DashboardShareConnectionLease leases;
    /** 全部事件固定模板且不含凭据、异常原文。 */
    private final DashboardShareSecurityEvents events;
    /** 所有依赖显式装配，缺少保护不开放。 */
    public DashboardShareHandshakeInterceptor(DashboardShareRuntimeService runtime, DashboardShareProtectionService protection,
            DashboardShareRuntimeProperties properties, DashboardShareConnectionLease leases, DashboardShareSecurityEvents events) {
        this.runtime = runtime;
        this.protection = protection;
        this.properties = properties;
        this.leases = leases;
        this.events = events;
    }
    /** 安全链与handler配置共用精确路径判断，不按宽泛/ws/**直接permitAll。 */
    public static boolean matches(String method, String path) { return "GET".equals(method) && PATH.matcher(path).matches(); }
    /** Cookie与Authorization完全忽略；唯一凭据来自规范私有子协议。 */
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Map<String, Object> attributes) {
        response.getHeaders().setCacheControl("no-store");
        State state = new State();
        if (!(request instanceof ServletServerHttpRequest servlet)) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE); return false;
        }
        servlet.getServletRequest().setAttribute(STATE_ATTRIBUTE, state);
        try {
            if (!properties.enabled()) { state.status = 503; return false; }
            state.source = protection.acquireSource(servlet.getServletRequest().getRemoteAddr());
            String path = servlet.getServletRequest().getRequestURI().substring(servlet.getServletRequest().getContextPath().length());
            var matcher = PATH.matcher(path);
            if (!matches(request.getMethod().name(), path) || !matcher.matches() || request.getURI().getRawQuery() != null
                    || request.getHeaders().getContentLength() > 0 || request.getHeaders().containsHeader("Transfer-Encoding")) {
                state.status = 403;
                state.reason = DashboardShareSecurityEvents.Reason.INVALID_INPUT; return false;
            }
            List<String> origins = request.getHeaders().getOrEmpty("Origin");
            if (origins.size() != 1 || !properties.hostOrigin().equals(origins.getFirst())) {
                state.status = 403;
                state.reason = DashboardShareSecurityEvents.Reason.REFERER_DENIED; return false;
            }
            String hash = credential(request.getHeaders().getOrEmpty("Sec-WebSocket-Protocol"));
            state.principal = runtime.authenticate(UUID.fromString(matcher.group(1)), hash);
            state.lease = leases.acquire(state.principal.shareId(), UUID.randomUUID().toString());
            attributes.put(DashboardHandshakeInterceptor.PRINCIPAL_ATTRIBUTE, DashboardRealtimePrincipal.share(state.principal));
            attributes.put(LEASE_ATTRIBUTE, state.lease);
            state.accepted = true;
            return true;
        } catch (BusinessException failure) {
            state.status = failure.errorCode().httpStatus();
            state.reason = state.status == 429 ? DashboardShareSecurityEvents.Reason.RATE_LIMITED
                    : state.status >= 500 ? DashboardShareSecurityEvents.Reason.UNAVAILABLE : DashboardShareSecurityEvents.Reason.INVALID_CREDENTIAL;
            return false;
        } catch (IllegalArgumentException failure) {
            state.status = 403;
            state.reason = DashboardShareSecurityEvents.Reason.INVALID_CREDENTIAL;
            return false;
        } catch (Exception failure) {
            state.status = 503;
            state.reason = DashboardShareSecurityEvents.Reason.UNAVAILABLE;
            return false;
        } finally {
            if (!state.accepted) {
                response.setStatusCode(HttpStatus.valueOf(state.status));
                if (state.status == 429) response.getHeaders().set("Retry-After", "1");
                finish(state, false);
            }
        }
    }
    /** 实际升级失败释放预租约；成功后唯一责任交给Registry，来源许可始终及时回收。 */
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Exception exception) {
        if (request instanceof ServletServerHttpRequest servlet
                && servlet.getServletRequest().getAttribute(STATE_ATTRIBUTE) instanceof State state) {
            boolean upgraded = exception == null && response instanceof ServletServerHttpResponse actual
                    && actual.getServletResponse().getStatus() == 101;
            if (!upgraded && state.accepted) { state.status = 503; state.reason = DashboardShareSecurityEvents.Reason.UNAVAILABLE; }
            finish(state, upgraded);
            servlet.getServletRequest().removeAttribute(STATE_ATTRIBUTE);
        }
    }
    /** 本地完成标记防止Spring失败分支重复回调重复审计或释放许可。 */
    private void finish(State state, boolean upgraded) {
        if (state.finished) return;
        state.finished = true;
        if (!upgraded && state.lease != null) leases.release(state.lease);
        try {
            events.record(DashboardShareSecurityEvents.Type.WS_HANDSHAKE,
                    upgraded ? DashboardShareSecurityEvents.Outcome.ALLOWED : state.status >= 500
                            ? DashboardShareSecurityEvents.Outcome.ERROR : DashboardShareSecurityEvents.Outcome.DENIED,
                    upgraded ? DashboardShareSecurityEvents.Reason.SUCCESS : state.reason,
                    TraceContext.current(), DashboardShareSecurityEvents.Route.PROPERTIES,
                    state.principal == null ? null : state.principal.shareId(),
                    state.principal == null ? null : state.principal.projectId(),
                    0, (System.nanoTime() - state.started) / 1_000_000);
        } finally {
            if (state.source != null) state.source.close();
        }
    }
    /** 恰好公开协议加一个规范32随机字节secret；列表重复/附加协议均不接受。 */
    private static String credential(List<String> headers) throws Exception {
        List<String> protocols = new ArrayList<>();
        for (String header : headers) {
            for (String item : header.split(",", -1)) protocols.add(item.trim());
        }
        if (protocols.size() != 2 || !protocols.contains(PROTOCOL)) throw new IllegalArgumentException("分享子协议无效");
        String privateProtocol = protocols.stream().filter(value -> value.startsWith("share.sh_")).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("分享凭据缺失"));
        String secret = privateProtocol.substring(6);
        if (!secret.matches("sh_[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("分享凭据无效");
        byte[] decoded = Base64.getUrlDecoder().decode(secret.substring(3));
        if (decoded.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(secret.substring(3)))
            throw new IllegalArgumentException("分享凭据非规范");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
    }
    /** 仅单个握手暂存保护资源；不保存原始secret或请求URL。 */
    private static final class State {
        /** 用单调时间计算耗时。 */ final long started = System.nanoTime();
        /** 在途许可贯穿实际升级。 */ DashboardShareProtectionService.SourcePermit source;
        /** 已知最小身份，诊断仅投影share/project。 */ DashboardSharePrincipal principal;
        /** 升级失败回收，成功移交连接。 */ DashboardShareConnectionLease.Lease lease;
        /** beforeHandshake已经通过。 */ boolean accepted;
        /** 防止重复finally/after回调。 */ boolean finished;
        /** 默认依赖失败保护。 */ int status = 503;
        /** 默认固定诊断原因。 */ DashboardShareSecurityEvents.Reason reason = DashboardShareSecurityEvents.Reason.UNAVAILABLE;
    }
}
