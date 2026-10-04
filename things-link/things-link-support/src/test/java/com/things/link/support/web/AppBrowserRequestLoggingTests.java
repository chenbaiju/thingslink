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

/** 浏览器身份操作与错误尾缀不能被通用日志采样密码、refresh或Cookie。 */
class AppBrowserRequestLoggingTests {
    /** 整个namespace包括拒绝请求均不包装正文也不记录原始输入。 */
    @Test void excludesCredentialsIncludingRejectedPaths() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start(); logger.addAppender(captured);
        try {
            for (String suffix : new String[] {"", "/login", "/refresh", "/logout", "/unknown"}) {
                var request = new MockHttpServletRequest("POST", "/api/v1/app/browser-auth" + suffix);
                request.addHeader("Cookie", "tc_app_refresh=private_cookie");
                request.setQueryString("password=private_query");
                request.setContent("{\"password\":\"private_password\"}".getBytes(StandardCharsets.UTF_8));
                new RequestLoggingFilter().doFilter(request, new MockHttpServletResponse(), (incoming, outgoing) -> {
                    assertThat(incoming).isSameAs(request);
                    incoming.getInputStream().readAllBytes();
                });
            }
            assertThat(captured.list).isEmpty();
        } finally { logger.detachAppender(captured); captured.stop(); }
    }
    /** 相似字符串不能无意静默其他业务请求。 */
    @Test void retainsLoggingForSimilarPrefix() {
        assertThat(new RequestLoggingFilter().shouldNotFilter(new MockHttpServletRequest("POST", "/api/v1/app/browser-auth-other/login"))).isFalse();
    }
}
