package com.things.link.bootstrap.contract;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

/** 独立于生成修正器的冻结矩阵守卫：新增接口/分页需显式分类。 */
final class OpenApiGlobalStructureAssertions {
    private OpenApiGlobalStructureAssertions() { }

    static void verify(JsonNode spec) throws Exception {
        var baseline = JsonMapper.builder().build().readTree(Files.readString(
                OpenApiGenerationWriteGuard.canonicalRepositoryOutput(OpenApiSpecTests.class).getParent()
                        .resolve("delivery/verification/openapi-structure-baseline.json")));
        Set<String> actual = new LinkedHashSet<>();
        spec.path("paths").properties().forEach(path -> path.getValue().properties().forEach(method -> {
            if (Set.of("get", "post", "put", "patch", "delete", "head", "options").contains(method.getKey()))
                actual.add(method.getKey() + " " + path.getKey());
        }));
        Set<String> expected = new LinkedHashSet<>();
        for (var row : baseline.path("operations")) {
            String path = row.path("path").asString(), method = row.path("method").asString();
            String label = method + " " + path;
            assertThat(expected.add(label)).as("重复基线操作%s", label).isTrue();
            var operation = spec.path("paths").path(path).path(method);
            var headers = operation.path("parameters").valueStream()
                    .filter(p -> "Idempotency-Key".equalsIgnoreCase(p.path("name").asString())).toList();
            String policy = row.path("idempotency").asString();
            if (policy.endsWith("-header")) {
                assertThat(headers).as(label).hasSize(1);
                assertThat(headers.getFirst().path("in").asString()).as(label).isEqualTo("header");
                assertThat(headers.getFirst().path("schema").path("type").asString()).as(label).isEqualTo("string");
                assertThat(headers.getFirst().path("required").asBoolean()).as(label)
                        .isEqualTo(policy.equals("required-header"));
            } else assertThat(headers).as(label + "是明确例外").isEmpty();
            if (!policy.equals("broker-callback")) operation.path("responses").properties().forEach(response -> {
                if (response.getKey().matches("[45][0-9Xx]{2}")) {
                    var content = response.getValue().path("content");
                    assertThat(content.propertyNames()).as(label + " " + response.getKey()).containsExactly("application/json");
                    assertThat(content.path("application/json").path("schema").path("$ref").asString())
                            .as(label + " " + response.getKey()).isEqualTo("#/components/schemas/ApiError");
                }
            });
        }
        assertThat(actual).as("所有操作必须在独立冻结矩阵中").containsExactlyInAnyOrderElementsOf(expected);
        var schemas = spec.path("components").path("schemas");
        var error = schemas.path("ApiError").path("properties");
        assertThat(error.path("code").path("type").asString()).isEqualTo("integer");
        for (String name : Set.of("message", "traceId"))
            assertThat(error.path(name).path("type").asString()).isEqualTo("string");
        assertThat(error.path("details").path("type").asString()).isEqualTo("array");
        assertThat(error.path("details").path("items").path("type").asString()).isEqualTo("string");
        Set<String> pages = new LinkedHashSet<>();
        for (var row : baseline.path("pages")) {
            String name = row.path("name").asString();
            assertThat(pages.add(name)).isTrue();
            var fields = schemas.path(name).path("properties");
            assertThat(fields.path("items").path("type").asString()).as(name).isEqualTo("array");
            var item = fields.path("items").path("items");
            assertThat(item.has("$ref") || item.has("type")).as(name + "元素类型").isTrue();
            assertThat(fields.path("nextCursor").path("type").valueStream().map(JsonNode::asString).toList())
                    .as(name + "末页null游标").containsExactlyInAnyOrder("string", "null");
            assertThat(fields.has("hasMore")).as(name).isEqualTo(row.path("hasMore").asBoolean());
            if (row.path("hasMore").asBoolean())
                assertThat(fields.path("hasMore").path("type").asString()).as(name).isEqualTo("boolean");
        }
        assertThat(schemas.properties().stream().filter(e -> e.getValue().path("properties").has("nextCursor"))
                .map(java.util.Map.Entry::getKey).toList()).containsExactlyInAnyOrderElementsOf(pages);
    }
}
