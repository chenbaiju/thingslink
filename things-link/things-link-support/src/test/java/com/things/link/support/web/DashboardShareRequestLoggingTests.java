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

/** ADR0101：即使签发输入非法，最外层原文日志也不得泄漏误放在正文或query的secret。 */
class DashboardShareRequestLoggingTests {
    /** 集合路由故意使用可辨认ID，证明未扩大成整个dashboards前缀跳过。 */
    private static final String PATH = "/api/v1/projects/project-id/dashboards/dashboard-id/shares";

    /** 真正执行过滤器与下游正文消费；成功/拒绝都不能留下原文日志或包装缓存。 */
    @Test
    void skipsCreationBodyQueryAndResponseForSuccessAndRejectedRequests() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            for (int status : new int[] {201, 400, 401, 409}) {
                var request = new MockHttpServletRequest("POST", "/context" + PATH);
                request.setContextPath("/context");
                request.setQueryString("secret=sh_query_credential");
                request.setContent("{\"secret\":\"sh_body_credential\"}".getBytes(StandardCharsets.UTF_8));
                var response = new MockHttpServletResponse();
                new RequestLoggingFilter().doFilter(request, response, (forwarded, outgoing) -> {
                    assertThat(forwarded).isSameAs(request);
                    assertThat(forwarded.getInputStream().readAllBytes()).isEqualTo(request.getContentAsByteArray());
                    response.setStatus(status);
                    response.getWriter().write("{\"secret\":\"sh_response_credential\"}");
                });
            }
            assertThat(captured.list).isEmpty();
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }

    /** 其他方法、撤销、发布和类似名字仍保留普通诊断规则，不以保护secret为由关闭整个模块日志。 */
    @Test
    void exclusionIsLimitedToExactPostCollection() {
        RequestLoggingFilter filter = new RequestLoggingFilter();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", PATH))).isTrue();
        for (String method : new String[] {"GET", "PUT", "PATCH", "DELETE"}) {
            assertThat(filter.shouldNotFilter(new MockHttpServletRequest(method, PATH))).isFalse();
        }
        for (String path : new String[] {PATH + "/id/revoke", PATH + "-extra", PATH + "/extra"}) {
            assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", path))).isFalse();
        }
    }
}
