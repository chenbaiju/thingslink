package com.things.link.bootstrap.integration;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** G3-LOCAL-4c：真实HTTP错误与末页样本，不能只让生成器自证。 */
class GlobalHttpShapeTests extends WebhookFixture {
    @Test void webhookAndBinaryCatalogFailuresUseJsonApiError() throws Exception {
        for (String path : new String[] {
                "/api/v1/projects/" + project + "/webhooks/not-a-uuid",
                "/api/v1/projects/" + project + "/devices/catalog?limit=0"}) {
            var response = request("GET", path, null, token(), Map.of());
            assertThat(response.statusCode()).as(path).isEqualTo(400);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                    value -> assertThat(value).startsWith("application/json"));
            var error = json.readTree(response.body());
            assertThat(error.propertyNames()).containsExactlyInAnyOrder("code", "message", "traceId", "details");
            assertThat(error.path("code").isIntegralNumber()).isTrue();
            assertThat(error.path("message").isString()).isTrue();
            assertThat(error.path("traceId").isString()).isTrue();
            assertThat(error.path("details").isArray()).isTrue();
        }
    }

    @Test void ordinaryAndFrozenRulePagesKeepTheirDifferentTerminalShapes() throws Exception {
        for (String resource : new String[] {"webhooks", "message-rules"}) {
            var response = request("GET", "/api/v1/projects/" + project + "/" + resource,
                    null, token(), Map.of());
            assertThat(response.statusCode()).as(resource).isEqualTo(200);
            var page = json.readTree(response.body());
            assertThat(page.path("items").isArray()).isTrue();
            assertThat(page.path("items")).isEmpty();
            assertThat(page.has("nextCursor")).isTrue();
            assertThat(page.path("nextCursor").isNull()).isTrue();
            if (resource.equals("webhooks")) {
                assertThat(page.path("hasMore").isBoolean()).isTrue();
                assertThat(page.path("hasMore").asBoolean()).isFalse();
            } else assertThat(page.has("hasMore")).isFalse();
        }
    }
}
