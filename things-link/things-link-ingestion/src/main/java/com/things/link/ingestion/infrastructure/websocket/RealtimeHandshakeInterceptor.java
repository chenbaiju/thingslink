package com.things.link.ingestion.infrastructure.websocket;

import com.things.link.ingestion.application.RealtimeAuthorizationService;
import com.things.link.ingestion.application.RealtimePrincipal;
import com.things.link.ingestion.application.RealtimeProjectAccessDeniedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 从原生 WebSocket 可发送的 {@code Sec-WebSocket-Protocol} 头校验控制台 JWT。
 *
 * <p>浏览器 API 无法添加 {@code Authorization}，而 JWT 放 URL query 会进入访问日志、代理日志和
 * 可能的 Referer。故冻结两个子协议值 {@code tc-v1} 与 {@code bearer.<JWT>}；握手成功只协商
 * {@code tc-v1}，绝不把 bearer 值回显给客户端。</p>
 */
@Component
public class RealtimeHandshakeInterceptor implements HandshakeInterceptor {

    /** 子协议的公开版本标识。 */
    public static final String APPLICATION_PROTOCOL = "tc-v1";

    /** bearer 子协议前缀，JWT 本身不含空格以符合 HTTP token 语法。 */
    private static final String BEARER_PROTOCOL_PREFIX = "bearer.";

    /** WebSocket 会话属性 中保存的已校验身份。 */
    public static final String PRINCIPAL_ATTRIBUTE = RealtimePrincipal.class.getName();

    /** 平台 JwtDecoder，使用 iam 装配的 HS256 验签与有效期校验。 */
    private final JwtDecoder jwtDecoder;

    /** 项目成员与生命周期权威复核。 */
    private final RealtimeAuthorizationService authorizationService;

    /**
     * @param jwtDecoder 已配置的 JWT 校验器
     * @param authorizationService 项目成员与生命周期权威复核
     */
    public RealtimeHandshakeInterceptor(JwtDecoder jwtDecoder,
                                        RealtimeAuthorizationService authorizationService) {
        this.jwtDecoder = jwtDecoder;
        this.authorizationService = authorizationService;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler webSocketHandler, Map<String, Object> attributes) {
        try {
            List<String> protocols = requestedProtocols(request.getHeaders());
            if (protocols.size() != 2 || !APPLICATION_PROTOCOL.equals(protocols.getFirst())
                    || !protocols.get(1).startsWith(BEARER_PROTOCOL_PREFIX)) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            String token = protocols.get(1).substring(BEARER_PROTOCOL_PREFIX.length());
            Jwt jwt = jwtDecoder.decode(token);
            UUID accountId = UUID.fromString(jwt.getSubject());
            UUID tenantId = UUID.fromString(jwt.getClaimAsString("tid"));
            String project = jwt.getClaimAsString("pid");
            if (project == null || project.isBlank()) {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
            RealtimePrincipal principal = new RealtimePrincipal(
                    accountId, tenantId, UUID.fromString(project), projectGeneration(jwt), jwt.getExpiresAt());
            // ADR0072：验签只证明令牌来源，握手还必须权威复核项目当前可读资格。
            authorizationService.requireProjectAccess(principal);
            attributes.put(PRINCIPAL_ATTRIBUTE, principal);
            return true;
        } catch (RealtimeProjectAccessDeniedException denied) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        } catch (JwtException | IllegalArgumentException | NullPointerException invalidIdentity) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        } catch (RuntimeException unavailable) {
            // 依赖故障不能伪装成令牌错误或项目确定失权；握手尚未建立，直接要求客户端稍后重试。
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler webSocketHandler, Exception exception) {
        // 身份仅在成功握手的 WebSocket attributes 存活；HTTP 请求完成后不保留任何状态。
    }

    /** 拆开合并或分行的 Sec-WebSocket-Protocol 头，顺序本身也是防止协议降级的约束。 */
    private static List<String> requestedProtocols(HttpHeaders headers) {
        List<String> result = new ArrayList<>();
        headers.getOrEmpty("Sec-WebSocket-Protocol").forEach(value -> {
            for (String protocol : value.split(",")) {
                String normalized = protocol.trim();
                if (!normalized.isEmpty()) {
                    result.add(normalized);
                }
            }
        });
        return result;
    }

    /** ADR0073：历史JWT缺失pgv只兼容0代项目；负数或非整数声明属于无效身份。 */
    private static long projectGeneration(Jwt jwt) {
        Object claim = jwt.getClaim("pgv");
        if (claim == null) {
            return 0L;
        }
        if (!(claim instanceof Number number)) {
            throw new IllegalArgumentException("项目生命周期代次声明必须为整数");
        }
        long generation = number.longValue();
        if (generation < 0 || number.doubleValue() != generation) {
            throw new IllegalArgumentException("项目生命周期代次声明必须为非负整数");
        }
        return generation;
    }
}
