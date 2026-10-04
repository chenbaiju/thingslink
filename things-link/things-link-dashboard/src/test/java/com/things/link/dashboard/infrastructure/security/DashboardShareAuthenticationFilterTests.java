package com.things.link.dashboard.infrastructure.security;

import com.things.link.dashboard.application.DashboardSharePrincipal;
import com.things.link.dashboard.application.DashboardShareRuntimeService;
import com.things.link.dashboard.application.sharing.DashboardShareProtectionService;
import com.things.link.dashboard.application.sharing.DashboardShareRuntimeProperties;
import com.things.link.dashboard.application.sharing.DashboardShareSecurityEvents;
import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 过滤器最低层证明默认关闭/Redis先于DB/响应结算前不flush；真实授权由Bootstrap集成覆盖。 */
class DashboardShareAuthenticationFilterTests {
    /** 显式测试可写目录，仅供启用配置构造校验，不冒称生产持久日志。 */
    @TempDir Path logs;
    /** 只模拟端口交互顺序，不将mock作为真实capability资格。 */
    private final DashboardShareRuntimeService runtime = mock(DashboardShareRuntimeService.class);
    /** 可单独注入Redis故障，证明没有继续查库。 */
    private final DashboardShareProtectionService protection = mock(DashboardShareProtectionService.class);
    /** 不让本层测试写真实诊断文件。 */
    private final DashboardShareSecurityEvents events = mock(DashboardShareSecurityEvents.class);
    /** 固定规范选择器与编码仅为测试输入。 */
    private final UUID id = UUID.randomUUID();

    /** disabled在任何Redis或数据库访问之前拒绝，不因为正确格式的token而静默开放。 */
    @Test void disabledNeverCallsRedisOrDatabase() throws Exception {
        var response = new MockHttpServletResponse();
        filter(false).doFilter(request(), response, (incoming, outgoing) -> { throw new AssertionError("禁用应停止"); });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("60055");
        verifyNoInteractions(runtime, protection);
    }

    /** 真实API未来路由不能因为namespace安全链存在而提前放行或触发token查库。 */
    @Test void unsupportedMethodAndRouteNeverReachLookup() throws Exception {
        for (String route : new String[] {"/api/v1/shares/" + id + "/devices/catalog", "/api/v1/shares/" + id + "/unknown"}) {
            var response = new MockHttpServletResponse();
            filter(true).doFilter(new MockHttpServletRequest("POST", route), response,
                    (incoming, outgoing) -> { throw new AssertionError("未交付路由不应执行"); });
            assertThat(response.getStatus()).isEqualTo(403);
        }
        verifyNoInteractions(runtime, protection);
    }

    /** 源桶Redis异常先于任何DB定位；错误不应退化成空404或继续沿Console本机额度运行。 */
    @Test void sourceFailurePreventsEveryDatabaseOperation() throws Exception {
        when(protection.acquireSource(anyString())).thenThrow(new BusinessException(DashboardErrorCode.SHARE_DEPENDENCY_UNAVAILABLE));
        var response = new MockHttpServletResponse();
        filter(true).doFilter(request(), response, (incoming, outgoing) -> { throw new AssertionError("Redis失败不得查库"); });
        assertThat(response.getStatus()).isEqualTo(503);
        verifyNoInteractions(runtime);
    }

    /** MVC即使flush也只能写有限内存，结算成功前原response不得含业务正文。 */
    @Test void buffersUntilReservationSettlementAndClearsRequestIdentity() throws Exception {
        var response = new MockHttpServletResponse();
        var reservation = authorize();
        doAnswer(invocation -> { assertThat(response.getContentAsByteArray()).isEmpty(); return null; })
                .when(reservation).finish(2);
        var request = request();
        filter(true).doFilter(request, response, (incoming, outgoing) -> {
            assertThat(incoming.getAttribute(DashboardSharePrincipal.class.getName())).isInstanceOf(DashboardSharePrincipal.class);
            outgoing.getWriter().write("{}");
            outgoing.flushBuffer();
            assertThat(response.getContentAsByteArray()).isEmpty();
        });
        assertThat(response.getContentAsString()).isEqualTo("{}");
        assertThat(request.getAttribute(DashboardSharePrincipal.class.getName())).isNull();
        verify(reservation).finish(2);
        verify(reservation).close();
    }

