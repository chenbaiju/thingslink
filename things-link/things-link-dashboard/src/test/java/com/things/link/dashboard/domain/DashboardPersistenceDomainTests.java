package com.things.link.dashboard.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 看板持久领域值的完整关系集合与可变JSON隔离合同。
 *
 * <p>S12-1b2a在进入JDBC前冻结草稿候选；这些纯领域反例补足数据库无法表达的连续位置和调用方
 * 内存改写边界。</p>
 */
@DisplayName("看板持久领域值")
class DashboardPersistenceDomainTests {

    /** JSON夹具的唯一构造器。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 完整关系集合必须连续排序，并同时拒绝重复key与重复模型版本。 */
    @Test
    @DisplayName("完整模型关系要求连续位置与双唯一")
    void completeModelReferencesRequireContinuousPositionsAndUniqueIdentities() {
        UUID firstVersion = UUID.randomUUID();
        UUID secondVersion = UUID.randomUUID();
        ArrayList<DashboardModelReference> input = new ArrayList<>(List.of(
                new DashboardModelReference(0, "primary_model", firstVersion),
                new DashboardModelReference(1, "secondary_model", secondVersion)));

        List<DashboardModelReference> snapshot = DashboardModelReference.requireCompleteSet(input);
        input.clear();

        assertThat(snapshot).hasSize(2);
        assertThatThrownBy(() -> snapshot.add(
                new DashboardModelReference(2, "third_model", UUID.randomUUID())))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> DashboardModelReference.requireCompleteSet(List.of(
                new DashboardModelReference(1, "gap_model", UUID.randomUUID()))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DashboardModelReference.requireCompleteSet(List.of(
                new DashboardModelReference(0, "same_model", firstVersion),
                new DashboardModelReference(1, "same_model", secondVersion))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DashboardModelReference.requireCompleteSet(List.of(
                new DashboardModelReference(0, "first_model", firstVersion),
                new DashboardModelReference(1, "second_model", firstVersion))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 草稿必须复制构造输入和每次读取结果，保证一个revision只代表一份稳定内容。 */
    @Test
    @DisplayName("草稿JSON与关系列表保持防御隔离")
    void draftDefensivelyCopiesJsonAndReferenceCollection() {
        UUID modelVersionId = UUID.randomUUID();
        ArrayList<DashboardModelReference> references = new ArrayList<>(List.of(
                new DashboardModelReference(0, "primary_model", modelVersionId)));
        ObjectNode input = content("初始标题", references);
        Instant at = Instant.parse("2026-09-06T10:40:00Z");
        DashboardDraft draft = new DashboardDraft(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), input, 0,
                UUID.randomUUID(), at, at, references);

        input.put("title", "输入改写");
        references.clear();
        ((ObjectNode) draft.content()).put("title", "读取改写");

        assertThat(draft.content().path("title").asString()).isEqualTo("初始标题");
        assertThat(draft.modelReferences())
                .containsExactly(new DashboardModelReference(0, "primary_model", modelVersionId));
        assertThatThrownBy(() -> draft.modelReferences().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** 不可变版本构造同样必须拒绝Schema模型投影的漏项、顺序、key及versionId错配。 */
    @Test
    @DisplayName("看板版本拒绝Schema与关系错配")
    void dashboardVersionRejectsSchemaModelProjectionMismatch() {
        UUID firstVersion = UUID.randomUUID();
        UUID secondVersion = UUID.randomUUID();
        List<DashboardModelReference> schemaReferences = List.of(
                new DashboardModelReference(0, "first_model", firstVersion),
                new DashboardModelReference(1, "second_model", secondVersion));

        assertThatThrownBy(() -> version(content("漏项", schemaReferences),
                List.of(schemaReferences.getFirst())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> version(content("顺序", schemaReferences), List.of(
                new DashboardModelReference(0, "second_model", secondVersion),
                new DashboardModelReference(1, "first_model", firstVersion))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> version(content("错key", schemaReferences), List.of(
                new DashboardModelReference(0, "wrong_model", firstVersion),
                schemaReferences.get(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> version(content("错version", schemaReferences), List.of(
                new DashboardModelReference(0, "first_model", secondVersion),
                new DashboardModelReference(1, "second_model", firstVersion))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 构造字段完整且模型投影与关系候选一致的看板Schema。 */
    private ObjectNode content(String title, List<DashboardModelReference> references) {
        ObjectNode content = JSON.createObjectNode()
                .put("schemaVersion", DashboardDraft.SCHEMA_VERSION)
                .put("title", title);
        ArrayNode models = content.putArray("models");
        for (DashboardModelReference reference : references) {
            models.addObject()
                    .put("key", reference.modelKey())
                    .put("versionId", reference.thingModelVersionId().toString())
                    .put("digestAlgorithm", DashboardVersion.SCHEMA_DIGEST_ALGORITHM)
                    .put("digest", compact(reference.thingModelVersionId()).repeat(2))
                    .put("profile", "TC_PROPERTY_COMPOSITE_V1");
        }
        return content;
    }

    /** 构造仅用于验证模型关系投影的合法不可变版本。 */
    private DashboardVersion version(ObjectNode schema, List<DashboardModelReference> references) {
        Instant at = Instant.parse("2026-09-06T10:45:00Z");
        return new DashboardVersion(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, 0, schema, DashboardDraft.SCHEMA_VERSION,
                DashboardVersion.SCHEMA_DIGEST_ALGORITHM, "a".repeat(64),
                JSON.createArrayNode(), JSON.createArrayNode(), UUID.randomUUID(), at, references);
    }

    /** 将UUID转换成无分隔符的小写十六进制摘要夹具。 */
    private String compact(UUID value) {
        return value.toString().replace("-", "");
    }
}
