package com.things.link.ingestion.infrastructure.websocket;

import com.things.link.enduser.application.AppAuthenticatedPrincipal;
import com.things.link.enduser.application.AppRealtimeAccessService;
import com.things.link.ingestion.application.DashboardRealtimePrincipal;
import com.things.link.project.application.AccountDirectory;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.junit.jupiter.api.Test;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** ADR0099握手拒绝反例：身份、来源与公开协议必须分别成立。 */
class DashboardHandshakeInterceptorTests {
    /** 测试来源只用于显式配置，不代表生产默认允许loopback。 */
    private static final String ORIGIN = "http://localhost:3007";
    /** 单个账户或App身份的固定标识。 */
    private static final UUID SUBJECT = UUID.fromString("01900000-0000-7000-8000-000000000001");
    /** 声明中的租户标识。 */
    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-000000000002");
    /** App的强制项目标识。 */
    private static final UUID PROJECT = UUID.fromString("01900000-0000-7000-8000-000000000003");

    /** App握手调用权威身份端口并仅保存最小身份。 */
    @Test
    void appIdentityIsRevalidatedAndCredentialIsNotStored() {
        Fixture fixture = fixture(true);
        Jwt jwt = token(true, List.of("things-link-app"), 3L, Instant.now().plusSeconds(60));
        when(fixture.decoder().decode("signed-token")).thenReturn(jwt);
        assertThat(fixture.accept()).isTrue();
        assertThat(fixture.attributes()).containsOnlyKeys(DashboardHandshakeInterceptor.PRINCIPAL_ATTRIBUTE);
        verify(fixture.appAccess()).validateIdentity(new AppAuthenticatedPrincipal(TENANT, PROJECT, SUBJECT, 3L));
        verifyNoInteractions(fixture.accounts());
    }

    /** Console未提供project时只检查账号，不能假装已经项目确权。 */
    @Test
    void consoleWithoutProjectOnlyVerifiesAccount() {
        Fixture fixture = fixture(false);
        when(fixture.decoder().decode("signed-token"))
                .thenReturn(token(false, List.of(), 0L, Instant.now().plusSeconds(60)));
        when(fixture.accounts().isActive(SUBJECT)).thenReturn(true);
        assertThat(fixture.accept()).isTrue();
        DashboardRealtimePrincipal principal = (DashboardRealtimePrincipal) fixture.attributes()
                .get(DashboardHandshakeInterceptor.PRINCIPAL_ATTRIBUTE);
        assertThat(principal.projectId()).isNull();
        verifyNoInteractions(fixture.appAccess());
    }

    /** 缺失、null、多值、组合值和不同Origin均在解码之前拒绝。 */
    @Test
    void rejectsEveryUntrustedOriginBeforeDecoding() {
        for (List<String> origins : List.of(List.<String>of(), List.of("null"),
                List.of(ORIGIN, ORIGIN), List.of(ORIGIN + ", https://evil.example"),
                List.of("https://evil.example"), List.of(ORIGIN + "/"))) {
            Fixture fixture = fixture(true);
            fixture.request().removeHeader("Origin");
            origins.forEach(origin -> fixture.request().addHeader("Origin", origin));
            assertThat(fixture.accept()).as("origin %s", origins).isFalse();
            assertThat(fixture.response().getStatus()).isEqualTo(403);
            verifyNoInteractions(fixture.decoder(), fixture.appAccess(), fixture.accounts());
        }
    }

    /** 子协议必须恰好公开协议和一个凭据，空段和其他版本不能混入。 */
    @Test
    void rejectsMissingDuplicateAndMixedProtocols() {
        for (String protocols : List.of("bearer.signed-token", "tc-v1, bearer.signed-token",
                "tc.app.properties.v1, bearer.signed-token,", "tc.app.properties.v1, tc.app.properties.v1",
                "tc.app.properties.v1, bearer.a, bearer.b", "tc.dashboard.properties.v1, bearer.signed-token")) {
            Fixture fixture = fixture(true);
            fixture.request().removeHeader("Sec-WebSocket-Protocol");
            fixture.request().addHeader("Sec-WebSocket-Protocol", protocols);
            assertThat(fixture.accept()).as(protocols).isFalse();
            assertThat(fixture.response().getStatus()).isEqualTo(401);
            verifyNoInteractions(fixture.decoder());
        }
    }

