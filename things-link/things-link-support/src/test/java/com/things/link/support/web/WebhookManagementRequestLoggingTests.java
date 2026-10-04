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

/** ADR0211：即使签发输入非法，最外层原文日志也不得泄漏误放在正文或query的secret。 */
class WebhookManagementRequestLoggingTests {
    /** 使用可辨认项目ID，验证Webhook管理命名空间退出原文日志。 */
    private static final String PATH = "/api/v1/projects/project-id/webhooks";

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

    @Test void exclusionCoversManagementOnly() {
        var filter=new RequestLoggingFilter();
        for(String method:new String[]{"GET","POST","PUT","DELETE"}) {
            assertThat(filter.shouldNotFilter(new MockHttpServletRequest(method,PATH))).isTrue();
            assertThat(filter.shouldNotFilter(new MockHttpServletRequest(method,PATH+"/id/rotate"))).isTrue();
        }
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST",PATH+"-other"))).isFalse();
    }
}
