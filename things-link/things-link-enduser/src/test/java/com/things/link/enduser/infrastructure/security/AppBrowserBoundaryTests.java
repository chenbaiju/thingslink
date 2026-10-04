package com.things.link.enduser.infrastructure.security;

import com.things.link.enduser.application.AppBrowserProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 安全链最早边界；这些替身不代替真实HTTP与会话事务验收。 */
class AppBrowserBoundaryTests {
    /** disabled只属于精确三POST，邻接路径和GET仍明确拒绝。 */
    @Test void disabledAppliesOnlyToExactSessionPosts() throws Exception {
        for (String endpoint : new String[] {"login", "refresh", "logout"}) {
            var response = run(false, request("POST", endpoint));
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getContentAsString()).contains("60057");
            assertThat(response.getHeader("Set-Cookie")).isNull();
        }
        assertThat(run(false, request("GET", "login")).getStatus()).isEqualTo(403);
        assertThat(run(false, request("POST", "unknown")).getStatus()).isEqualTo(403);
    }
    /** 缺失/重复/多值/空端口Origin拒绝，明确标准443端口等同HTTPS默认端口。 */
    @Test void checksStrictOriginAndEffectivePort() throws Exception {
        for (String origin : new String[] {"null", "https://share.test:", "https://share.test/", "https://share.test,https://share.test", "https://attacker.test"}) {
            var request = request("POST", "refresh");
            request.removeHeader("Origin"); request.addHeader("Origin", origin);
            assertThat(run(true, request).getStatus()).isEqualTo(403);
        }
        var explicit = request("POST", "refresh");
        explicit.removeHeader("Origin"); explicit.addHeader("Origin", "https://share.test:443");
        assertThat(run(true, explicit).getStatus()).isEqualTo(204);
        var duplicate = request("POST", "refresh"); duplicate.addHeader("Origin", "https://share.test");
        assertThat(run(true, duplicate).getStatus()).isEqualTo(403);
    }
    /** 请求媒体检查不调用parameterMap触发表单解析；超8KiB采用公开413。 */
    @Test void rejectsFormEncodingAndOversizeBeforeDownstream() throws Exception {
        var form = new MockHttpServletRequest("POST", "/api/v1/app/browser-auth/login") {
            /** 调用即证明无界表单解析入口被触发。 */
            @Override public java.util.Map<String, String[]> getParameterMap() { throw new AssertionError("不能读取表单"); }
        };
        form.addHeader("Origin", "https://share.test"); form.setContentType("application/x-www-form-urlencoded");
        assertThat(run(true, form).getStatus()).isEqualTo(400);
        var large = request("POST", "login"); large.setContent(new byte[8193]);
        var response = run(true, large);
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("10013");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
    /** 合法头只为本层传输测试，不提供真实凭据。 */
    private static MockHttpServletRequest request(String method, String endpoint) {
        var request = new MockHttpServletRequest(method, "/api/v1/app/browser-auth/" + endpoint);
        request.addHeader("Origin", "https://share.test"); request.setContentType("application/json;charset=UTF-8");
        return request;
    }
    /** 下游204标识仅说明边界通过，不声称已认证。 */
    private static MockHttpServletResponse run(boolean enabled, MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        new AppBrowserSecurityConfiguration.Boundary(new AppBrowserProperties(enabled, "https://share.test", false, null, null, null, null), new ObjectMapper())
                .doFilter(request, response, (incoming, outgoing) -> ((jakarta.servlet.http.HttpServletResponse) outgoing).setStatus(204));
        return response;
    }
}
