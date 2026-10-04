package com.things.link.dashboard.application;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 运行Schema值对象的500KiB边界与JSON防御复制单测。 */
class RuntimeDashboardSchemaTests {

    /** PostgreSQL规范文本恰好512000字节仍可表达，调用方修改返回树不影响内部快照。 */
    @Test
    void acceptsExactSchemaByteLimitAndDefensivelyCopiesJson() {
        JsonNode source = JsonMapper.builder().build().createObjectNode()
                .put("schemaVersion", "tc.dashboard/v1");
        RuntimeDashboardSchema schema = schema(source, 512_000);

        ((tools.jackson.databind.node.ObjectNode) source).put("mutated", true);
        ((tools.jackson.databind.node.ObjectNode) schema.schema()).put("returnedMutation", true);

        assertThat(schema.schemaUtf8Bytes()).isEqualTo(512_000);
        assertThat(schema.schema().has("mutated")).isFalse();
        assertThat(schema.schema().has("returnedMutation")).isFalse();
    }

    /** 512001字节投影即使绕过数据库CHECK也由公开值防御拒绝。 */
    @Test
    void rejectsSchemaProjectionBeyondByteLimit() {
        JsonNode source = JsonMapper.builder().build().createObjectNode()
                .put("schemaVersion", "tc.dashboard/v1");

        assertThatThrownBy(() -> schema(source, 512_001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500KiB");
    }

    /** 建立最小合法运行Schema投影。 */
    private static RuntimeDashboardSchema schema(JsonNode source, int bytes) {
        return new RuntimeDashboardSchema(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "app_0123456789abcdef0123456789abcdef", UUID.randomUUID(), 1,
                UUID.randomUUID(), UUID.randomUUID(), 1, "tc.dashboard/v1",
                "PG_JSONB_TEXT_V1_SHA256", "d".repeat(64), List.of(), List.of(), source, bytes);
    }
}
