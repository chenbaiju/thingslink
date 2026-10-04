package com.things.link.ingestion.infrastructure.protocol.http;

import com.things.link.ingestion.application.access.DeviceAccessAuthBudget;
import com.things.link.ingestion.application.access.DeviceAccessAuthFailureReason;
import com.things.link.ingestion.application.access.DeviceAccessDeviceAuthenticationException;
import com.things.link.ingestion.application.access.DeviceAccessDeviceAuthenticator;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.message.TransportProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 设备面认证过滤器的传输、凭据头与拒绝映射；不启动容器也不连接数据库。 */
class DeviceAccessHttpAuthenticationFilterTests {

    /** 设备密钥明文。 */
    private static final String SECRET = "s".repeat(64);

    /** 认证端口替身。 */
    private DeviceAccessDeviceAuthenticator authenticator;

    /** 认证面预算替身。 */
    private DeviceAccessAuthBudget authBudget;

    /** 每个用例重建替身并默认放行预算。 */
    @BeforeEach
    void setUp() {
        authenticator = mock(DeviceAccessDeviceAuthenticator.class);
        authBudget = mock(DeviceAccessAuthBudget.class);
        when(authBudget.check(any(), anyString(), anyString())).thenReturn(DeviceAccessAuthBudget.Decision.ALLOW);
    }

    /** 缺少凭据头：拒绝且不进入业务链，也不触碰认证端口。 */
    @Test
    void missingHeadersAreRejectedWithoutAuthenticating() throws Exception {
        Execution execution = execute(filter(false), request(false, null, null));

        assertThat(execution.response().getStatus()).isEqualTo(401);
        assertThat(execution.body()).contains("AUTH_REQUIRED");
        assertThat(execution.chainInvoked()).isFalse();
        verifyNoInteractions(authenticator);
    }

    /** 非 TLS 且未开测试开关：设备密钥是明文凭据，必须拒绝。 */
    @Test
    void insecureTransportIsRejectedUnlessLoopbackBypassEnabled() throws Exception {
        Execution rejected = execute(filter(false), request(false, "project/device", SECRET));

        assertThat(rejected.response().getStatus()).isEqualTo(401);
        assertThat(rejected.body()).contains("必须使用 HTTPS");
        assertThat(rejected.chainInvoked()).isFalse();

        when(authenticator.authenticate(any(), anyString(), anyString(), anyString()))
                .thenReturn(identity());
        MockHttpServletRequest loopbackRequest = request(false, "project/device", SECRET);
        loopbackRequest.setRemoteAddr("127.0.0.1");
        Execution allowed = execute(filter(true), loopbackRequest);
        assertThat(allowed.chainInvoked()).as("测试专用开关放行回环明文").isTrue();
    }

    /** 身份格式不合法：按凭据失败拒绝，不进入认证端口。 */
    @Test
    void malformedDeviceKeyIsRejected() throws Exception {
        Execution execution = execute(filter(true), request(true, "only-project", SECRET));

        assertThat(execution.response().getStatus()).isEqualTo(401);
        assertThat(execution.body()).contains("AUTH_FAILED");
        verifyNoInteractions(authenticator);
    }

    /** 预算耗尽与退避：都返回 429 并带 Retry-After，业务链不执行。 */
    @Test
    void budgetRejectionsCarryRetryAfter() throws Exception {
        when(authBudget.check(any(), anyString(), anyString()))
                .thenReturn(DeviceAccessAuthBudget.Decision.RATE_LIMITED);
        Execution limited = execute(filter(true), request(true, "project/device", SECRET));
        assertThat(limited.response().getStatus()).isEqualTo(429);
        assertThat(limited.body()).contains("RATE_LIMITED");
        assertThat(limited.response().getHeader("Retry-After")).isEqualTo("1");
        assertThat(limited.chainInvoked()).isFalse();

        when(authBudget.check(any(), anyString(), anyString()))
                .thenReturn(DeviceAccessAuthBudget.Decision.BACKOFF);
        when(authBudget.remainingBackoffMillis(anyString(), anyString())).thenReturn(2_500L);
        Execution backoff = execute(filter(true), request(true, "project/device", SECRET));
        assertThat(backoff.response().getStatus()).isEqualTo(429);
        assertThat(backoff.response().getHeader("Retry-After")).isEqualTo("2");
        verifyNoInteractions(authenticator);
    }

