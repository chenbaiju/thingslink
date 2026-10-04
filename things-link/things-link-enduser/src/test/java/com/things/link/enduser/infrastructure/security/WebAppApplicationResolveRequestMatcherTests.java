package com.things.link.enduser.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** 精确公开应用resolve匹配器的低层安全边界测试。 */
class WebAppApplicationResolveRequestMatcherTests {

    /** 被测匹配器只持有不可变路径规则，可在全部用例间安全复用。 */
    private final WebAppApplicationResolveRequestMatcher matcher =
            new WebAppApplicationResolveRequestMatcher();

    /** 规范GET在根部署和非空contextPath下都应命中同一公开资格。 */
    @ParameterizedTest
    @CsvSource({"'',/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/resolve",
            "/cloud,/cloud/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/resolve"})
    void matchesOnlyCanonicalGetWithinDeploymentContext(String contextPath, String requestUri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", requestUri);
        request.setContextPath(contextPath);

        assertThat(matcher.matches(request)).isTrue();
    }

    /** 方法、键格式、尾斜杠和子路径任一变化都不能继承匿名资格。 */
    @ParameterizedTest
    @CsvSource({
            "POST,/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/resolve",
            "HEAD,/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/resolve",
            "GET,/api/v1/app/applications/app_0123456789ABCDEF0123456789ABCDEF/resolve",
            "GET,/api/v1/app/applications/app_short/resolve",
            "GET,/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/resolve/",
            "GET,/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/resolve/extra"
    })
    void rejectsNearMethodAndPath(String method, String path) {
        assertThat(matcher.matches(new MockHttpServletRequest(method, path))).isFalse();
    }

    /** 不一致contextPath属于服务器集成缺陷，不能用错误切片路径扩大公开范围。 */
    @Test
    void rejectsRequestUriOutsideContextPathAsInvariantFailure() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/app/applications/key/resolve");
        request.setContextPath("/cloud");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> matcher.matches(request))
                .isInstanceOf(IllegalStateException.class);
    }
}