    /** 超单次值或结算Redis异常都在任何业务字节下发前转503；绝不先发半个Schema。 */
    @Test void overLimitAndSettlementFailureDoNotReleaseBufferedBody() throws Exception {
        var reservation = authorize();
        var tooLarge = new MockHttpServletResponse();
        filter(true).doFilter(request(), tooLarge, (incoming, outgoing) -> outgoing.getOutputStream().write(new byte[16385]));
        assertThat(tooLarge.getStatus()).isEqualTo(503);
        assertThat(tooLarge.getContentAsString()).contains("60055");
        doThrow(new BusinessException(DashboardErrorCode.SHARE_DEPENDENCY_UNAVAILABLE)).when(reservation).finish(anyLong());
        var unavailable = new MockHttpServletResponse();
        filter(true).doFilter(request(), unavailable, (incoming, outgoing) -> outgoing.getWriter().write("sensitive-value"));
        assertThat(unavailable.getStatus()).isEqualTo(503);
        assertThat(unavailable.getContentAsString()).doesNotContain("sensitive-value");
    }

    /** 已知身份连固定错误体也无额度时必须空429，不无限写未计量JSON突破字节硬上限。 */
    @Test void exhaustedKnownByteBudgetProducesHeadersOnly() throws Exception {
        authorize();
        when(protection.reserve(any(), any(), any(), anyLong()))
                .thenThrow(new BusinessException(DashboardErrorCode.SHARE_RATE_LIMITED));
        var response = new MockHttpServletResponse();
        filter(true).doFilter(request(), response, (incoming, outgoing) -> { throw new AssertionError("无额度不得读正文"); });
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    /** Servlet重定向/Location不能提前提交真实响应；最终只产生已计量的固定503错误。 */
    @Test void redirectAndLocationCannotBypassBufferedDelivery() throws Exception {
        authorize();
        var redirect = new MockHttpServletResponse();
        filter(true).doFilter(request(), redirect, (incoming, outgoing) ->
                ((jakarta.servlet.http.HttpServletResponse) outgoing).sendRedirect("https://untrusted.invalid"));
        assertThat(redirect.getStatus()).isEqualTo(503);
        assertThat(redirect.getHeader("Location")).isNull();
        var header = new MockHttpServletResponse();
        filter(true).doFilter(request(), header, (incoming, outgoing) ->
                ((jakarta.servlet.http.HttpServletResponse) outgoing).setHeader("Location", "https://untrusted.invalid"));
        assertThat(header.getStatus()).isEqualTo(503);
        assertThat(header.getHeader("Location")).isNull();
    }

    /** 使用规范secret与NONE策略，仅隔离此层响应预算，不假冒Referer真实性。 */
    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/api/v1/shares/" + id + "/context");
        request.addHeader("X-Share-Token", "sh_" + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
        return request;
    }
    /** 装配测试端口，生产类由独立安全链创建。 */
    private DashboardShareAuthenticationFilter filter(boolean enabled) {
        return new DashboardShareAuthenticationFilter(runtime, protection,
                new DashboardShareRuntimeProperties(enabled, "https://share.example.test", false, logs.toString()), events, new ObjectMapper());
    }
    /** 有效身份/许可仅为预算边界测试；HTTP/PG测试另验证实际查库结果。 */
    private DashboardShareProtectionService.Reservation authorize() {
        var principal = new DashboardSharePrincipal(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 0, Instant.now().plusSeconds(3600), "NONE", "a".repeat(64));
        when(runtime.authenticate(any(), any())).thenReturn(principal);
        when(protection.acquireSource(anyString())).thenReturn(mock(DashboardShareProtectionService.SourcePermit.class));
        var reservation = mock(DashboardShareProtectionService.Reservation.class);
        when(protection.reserve(any(), any(), any(), anyLong())).thenReturn(reservation);
        return reservation;
    }
}