    /** 认证失败与平面未开通分别映射 401 与 403，并记录一次失败用于退避。 */
    @Test
    void authenticationFailuresMapToFrozenStatuses() throws Exception {
        // 必须用 doThrow 重设桩：when(...) 形式会先调用既有桩，把它刚抛出的异常再抛一次。
        doThrow(new DeviceAccessDeviceAuthenticationException(DeviceAccessAuthFailureReason.AUTH_FAILED))
                .when(authenticator).authenticate(any(), anyString(), anyString(), anyString());
        Execution unauthorized = execute(filter(true), request(true, "project/device", SECRET));
        assertThat(unauthorized.response().getStatus()).isEqualTo(401);
        assertThat(unauthorized.body()).contains("AUTH_FAILED");
        verify(authBudget).recordFailure("project", "device");

        doThrow(new DeviceAccessDeviceAuthenticationException(DeviceAccessAuthFailureReason.PLANE_NOT_ENABLED))
                .when(authenticator).authenticate(any(), anyString(), anyString(), anyString());
        Execution disabled = execute(filter(true), request(true, "project/device", SECRET));
        assertThat(disabled.response().getStatus()).isEqualTo(403);
        assertThat(disabled.body()).contains("DEVICE_DISABLED");
    }

    /** 认证成功：身份写入请求属性、清除退避并放行到业务链。 */
    @Test
    void successfulAuthenticationPopulatesRequestAttribute() throws Exception {
        AuthenticatedDeviceIdentity identity = identity();
        when(authenticator.authenticate(any(), anyString(), anyString(), anyString())).thenReturn(identity);
        MockHttpServletRequest request = request(true, "project/device", SECRET);

        Execution execution = execute(filter(true), request);

        assertThat(execution.chainInvoked()).isTrue();
        assertThat(request.getAttribute(DeviceAccessHttpAuthenticationFilter.IDENTITY_ATTRIBUTE))
                .isEqualTo(identity);
        verify(authBudget).recordSuccess("project", "device");
    }

    /** 错误响应体只含错误码与说明，绝不回显密钥。 */
    @Test
    void errorBodyNeverEchoesSecret() throws Exception {
        Execution execution = execute(filter(true), request(true, "project/device", SECRET));

        assertThat(execution.body()).doesNotContain(SECRET);
    }

    /**
     * 构造过滤器。
     *
     * @param allowInsecureLoopback 是否放行回环明文
     * @return 认证过滤器
     */
    private DeviceAccessHttpAuthenticationFilter filter(boolean allowInsecureLoopback) {
        return new DeviceAccessHttpAuthenticationFilter(authenticator, authBudget, allowInsecureLoopback);
    }

    /**
     * 构造请求。
     *
     * @param secure 是否 TLS
     * @param deviceKey 设备身份头；为空表示不携带
     * @param secret 密钥头；为空表示不携带
     * @return 请求
     */
    private static MockHttpServletRequest request(boolean secure, String deviceKey, String secret) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/device-access/v1/property/report");
        request.setSecure(secure);
        request.setRemoteAddr("10.20.30.40");
        if (deviceKey != null) {
            request.addHeader(DeviceAccessHttpAuthenticationFilter.DEVICE_KEY_HEADER, deviceKey);
        }
        if (secret != null) {
            request.addHeader(DeviceAccessHttpAuthenticationFilter.DEVICE_SECRET_HEADER, secret);
        }
        return request;
    }

    /**
     * 执行一次过滤器调用。
     *
     * @param filter 过滤器
     * @param request 请求
     * @return 结果
     * @throws Exception 过滤器执行失败
     */
    private static Execution execute(DeviceAccessHttpAuthenticationFilter filter, MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        boolean invoked = chain.getRequest() != null;
        return new Execution(response, invoked);
    }

    /**
     * 认证身份替身。
     *
     * @return 认证身份
     */
    private static AuthenticatedDeviceIdentity identity() {
        UUID tenantId = Uuid7.generate();
        return new AuthenticatedDeviceIdentity(tenantId, Uuid7.generate(), Uuid7.generate(), 1);
    }

    /**
     * 一次过滤器执行的结果。
     *
     * @param response 响应
     * @param chainInvoked 业务链是否被执行
     */
    private record Execution(MockHttpServletResponse response, boolean chainInvoked) {

        /**
         * @return 响应体文本
         */
        String body() {
            try {
                return response.getContentAsString(StandardCharsets.UTF_8);
            } catch (java.io.UnsupportedEncodingException exception) {
                throw new IllegalStateException("测试响应体编码固定为 UTF-8", exception);
            }
        }
    }
}
