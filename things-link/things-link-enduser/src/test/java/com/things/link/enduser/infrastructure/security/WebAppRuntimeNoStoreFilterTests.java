package com.things.link.enduser.infrastructure.security;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** WebApp运行入口禁止缓存过滤器的低层分支测试。 */
class WebAppRuntimeNoStoreFilterTests {

    /** 被测过滤器无可变状态，可跨用例复用。 */
    private final WebAppRuntimeNoStoreFilter filter = new WebAppRuntimeNoStoreFilter();

    /** 规范及畸形键的GET resolve/current/Schema都先写no-store，成功、安全拒绝及系统错误均不可缓存。 */
    @ParameterizedTest
    @CsvSource({
            "/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/resolve,500",
            "/api/v1/app/applications/INVALID/resolve,500",
            "/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/current,200",
            "/api/v1/app/applications/INVALID/current,400",
            "/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/current,401",
            "/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/current,500",
            "/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/versions/00000000-0000-0000-0000-000000000001/dashboards/00000000-0000-0000-0000-000000000002/schema,200",
            "/api/v1/app/applications/INVALID/versions/bad-uuid/dashboards/bad-uuid/schema,400",
            "/api/v1/app/applications/INVALID/versions/bad-uuid/dashboards/bad-uuid/schema,401",
            "/api/v1/app/applications/INVALID/versions/bad-uuid/dashboards/bad-uuid/schema,500"
    })
    void addsNoStoreBeforeDownstreamOutcome(String path, int status) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, downstream) -> {
            assertThat(((MockHttpServletResponse) downstream).getHeader(HttpHeaders.CACHE_CONTROL))
                    .isEqualTo("no-store");
            ((MockHttpServletResponse) downstream).setStatus(status);
        });

        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    /** 非GET或相邻路径保持已有缓存合同，过滤器不能扩展到其他App接口。 */
    @ParameterizedTest
    @CsvSource({
            "POST,/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/resolve",
            "GET,/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/resolve/extra",
            "POST,/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/current",
            "PUT,/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/current",
            "HEAD,/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/current",
            "OPTIONS,/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/current",
            "GET,/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/current/extra",
            "GET,/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/current/",
            "GET,/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/schema",
            "GET,/api/v1/app/applications//current",
            "POST,/api/v1/app/applications/key/versions/version/dashboards/board/schema",
            "HEAD,/api/v1/app/applications/key/versions/version/dashboards/board/schema",
            "OPTIONS,/api/v1/app/applications/key/versions/version/dashboards/board/schema",
            "GET,/api/v1/app/applications/key/versions/version/dashboards/board/schema/extra",
            "GET,/api/v1/app/applications/key/versions/version/dashboards/board/schema/",
            "GET,/api/v1/app/applications/key/versions//dashboards/board/schema"
    })
    void leavesOtherRequestsUnchanged(String method, String path) throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(method, path), response, (request, downstream) -> { });

        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isNull();
    }

    /** If-None-Match不能促使本过滤器生成ETag或304，条件请求仍交给Controller正常读取。 */
    @ParameterizedTest
    @ValueSource(strings = {"resolve", "current", "versions/version/dashboards/board/schema"})
    void doesNotGenerateEtagOrNotModifiedResponse(String endpoint) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/" + endpoint);
        request.addHeader(HttpHeaders.IF_NONE_MATCH, "\"old\"");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, downstream) ->
                ((MockHttpServletResponse) downstream).setStatus(200));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(HttpHeaders.ETAG)).isNull();
    }

    /** 非根部署去除contextPath后仍精确匹配current，不规范资源键的认证拒绝也禁止缓存。 */
    @ParameterizedTest
    @ValueSource(ints = {401, 500})
    void currentUnderContextPathKeepsNoStoreBeforeFailure(int status) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/things-link/api/v1/app/applications/INVALID/current");
        request.setContextPath("/things-link");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (ignoredRequest, downstream) -> {
            assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
            response.setStatus(status);
        });
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    /** Schema路径在contextPath下对非法query仍先禁止缓存，避免认证或MVC失败漏头。 */
    @ParameterizedTest
    @ValueSource(ints = {400, 401, 500})
    void schemaUnderContextPathKeepsNoStoreBeforeFailure(int status) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/things-link/api/v1/app/applications/bad/versions/bad/dashboards/bad/schema");
        request.setContextPath("/things-link");
        request.setQueryString("expectedPublicationRevision=bad&expectedPublicationRevision=2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (ignoredRequest, downstream) -> {
            assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
            response.setStatus(status);
        });
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    /** 后续链路抛系统异常时保留已写头并传播首因，不把失败伪装成过滤器成功响应。 */
    @Test
    void currentDownstreamExceptionRetainsNoStoreAndOriginalFailure() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/current");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ServletException failure = new ServletException("后续读取失败");
        assertThatThrownBy(() -> filter.doFilter(request, response, (ignoredRequest, downstream) -> {
            assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
            throw failure;
        })).isSameAs(failure);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }
}
