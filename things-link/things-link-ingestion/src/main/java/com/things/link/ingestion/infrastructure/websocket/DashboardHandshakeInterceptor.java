package com.things.link.ingestion.infrastructure.websocket;

import com.things.link.enduser.application.AppAuthenticatedPrincipal;
import com.things.link.enduser.application.AppRealtimeAccessService;
import com.things.link.enduser.application.AppTokenIssuer;
import com.things.link.ingestion.application.DashboardRealtimePrincipal;
import com.things.link.project.application.AccountDirectory;
import com.things.link.shared.error.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ADR0099：两个新端点分别验签和复验身份，握手不假称已经验证尚未提交的设备。 */
public class DashboardHandshakeInterceptor implements HandshakeInterceptor {
    /** 公开App子协议，凭据子协议永不回显。 */
    public static final String APP_PROTOCOL = "tc.app.properties.v1";
    /** ADR0109独立App双域协议，旧App端点不协商此值。 */
    public static final String APP_DASHBOARD_PROTOCOL = "tc.app.dashboard.v2";
    /** 当前显式端点唯一公开协议。 */
    private final String publicProtocol;
    /** 公开Console看板子协议，与旧设备详情协议隔离。 */
    public static final String CONSOLE_PROTOCOL = "tc.dashboard.properties.v1";
    /** 会话只保留已复验的最小身份，不保存JWT原文。 */
    public static final String PRINCIPAL_ATTRIBUTE = DashboardRealtimePrincipal.class.getName();
    /** 当前端点身份种类。 */
    private final boolean app;
    /** 明确选定的App或Console解码器。 */
    private final JwtDecoder decoder;
    /** 必须与既有签发配置一致的issuer。 */
    private final String issuer;
    /** 对应宿主的精确来源白名单。 */
    private final List<String> origins;
    /** App用户、角色与项目代次权威端口。 */
    private final AppRealtimeAccessService appAccess;
    /** IAM拥有的账号有效性端口，不访问IAM基础设施。 */
    private final AccountDirectory accounts;

    /** 装配独立身份配置，避免错误decoder的同类型自动注入。 */
    public DashboardHandshakeInterceptor(boolean app, JwtDecoder decoder, String issuer, List<String> origins,
                                         AppRealtimeAccessService appAccess, AccountDirectory accounts) {
        this(app, decoder, issuer, origins, appAccess, accounts, app ? APP_PROTOCOL : CONSOLE_PROTOCOL);
    }

    /** v2复用同一确权实现，但端点协议必须由装配显式限定。 */
    public DashboardHandshakeInterceptor(boolean app, JwtDecoder decoder, String issuer, List<String> origins,
                                         AppRealtimeAccessService appAccess, AccountDirectory accounts, String protocol) {
        if (!(app ? List.of(APP_PROTOCOL, APP_DASHBOARD_PROTOCOL).contains(protocol) : CONSOLE_PROTOCOL.equals(protocol)))
            throw new IllegalArgumentException("非法实时端点协议");
        this.publicProtocol = protocol;
        this.app = app;
        this.decoder = decoder;
        this.issuer = issuer;
        this.origins = List.copyOf(origins);
        this.appAccess = appAccess;
        this.accounts = accounts;
    }

    /** Origin与子协议先校验，再进行JWT和数据库身份复验；依赖故障保持503而非冒称失权。 */
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        List<String> originHeaders = request.getHeaders().getOrEmpty("Origin");
        if (originHeaders.size() != 1 || !origins.contains(originHeaders.getFirst())) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        try {
            List<String> protocols = new ArrayList<>();
            request.getHeaders().getOrEmpty("Sec-WebSocket-Protocol").forEach(header -> {
                for (String value : header.split(",", -1)) {
                    protocols.add(value.trim());
                }
            });
            if (protocols.size() != 2 || !protocols.contains(publicProtocol)) {
                throw new IllegalArgumentException("无效实时子协议");
            }
            String bearer = protocols.stream().filter(value -> value.startsWith("bearer."))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("缺少实时凭据"));
            Jwt jwt = decoder.decode(bearer.substring("bearer.".length()));
            if (!issuer.equals(jwt.getClaimAsString("iss"))) {
                throw new IllegalArgumentException("无效实时签发方");
            }
            if (jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(Instant.now())) {
                throw new IllegalArgumentException("实时凭据已过期");
            }
            // 新App握手的显式受众与专用验签并用；Console原令牌无aud，显式异类aud仍拒绝。
            if (app ? !List.of(AppTokenIssuer.AUDIENCE).equals(jwt.getAudience())
                    : jwt.getAudience() != null && !jwt.getAudience().isEmpty()
                    && !List.of("things-link").equals(jwt.getAudience())) {
                throw new IllegalArgumentException("无效实时受众");
            }
            UUID subjectId = UUID.fromString(jwt.getSubject());
            UUID tenantId = UUID.fromString(jwt.getClaimAsString("tid"));
            String project = jwt.getClaimAsString("pid");
            UUID projectId = project == null ? null : UUID.fromString(project);
            long generation = projectGeneration(jwt);
            if (app) {
                if (projectId == null) {
                    throw new IllegalArgumentException("App凭据缺少项目");
                }
                appAccess.validateIdentity(new AppAuthenticatedPrincipal(tenantId, projectId, subjectId, generation));
            } else if (!accounts.isActive(subjectId)) {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
            attributes.put(PRINCIPAL_ATTRIBUTE, new DashboardRealtimePrincipal(
                    app, subjectId, tenantId, projectId, generation, jwt.getExpiresAt()));
            return true;
        } catch (BusinessException denied) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        } catch (JwtException | IllegalArgumentException | NullPointerException invalid) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        } catch (RuntimeException unavailable) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
    }

    /** ADR0073允许缺省零代，但小数、负数和溢出不能截断为可接受代次。 */
    private static long projectGeneration(Jwt jwt) {
        Object claim = jwt.getClaim("pgv");
        if (claim == null) {
            return 0L;
        }
        if (!(claim instanceof Number)) {
            throw new IllegalArgumentException("项目代次必须为整数");
        }
        try {
            long generation = new BigDecimal(claim.toString()).longValueExact();
            if (generation < 0) {
                throw new IllegalArgumentException("项目代次必须非负");
            }
            return generation;
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("项目代次超出整数范围", invalid);
        }
    }

    /** 握手失败不保留身份或凭据状态。 */
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
        // 无外部握手状态需要回收；成功身份仅随物理会话存活。
    }
}
