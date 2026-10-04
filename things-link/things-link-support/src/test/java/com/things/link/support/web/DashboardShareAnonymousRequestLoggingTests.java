package com.things.link.support.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR0101匿名路径诊断只允许固定模板安全事件，通用外层日志不能先记录非法凭据。 */
class DashboardShareAnonymousRequestLoggingTests {
    /** 真实过滤器链消费恶意query/body也不能留下原URI、secret、hash或内部ID日志。 */
    @Test
    void anonymousNamespaceIncludingDeniedPathsNeverEntersRawLogger() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            for (String path : new String[] {"/api/v1/shares", "/api/v1/shares/id/context",
                    "/ws/shares", "/ws/shares/id/properties", "/ws/shares/id/unknown",
                    "/api/v1/shares/id/schema", "/api/v1/shares/id/devices/current-values/query", "/api/v1/shares/id/unknown"}) {
                var request = new MockHttpServletRequest("POST", path);
                request.setQueryString("secret=sh_misplaced_secret");
                request.setContent("{\"secretHash\":\"sensitive_digest\"}".getBytes(StandardCharsets.UTF_8));
                request.addHeader("X-Share-Token", "sh_sensitive_header");
                new RequestLoggingFilter().doFilter(request, new MockHttpServletResponse(), (forwarded, response) -> {
                    assertThat(forwarded).isSameAs(request);
                    forwarded.getInputStream().readAllBytes();
                });
            }
            assertThat(captured.list).isEmpty();
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }

    /** 仅匿名命名空间退出，类似前缀和原App设备分享不被无意静默。 */
    @Test
    void similarPrefixesStillUseOrdinaryLogging() {
        RequestLoggingFilter filter = new RequestLoggingFilter();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/shares/id/context"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/shares-other/id/context"))).isFalse();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/v1/app/device-shares"))).isFalse();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/ws/shares-other/id/properties"))).isFalse();
    }
}