    /** 专用decoder拒绝另一类签名，错误受众、旧无受众与多受众均不进入授权。 */
    @Test
    void rejectsWrongAndMissingAppAudience() {
        for (List<String> audiences : List.of(List.<String>of(), List.of("things-link"),
                List.of("things-link-app", "things-link"))) {
            Fixture fixture = fixture(true);
            when(fixture.decoder().decode("signed-token"))
                    .thenReturn(token(true, audiences, 0L, Instant.now().plusSeconds(60)));
            assertThat(fixture.accept()).isFalse();
            assertThat(fixture.response().getStatus()).isEqualTo(401);
            verifyNoInteractions(fixture.appAccess());
        }
        Fixture fixture = fixture(true);
        when(fixture.decoder().decode("signed-token")).thenThrow(new JwtException("invalid signature"));
        assertThat(fixture.accept()).isFalse();
        verifyNoInteractions(fixture.appAccess());
    }

    /** 新握手不沿用JWT时钟偏差把已到期会话复活，错误issuer也拒绝。 */
    @Test
    void rejectsExpiredTokenAndWrongIssuer() {
        Fixture expired = fixture(true);
        when(expired.decoder().decode("signed-token"))
                .thenReturn(token(true, List.of("things-link-app"), 0L, Instant.now().minusSeconds(1)));
        assertThat(expired.accept()).isFalse();
        Fixture wrongIssuer = fixture(true);
        Jwt valid = token(true, List.of("things-link-app"), 0L, Instant.now().plusSeconds(60));
        when(wrongIssuer.decoder().decode("signed-token")).thenReturn(Jwt.withTokenValue("signed-token")
                .headers(headers -> headers.putAll(valid.getHeaders()))
                .claims(claims -> claims.putAll(valid.getClaims()))
                .claim("iss", "things-link").build());
        assertThat(wrongIssuer.accept()).isFalse();
        verifyNoInteractions(expired.appAccess(), wrongIssuer.appAccess());
    }

    /** 代次不能按浮点或long溢出截断。 */
    @Test
    void rejectsMalformedProjectGeneration() {
        for (Object generation : List.of(-1L, 1.5, "1", 9223372036854775808.0)) {
            Fixture fixture = fixture(true);
            when(fixture.decoder().decode("signed-token"))
                    .thenReturn(token(true, List.of("things-link-app"), generation, Instant.now().plusSeconds(60)));
            assertThat(fixture.accept()).isFalse();
            assertThat(fixture.response().getStatus()).isEqualTo(401);
            verifyNoInteractions(fixture.appAccess());
        }
    }

    /** 已停用账号拒绝；数据库故障应保留503而非伪装认证错误。 */
    @Test
    void rejectsInactiveAccountAndPreservesInfrastructureFailure() {
        Fixture inactive = fixture(false);
        when(inactive.decoder().decode("signed-token"))
                .thenReturn(token(false, List.of(), 0L, Instant.now().plusSeconds(60)));
        assertThat(inactive.accept()).isFalse();
        assertThat(inactive.response().getStatus()).isEqualTo(403);
        Fixture unavailable = fixture(true);
        when(unavailable.decoder().decode("signed-token"))
                .thenReturn(token(true, List.of("things-link-app"), 0L, Instant.now().plusSeconds(60)));
        doThrow(new IllegalStateException("database unavailable")).when(unavailable.appAccess())
                .validateIdentity(new AppAuthenticatedPrincipal(TENANT, PROJECT, SUBJECT, 0L));
        assertThat(unavailable.accept()).isFalse();
        assertThat(unavailable.response().getStatus()).isEqualTo(503);
    }

