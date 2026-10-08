package com.things.link.support.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** 产品凭据及动态注册在诊断日志启用时也不得缓存或打印秘密。 */
class ProductCredentialRequestLoggingTests {
    @Test void secretRoutesEvenDeniedRequestsNeverCacheOrLogPlaintext() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        Level previous = logger.getLevel();
        var capture = new ListAppender<ILoggingEvent>(); capture.start(); logger.addAppender(capture);
        logger.setLevel(Level.INFO);
        try {
            for (String method : List.of("POST", "GET", "PUT")) {
                for (String path : List.of("/api/v1/emqx/register", "/api/v1/emqx/register/invalid",
                        "/api/v1/projects/p/device-types/t/product-credential", "/api/v1/projects/p/device-types/t/product-credential/invalid")) {
                    var request = new MockHttpServletRequest(method, "/console" + path);
                    request.setContextPath("/console"); request.setQueryString("productSecret=synthetic_secret");
                    request.setContent("{\"productSecret\":\"synthetic_secret\"}".getBytes(StandardCharsets.UTF_8));
                    new RequestLoggingFilter().doFilter(request, new MockHttpServletResponse(), (forwarded, response) -> {
                        assertThat(forwarded).isSameAs(request);
                        forwarded.getInputStream().readAllBytes(); ((MockHttpServletResponse) response).setStatus(403);
                    });
                }
            }
            assertThat(capture.list).isEmpty();
            // 正向校验捕获器确实启用；不能把默认OFF误作秘密路径退出的证据。
            var ordinary = new MockHttpServletRequest("GET", "/api/v1/projects/p/device-types/t");
            new RequestLoggingFilter().doFilter(ordinary, new MockHttpServletResponse(), (request, response) -> {});
            assertThat(capture.list).hasSize(1);
            assertThat(capture.list.getFirst().getFormattedMessage()).contains("GET /api/v1/projects/p/device-types/t");
        } finally { logger.setLevel(previous); logger.detachAppender(capture); capture.stop(); }
    }
    @Test void nearbyCatalogAndSimilarPrefixKeepDiagnostics() {
        var filter = new RequestLoggingFilter();
        for (String path : List.of("/api/v1/emqx/register-other", "/api/v1/projects/p/device-types/t/product-credentials",
                "/api/v1/projects/p/device-types/t", "/api/v1/projects/p/device-types/t/publish"))
            assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", path))).isFalse();
    }
}
