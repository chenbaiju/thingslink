package com.things.link.ingestion.infrastructure.websocket;

import com.things.link.ingestion.application.RealtimeAuthorizationService;
import com.things.link.ingestion.application.RealtimePrincipal;
import com.things.link.ingestion.application.RealtimeProjectAccessDeniedException;
import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.socket.WebSocketHandler;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 握手必须把令牌有效性与项目权威授权分层，并把JWT截止写入连接身份。 */
class RealtimeHandshakeInterceptorTests {

    /** 有效JWT及项目成员通过后才建立身份，且只保存公开协议需要的最小字段。 */
    @Test
    void authorizesProjectAndPreservesJwtExpiryInPrincipal() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        RealtimeAuthorizationService authorization = mock(RealtimeAuthorizationService.class);
        RealtimeHandshakeInterceptor interceptor = new RealtimeHandshakeInterceptor(decoder, authorization);
        UUID accountId = Uuid7.generate();
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        Instant expiresAt = Instant.parse("2030-01-01T00:00:00Z");
        Jwt jwt = jwt(accountId, tenantId, projectId, 7L, expiresAt);
        when(decoder.decode("signed-token")).thenReturn(jwt);
        Exchange exchange = exchange("tc-v1, bearer.signed-token");
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(exchange.request(), exchange.response(),
                mock(WebSocketHandler.class), attributes);

