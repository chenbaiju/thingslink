package com.things.link.dashboard.infrastructure.security;

import com.things.link.dashboard.application.DashboardSharePrincipal;
import com.things.link.dashboard.application.DashboardShareRuntimeService;
import com.things.link.dashboard.application.sharing.DashboardShareProtectionService;
import com.things.link.dashboard.application.sharing.DashboardShareRuntimeProperties;
import com.things.link.dashboard.application.sharing.DashboardShareSecurityEvents;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 数据REST的精确路由与字节保护纯测试；实际授权另由真实PG/Redis集成证明。 */
class DashboardShareDataRouteFilterTests {
    /** 测试专用日志目录满足启用配置，不声明为生产日志。 */
    @TempDir Path logs;
    /** 固定选择器只用于过滤器输入。 */
    private final UUID shareId = UUID.randomUUID();
    /** 模拟事务服务用于证明拒绝发生在定位之前。 */
    private final DashboardShareRuntimeService runtime = mock(DashboardShareRuntimeService.class);
    /** 最低层不运行Redis，仅校验预算调用与真实下发顺序。 */
    private final DashboardShareProtectionService protection = mock(DashboardShareProtectionService.class);

    /** 五条路由只允许冻结方法，邻接路径与方法不能借namespace获得访问。 */
    @Test void matchesOnlyDeliveredDataMethodsAndCanonicalPaths() {
        for (String suffix : new String[] {"devices/snapshots/query", "devices/current-values/query", "alarms/query"}) {
            assertThat(DashboardShareAuthenticationFilter.isReadableRoute(request("POST", suffix))).isTrue();
            assertThat(DashboardShareAuthenticationFilter.isReadableRoute(request("GET", suffix))).isFalse();
        }
        String history = "devices/" + UUID.randomUUID() + "/properties/temperature/history";
        for (String suffix : new String[] {"devices/catalog", history}) {
            assertThat(DashboardShareAuthenticationFilter.isReadableRoute(request("GET", suffix))).isTrue();
            assertThat(DashboardShareAuthenticationFilter.isReadableRoute(request("POST", suffix))).isFalse();
            assertThat(DashboardShareAuthenticationFilter.isReadableRoute(request("GET", suffix + "/"))).isFalse();
        }
        assertThat(DashboardShareAuthenticationFilter.isReadableRoute(request("GET", "devices/not-a-uuid/properties/key/history"))).isFalse();
        assertThat(DashboardShareAuthenticationFilter.isReadableRoute(request("PUT", "alarms/query"))).isFalse();
    }

    /** 数据响应可到4MiB且先结算后发送，旧context16KiB限制不错误套用于数据路由。 */
    @Test void reservesFourMebibytesAndSettlesExactPayload() throws Exception {
        var reservation = authorize();
        var response = new MockHttpServletResponse();
        byte[] body = new byte[4 * 1024 * 1024];
        filter().doFilter(request("POST", "devices/current-values/query"), response, (incoming, outgoing) -> {
            outgoing.getOutputStream().write(body);
            outgoing.flushBuffer();
            assertThat(response.getContentAsByteArray()).isEmpty();
        });
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).hasSize(body.length);
        verify(protection).reserve(any(), any(), any(), org.mockito.ArgumentMatchers.eq((long) body.length));
        verify(reservation).finish(body.length);
        verify(reservation).close();
    }

    /** query凭据、超长POST以及GET正文均在认证查库之前被拒绝，不能消耗已知身份桶。 */
    @Test void invalidTransportNeverReachesCapabilityLookup() throws Exception {
        when(protection.acquireSource(anyString())).thenReturn(mock(DashboardShareProtectionService.SourcePermit.class));
        var query = request("POST", "alarms/query");
        query.setQueryString("secret=forbidden");
        var large = request("POST", "devices/snapshots/query");
        large.setContent(new byte[64 * 1024 + 1]);
        var getBody = request("GET", "devices/catalog");
        getBody.setContent(new byte[] {1});
        var credentialQuery = request("GET", "devices/catalog");
        credentialQuery.addParameter("secret", "forbidden");
        for (var request : new MockHttpServletRequest[] {query, large, getBody, credentialQuery}) {
            var response = new MockHttpServletResponse();
            filter().doFilter(request, response, (incoming, outgoing) -> { throw new AssertionError("非法传输不得执行数据服务"); });
            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(response.getContentAsString()).contains("10001").doesNotContain("forbidden");
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
        verifyNoInteractions(runtime);
    }

    /** 先拒绝非JSON与压缩编码，不允许Servlet因读取parameterMap而先解析无界form。 */
    @Test void rejectsNonJsonAndEncodedBodiesWithoutParameterParsing() throws Exception {
        when(protection.acquireSource(anyString())).thenReturn(mock(DashboardShareProtectionService.SourcePermit.class));
        for (String contentType : new String[] {"application/x-www-form-urlencoded", "text/plain", "application/json;charset=GBK", "application/json;charset=UTF-8;charset=UTF-8"}) {
            var request = new MockHttpServletRequest("POST", "/api/v1/shares/" + shareId + "/alarms/query") {
                /** 此方法一旦被调用即证明过滤器提前触发容器表单解析。 */
                @Override public java.util.Map<String, String[]> getParameterMap() { throw new AssertionError("不得解析form参数"); }
            };
            request.setContentType(contentType);
            var response = new MockHttpServletResponse();
            filter().doFilter(request, response, (incoming, outgoing) -> { throw new AssertionError("非法媒体不得执行"); });
            assertThat(response.getStatus()).isEqualTo(400);
        }
        var encoded = request("POST", "alarms/query");
        encoded.addHeader("Content-Encoding", "gzip");
        var response = new MockHttpServletResponse();
        filter().doFilter(encoded, response, (incoming, outgoing) -> { throw new AssertionError("不得接受压缩正文"); });
        assertThat(response.getStatus()).isEqualTo(400);
        verifyNoInteractions(runtime);
    }

    /** 构造规范token，仅控制此层路径与预算；不把mock身份冒称真实认证。 */
    private MockHttpServletRequest request(String method, String suffix) {
        var request = new MockHttpServletRequest(method, "/api/v1/shares/" + shareId + "/" + suffix);
        if ("POST".equals(method)) request.setContentType("application/json");
        request.addHeader("X-Share-Token", "sh_" + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
        return request;
    }

    /** 显式启用测试配置以隔离默认关闭场景。 */
    private DashboardShareAuthenticationFilter filter() {
        return new DashboardShareAuthenticationFilter(runtime, protection,
                new DashboardShareRuntimeProperties(true, "https://share.example.test", false, logs.toString()),
                mock(DashboardShareSecurityEvents.class), new ObjectMapper());
    }

    /** NONE只跳过附加Referer，令牌定位与预算调用仍全部存在。 */
    private DashboardShareProtectionService.Reservation authorize() {
        when(runtime.authenticate(any(), any())).thenReturn(new DashboardSharePrincipal(shareId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, Instant.now().plusSeconds(3600), "NONE", "a".repeat(64)));
        when(protection.acquireSource(anyString())).thenReturn(mock(DashboardShareProtectionService.SourcePermit.class));
        var reservation = mock(DashboardShareProtectionService.Reservation.class);
        when(protection.reserve(any(), any(), any(), anyLong())).thenReturn(reservation);
        return reservation;
    }
}
