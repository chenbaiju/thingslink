package com.things.link.support.web;

import com.things.link.support.idempotency.RequestBodySizeFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** 精确路由才退出缓存；相邻管理JSON不可利用上传豁免。 */
class ArtifactUploadRouteTests {
    private static final String ID = "12345678-1234-1234-1234-123456789abc";
    private static final String BASE = "/api/v1/projects/" + ID + "/ota/firmwares/" + ID + "/uploads";
    private static final String CONTENT = BASE + "/" + ID + "/content";
    @Test void rejectsNeighbouringAndNoncanonicalRoutes() {
        assertThat(ArtifactUploadRoute.isContent("PUT", CONTENT)).isTrue();
        for (String path : new String[] { CONTENT + "/", CONTENT + ";a=1", CONTENT + "/more",
                CONTENT.replace(ID, "1-1-1-1-1"), CONTENT.replace("/uploads/", "/uploads%2f"), BASE }) {
            assertThat(ArtifactUploadRoute.isContent("PUT", path)).as(path).isFalse();
        }
        for (String method : new String[] { "POST", "PATCH", "DELETE", "GET" }) {
            assertThat(ArtifactUploadRoute.isContent(method, CONTENT)).isFalse();
        }
        assertThat(ArtifactUploadRoute.isCreation("POST", BASE)).isTrue();
        assertThat(ArtifactUploadRoute.isCreation("POST", BASE + "/" + ID + "/cancel")).isFalse();
    }
    @Test void streamingBodyTraversesBothOuterFiltersUnwrapped() throws Exception {
        var request = new MockHttpServletRequest("PUT", CONTENT);
        request.setContent(new byte[1024 * 1024 + 1]);
        request.setQueryString("private=binary");
        var response = new MockHttpServletResponse();
        new RequestLoggingFilter().doFilter(request, response, (logged, output) ->
                new RequestBodySizeFilter(JsonMapper.builder().build()).doFilter(logged, output,
                        (received, finalResponse) -> assertThat(received).isSameAs(request)));
        assertThat(response.getStatus()).isEqualTo(200);
    }
    @Test void neighbouringOversizedJsonStillFailsClosed() throws Exception {
        var request = new MockHttpServletRequest("POST", BASE);
        request.setContent(new byte[1024 * 1024 + 1]);
        var response = new MockHttpServletResponse();
        new RequestBodySizeFilter(JsonMapper.builder().build()).doFilter(request, response,
                (received, output) -> { throw new AssertionError("超长JSON不得进入控制器"); });
        assertThat(response.getStatus()).isEqualTo(413);
    }
    @Test void contextPathIsExcludedOnlyAsContainerPrefix() {
        var request = new MockHttpServletRequest("PUT", "/service" + CONTENT);
        request.setContextPath("/service");
        assertThat(ArtifactUploadRoute.isContent(request)).isTrue();
    }
}