        RealtimePrincipal principal = (RealtimePrincipal) attributes.get(
                RealtimeHandshakeInterceptor.PRINCIPAL_ATTRIBUTE);
        assertThat(accepted).isTrue();
        assertThat(principal).isEqualTo(new RealtimePrincipal(accountId, tenantId, projectId, 7L, expiresAt));
        verify(authorization).requireProjectAccess(principal);
    }

    /** ADR0073前签发的JWT没有pgv时按0解释，且仍须进入权威项目复核。 */
    @Test
    void defaultsMissingProjectGenerationToZero() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        RealtimeAuthorizationService authorization = mock(RealtimeAuthorizationService.class);
        RealtimeHandshakeInterceptor interceptor = new RealtimeHandshakeInterceptor(decoder, authorization);
        UUID accountId = Uuid7.generate();
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        Instant expiresAt = Instant.parse("2030-01-01T00:00:00Z");
        when(decoder.decode("signed-token")).thenReturn(jwt(accountId, tenantId, projectId, null, expiresAt));
        Exchange exchange = exchange("tc-v1, bearer.signed-token");
        Map<String, Object> attributes = new HashMap<>();

        assertThat(interceptor.beforeHandshake(exchange.request(), exchange.response(),
                mock(WebSocketHandler.class), attributes)).isTrue();

        RealtimePrincipal principal = (RealtimePrincipal) attributes.get(
                RealtimeHandshakeInterceptor.PRINCIPAL_ATTRIBUTE);
        assertThat(principal.projectGeneration()).isZero();
        verify(authorization).requireProjectAccess(principal);
    }

    /** 负数、小数或字符串pgv都属于无效JWT身份，必须401且不能访问项目数据库。 */
    @Test
    void rejectsNegativeOrNonIntegerProjectGenerationBeforeAuthorization() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        RealtimeAuthorizationService authorization = mock(RealtimeAuthorizationService.class);
        RealtimeHandshakeInterceptor interceptor = new RealtimeHandshakeInterceptor(decoder, authorization);
        UUID accountId = Uuid7.generate();
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        Instant expiresAt = Instant.parse("2030-01-01T00:00:00Z");

        for (Object invalidGeneration : java.util.List.of(-1L, 1.5D, "1")) {
            when(decoder.decode("signed-token"))
                    .thenReturn(jwt(accountId, tenantId, projectId, invalidGeneration, expiresAt));
            Exchange exchange = exchange("tc-v1, bearer.signed-token");
            Map<String, Object> attributes = new HashMap<>();

            assertThat(interceptor.beforeHandshake(exchange.request(), exchange.response(),
                    mock(WebSocketHandler.class), attributes)).isFalse();
            assertThat(exchange.servletResponse().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            assertThat(attributes).doesNotContainKey(RealtimeHandshakeInterceptor.PRINCIPAL_ATTRIBUTE);
        }
        verify(authorization, never()).requireProjectAccess(org.mockito.ArgumentMatchers.any());
    }

    /** 合法签名但项目已删除或成员失效属于403，不能伪装成令牌401。 */
    @Test
    void mapsAuthoritativeProjectDenialToForbidden() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        RealtimeAuthorizationService authorization = mock(RealtimeAuthorizationService.class);
        RealtimeHandshakeInterceptor interceptor = new RealtimeHandshakeInterceptor(decoder, authorization);
        UUID accountId = Uuid7.generate();
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        RealtimePrincipal principal = new RealtimePrincipal(accountId, tenantId, projectId,
                Instant.parse("2030-01-01T00:00:00Z"));
        when(decoder.decode("signed-token")).thenReturn(
                jwt(accountId, tenantId, projectId, 0L, principal.expiresAt()));
        doThrow(new RealtimeProjectAccessDeniedException(new IllegalStateException("denied")))
                .when(authorization).requireProjectAccess(principal);
        Exchange exchange = exchange("tc-v1, bearer.signed-token");

        boolean accepted = interceptor.beforeHandshake(exchange.request(), exchange.response(),
                mock(WebSocketHandler.class), new HashMap<>());

        assertThat(accepted).isFalse();
        assertThat(exchange.servletResponse().getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    /** 协议形状或JWT时效无效返回401，并且绝不进入项目数据库授权。 */
    @Test
    void rejectsInvalidProtocolAndExpiredTokenBeforeProjectAuthorization() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        RealtimeAuthorizationService authorization = mock(RealtimeAuthorizationService.class);
        RealtimeHandshakeInterceptor interceptor = new RealtimeHandshakeInterceptor(decoder, authorization);
        Exchange wrongProtocol = exchange("bearer.token, tc-v1");

        assertThat(interceptor.beforeHandshake(wrongProtocol.request(), wrongProtocol.response(),
                mock(WebSocketHandler.class), new HashMap<>())).isFalse();
        assertThat(wrongProtocol.servletResponse().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(decoder, never()).decode("token");

        when(decoder.decode("expired-token")).thenThrow(new JwtException("expired"));
        Exchange expired = exchange("tc-v1, bearer.expired-token");
        assertThat(interceptor.beforeHandshake(expired.request(), expired.response(),
                mock(WebSocketHandler.class), new HashMap<>())).isFalse();
        assertThat(expired.servletResponse().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(authorization, never()).requireProjectAccess(org.mockito.ArgumentMatchers.any());
    }

    /** 构造与生产解码结果同形的JWT对象。 */
    private static Jwt jwt(UUID accountId, UUID tenantId, UUID projectId, Object projectGeneration,
                           Instant expiresAt) {
        var builder = Jwt.withTokenValue("signed-token")
                .header("alg", "HS256")
                .subject(accountId.toString())
                .issuedAt(Instant.parse("2026-09-04T00:00:00Z"))
                .expiresAt(expiresAt)
                .claim("tid", tenantId.toString())
                .claim("pid", projectId.toString());
        if (projectGeneration != null) {
            builder.claim("pgv", projectGeneration);
        }
        return builder.build();
    }

    /** 用Servlet适配器覆盖生产HandshakeInterceptor实际接收的HTTP接口。 */
    private static Exchange exchange(String protocols) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader("Sec-WebSocket-Protocol", protocols);
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        return new Exchange(new ServletServerHttpRequest(servletRequest),
                new ServletServerHttpResponse(servletResponse), servletResponse);
    }

    /** 握手HTTP请求、响应与可观察Servlet状态。 */
    private record Exchange(ServletServerHttpRequest request, ServletServerHttpResponse response,
                            MockHttpServletResponse servletResponse) {
    }
}
