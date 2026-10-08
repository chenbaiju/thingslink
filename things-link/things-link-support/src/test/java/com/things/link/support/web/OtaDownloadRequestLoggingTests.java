package com.things.link.support.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ContentCachingRequestWrapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OTA下载签发命名空间在诊断日志启用时也不得缓存或打印误放的签名地址。 */
class OtaDownloadRequestLoggingTests {
    private static final String SYNTHETIC_QUERY_SECRET = "synthetic_ota_download_query_secret";
    private static final String SYNTHETIC_BODY_SECRET = "synthetic_ota_download_body_secret";
    private static final String SYNTHETIC_PATH_SECRET = "synthetic_ota_download_path_secret";
    private static final List<String> METHODS = List.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE", "CONNECT", "INVALID");

    @Test
    void deniedDownloadNamespaceNeverCachesOrLogsSecretsForAnyMethod() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        Level previous = logger.getLevel();
        var capture = new ListAppender<ILoggingEvent>();
        capture.start();
        logger.addAppender(capture);
        logger.setLevel(Level.INFO);
        try {
            for (String context : List.of("", "/console")) {
                for (String method : METHODS) {
                    for (String path : List.of(
                            "/api/v1/projects/p/ota/firmwares/f/release/downloads",
                            "/api/v1/projects/p/ota/firmwares/f/release/downloads/",
                            "/api/v1/projects/p/ota/firmwares/f/release/downloads/invalid/" + SYNTHETIC_PATH_SECRET,
                            "/api/v1/projects/" + SYNTHETIC_PATH_SECRET + "/ota/firmwares/invalid/release/downloads")) {
                        var request = secretRequest(method, context, path);
                        var response = new MockHttpServletResponse();
                        new RequestLoggingFilter().doFilter(request, response, (forwarded, rejected) -> {
                            // 不只是日志为空：原请求必须原样转交，连正文旁路缓存都不能建立。
                            assertThat(forwarded).isSameAs(request);
                            forwarded.getInputStream().readAllBytes();
                            ((MockHttpServletResponse) rejected).setStatus(403);
                        });
                        assertThat(response.getStatus()).isEqualTo(403);
                    }
                }
            }
            assertThat(capture.list).isEmpty();

            // 强制INFO后的真实正向捕获，排除默认OFF使隐私测试假通过。
            var ordinary = new MockHttpServletRequest("GET", "/api/v1/projects/p/ota/firmwares/f/release");
            new RequestLoggingFilter().doFilter(ordinary, new MockHttpServletResponse(), (request, response) -> { });
            assertThat(capture.list).hasSize(1);
            assertThat(capture.list.getFirst().getFormattedMessage())
                    .contains("GET /api/v1/projects/p/ota/firmwares/f/release")
                    .doesNotContain(SYNTHETIC_QUERY_SECRET, SYNTHETIC_BODY_SECRET, SYNTHETIC_PATH_SECRET);
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(capture);
            capture.stop();
        }
    }

    @Test
    void exceptionalDownloadRejectionNeverLogsSecretsButAdjacentWritesKeepDiagnostics() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        Level previous = logger.getLevel();
        var capture = new ListAppender<ILoggingEvent>();
        capture.start();
        logger.addAppender(capture);
        logger.setLevel(Level.INFO);
        try {
            var request = secretRequest("POST", "/console",
                    "/api/v1/projects/p/ota/firmwares/f/release/downloads/" + SYNTHETIC_PATH_SECRET);
            assertThatThrownBy(() -> new RequestLoggingFilter().doFilter(request, new MockHttpServletResponse(),
                    (forwarded, response) -> {
                        assertThat(forwarded).isSameAs(request);
                        forwarded.getInputStream().readAllBytes();
                        throw new IOException("synthetic rejection");
                    })).isInstanceOf(IOException.class).hasMessage("synthetic rejection");
            assertThat(capture.list).isEmpty();

            List<String> ordinaryPaths = List.of(
                    "/api/v1/projects/p/ota/firmwares/f/release",
                    "/api/v1/projects/p/ota/firmwares/f/release/download",
                    "/api/v1/projects/p/ota/firmwares/f/release/downloads-other",
                    "/api/v1/projects/p/ota/firmwares/f/downloads",
                    "/api/v1/projects/p/ota/firmwares/f/publications");
            for (String path : ordinaryPaths) {
                var ordinary = new MockHttpServletRequest("POST", "/console" + path);
                ordinary.setContextPath("/console");
                ordinary.setQueryString("diagnostic=normal_query");
                ordinary.setContent("{\"diagnostic\":\"normal_body\"}".getBytes(StandardCharsets.UTF_8));
                new RequestLoggingFilter().doFilter(ordinary, new MockHttpServletResponse(), (forwarded, response) -> {
                    assertThat(forwarded).isInstanceOf(ContentCachingRequestWrapper.class);
                    forwarded.getInputStream().readAllBytes();
                });
            }
            assertThat(capture.list).hasSize(ordinaryPaths.size());
            for (int i = 0; i < ordinaryPaths.size(); i++) {
                assertThat(capture.list.get(i).getFormattedMessage())
                        .contains("POST /console" + ordinaryPaths.get(i), "diagnostic=normal_query", "normal_body")
                        .doesNotContain(SYNTHETIC_QUERY_SECRET, SYNTHETIC_BODY_SECRET, SYNTHETIC_PATH_SECRET);
            }
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(capture);
            capture.stop();
        }
    }

    private static MockHttpServletRequest secretRequest(String method, String context, String path) {
        var request = new MockHttpServletRequest(method, context + path);
        request.setContextPath(context);
        request.setQueryString("downloadUrl=https://synthetic.example.invalid/object?X-Amz-Signature=" + SYNTHETIC_QUERY_SECRET);
        request.setContent(("{\"downloadUrl\":\"https://synthetic.example.invalid/object?X-Amz-Signature="
                + SYNTHETIC_BODY_SECRET + "\"}").getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