    /** 角色、用户或项目代次权威拒绝不能留下已认证会话。 */
    @Test
    void rejectsRevokedAppIdentityWithoutPublishingPrincipal() {
        Fixture fixture = fixture(true);
        when(fixture.decoder().decode("signed-token"))
                .thenReturn(token(true, List.of("things-link-app"), 0L, Instant.now().plusSeconds(60)));
        doThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND)).when(fixture.appAccess())
                .validateIdentity(new AppAuthenticatedPrincipal(TENANT, PROJECT, SUBJECT, 0L));
        assertThat(fixture.accept()).isFalse();
        assertThat(fixture.response().getStatus()).isEqualTo(403);
        assertThat(fixture.attributes()).isEmpty();
    }

    /** 公开协议协商不依赖请求顺序，且永不回显bearer。 */
    @Test
    void onlyNegotiatesPublicProtocol() {
        DashboardHandshakeHandler handler = new DashboardHandshakeHandler(DashboardHandshakeInterceptor.APP_PROTOCOL);
        assertThat(handler.selectProtocol(List.of("bearer.secret", DashboardHandshakeInterceptor.APP_PROTOCOL),
                mock(WebSocketHandler.class))).isEqualTo(DashboardHandshakeInterceptor.APP_PROTOCOL);
        assertThat(handler.selectProtocol(List.of("bearer.secret"), mock(WebSocketHandler.class))).isNull();
    }

    /** 漏配来源关闭入口，通配与URL路径不能伪装Origin配置。 */
    @Test
    void originConfigurationIsFailClosedAndExact() {
        assertThat(new DashboardWebSocketProperties(null, null).appAllowedOrigins()).isEmpty();
        for (String invalid : List.of("*", "https://*.example.com", "https://example.com/path", "null",
                "https://user@example.com", "https://example.com?query=1", "https://example.com#fragment")) {
            assertThatThrownBy(() -> new DashboardWebSocketProperties(List.of(invalid), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** v2在旧App端点拒绝，独立端点仅接受自身公开协议和同一严格App身份。 */
    @Test
    void appDashboardProtocolIsExplicitAndNotNegotiableOnOldEndpoint() {
        Fixture old = fixture(true);
        old.request().removeHeader("Sec-WebSocket-Protocol");
        old.request().addHeader("Sec-WebSocket-Protocol", DashboardHandshakeInterceptor.APP_DASHBOARD_PROTOCOL + ", bearer.signed-token");
        assertThat(old.accept()).isFalse(); verifyNoInteractions(old.decoder());
        DashboardHandshakeInterceptor v2 = new DashboardHandshakeInterceptor(true, old.decoder(), "things-link-app", List.of(ORIGIN),
                old.appAccess(), old.accounts(), DashboardHandshakeInterceptor.APP_DASHBOARD_PROTOCOL);
        when(old.decoder().decode("signed-token")).thenReturn(token(true, List.of("things-link-app"), 3L, Instant.now().plusSeconds(60)));
        assertThat(v2.beforeHandshake(new ServletServerHttpRequest(old.request()), new ServletServerHttpResponse(new MockHttpServletResponse()),
                mock(WebSocketHandler.class), new HashMap<>())).isTrue();
        old.request().removeHeader("Sec-WebSocket-Protocol"); old.request().addHeader("Sec-WebSocket-Protocol", DashboardHandshakeInterceptor.APP_PROTOCOL + ", bearer.signed-token");
        assertThat(v2.beforeHandshake(new ServletServerHttpRequest(old.request()), new ServletServerHttpResponse(new MockHttpServletResponse()),
                mock(WebSocketHandler.class), new HashMap<>())).isFalse();
    }

    /** 构造有界固定声明，签名真伪由decoder反例单独覆盖。 */
    private static Jwt token(boolean app, List<String> audience, Object generation, Instant expiresAt) {
        Jwt.Builder builder = Jwt.withTokenValue("signed-token").header("alg", "HS256")
                .subject(SUBJECT.toString()).claim("iss", app ? "things-link-app" : "things-link")
                .claim("tid", TENANT.toString()).claim("pgv", generation).expiresAt(expiresAt);
        if (!audience.isEmpty()) {
            builder.claim("aud", audience);
        }
        if (app) {
            builder.claim("pid", PROJECT.toString());
        }
        return builder.build();
    }

    /** 每个反例使用独立依赖，避免上一个授权调用掩盖本次是否进入权威端口。 */
    private static Fixture fixture(boolean app) {
        JwtDecoder decoder = mock(JwtDecoder.class);
        AppRealtimeAccessService appAccess = mock(AppRealtimeAccessService.class);
        AccountDirectory accounts = mock(AccountDirectory.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/app/properties");
        request.addHeader("Origin", ORIGIN);
        request.addHeader("Sec-WebSocket-Protocol", (app ? DashboardHandshakeInterceptor.APP_PROTOCOL
                : DashboardHandshakeInterceptor.CONSOLE_PROTOCOL) + ", bearer.signed-token");
        return new Fixture(new DashboardHandshakeInterceptor(app, decoder,
                app ? "things-link-app" : "things-link", List.of(ORIGIN), appAccess, accounts),
                decoder, appAccess, accounts, request, new MockHttpServletResponse(), new HashMap<>());
    }

    /** 握手交换的局部夹具，不启动Web容器。 */
    private record Fixture(DashboardHandshakeInterceptor interceptor, JwtDecoder decoder,
                           AppRealtimeAccessService appAccess, AccountDirectory accounts,
                           MockHttpServletRequest request, MockHttpServletResponse response,
                           Map<String, Object> attributes) {
        /** 执行真实拦截器，响应状态与attributes均可断言。 */
        boolean accept() {
            return interceptor.beforeHandshake(new ServletServerHttpRequest(request),
                    new ServletServerHttpResponse(response), mock(WebSocketHandler.class), attributes);
        }
    }
}
