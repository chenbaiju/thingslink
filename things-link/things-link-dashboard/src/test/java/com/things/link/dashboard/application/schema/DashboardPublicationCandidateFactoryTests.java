package com.things.link.dashboard.application.schema;

import com.things.link.dashboard.application.publication.DashboardPublicationCandidate;
import com.things.link.dashboard.application.publication.DashboardPublicationEligibilityRequirement;
import com.things.link.dashboard.application.publication.DashboardRequiredComponent;
import com.things.link.dashboard.application.publication.DashboardRequiredResource;
import com.things.link.dashboard.application.publication.DashboardSchemaCanonicalizer;
import com.things.link.dashboard.application.publication.PostgreSqlDashboardSchemaCanonicalForm;
import com.things.link.dashboard.domain.DashboardDraft;
import com.things.link.dashboard.domain.DashboardModelReference;
import com.things.link.dashboard.domain.DashboardVersion;
import com.things.link.dashboard.infrastructure.schema.JacksonDashboardSchemaParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 看板发布候选的严格复验、完整需求投影、清单规范化及值对象围栏测试。 */
@DisplayName("看板规范发布候选")
class DashboardPublicationCandidateFactoryTests {

    /** 与黄金语料和生产解析器一致的JSON映射器。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 固定模型版本ID。 */
    private static final UUID MODEL_VERSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** 固定合法数据库摘要。 */
    private static final String SCHEMA_DIGEST = "c".repeat(64);
    /** 模拟数据库规范化端口。 */
    private final DashboardSchemaCanonicalizer canonicalizer = mock(DashboardSchemaCanonicalizer.class);
    /** 使用生产严格解析器装配的被测候选工厂。 */
    private final DefaultDashboardPublicationCandidateFactory factory =
            new DefaultDashboardPublicationCandidateFactory(
                    new JacksonDashboardSchemaParser(), canonicalizer);

    /** 八类外部需求必须按完整Schema遍历顺序逐项投影，且候选对所有可变输入防御复制。 */
    @Test
    @DisplayName("完整Schema保留八类资格需求顺序并隔离候选状态")
    void completeSchemaPreservesAllEligibilityRequirementsAndDefensiveCopies() throws IOException {
        JsonNode content = externalRequirementsSchema();
        when(canonicalizer.canonicalize(any())).thenReturn(canonicalForm(content));

        DashboardPublicationCandidate candidate = factory.prepare(draft(
                content, List.of(new DashboardModelReference(0, "pump_model", MODEL_VERSION_ID))));

        assertThat(candidate.eligibilityRequirements()).extracting(value -> value.getClass().getSimpleName())
                .containsExactly(
                        "ModelReference", "DefaultDevice", "DefaultDevice", "HostComponent",
                        "BuiltinResource", "HostComponent", "ModelProperty", "ModelGaugeRange",
                        "DataAdapter", "HostComponent", "HistoricalProperty", "DataAdapter",
                        "HostComponent", "ModelProperty", "DataAdapter", "HostComponent",
                        "ModelProperty", "DataAdapter", "HostComponent", "DataAdapter",
                        "HostComponent", "DataAdapter", "HostComponent", "ModelProperty",
                        "DataAdapter", "HostComponent", "DataAdapter");
        assertThat(candidate.eligibilityRequirements()).hasAtLeastOneElementOfType(
                DashboardPublicationEligibilityRequirement.HostComponent.class)
                .hasAtLeastOneElementOfType(DashboardPublicationEligibilityRequirement.BuiltinResource.class)
                .hasAtLeastOneElementOfType(DashboardPublicationEligibilityRequirement.ModelReference.class)
                .hasAtLeastOneElementOfType(DashboardPublicationEligibilityRequirement.DefaultDevice.class)
                .hasAtLeastOneElementOfType(DashboardPublicationEligibilityRequirement.ModelProperty.class)
                .hasAtLeastOneElementOfType(DashboardPublicationEligibilityRequirement.HistoricalProperty.class)
                .hasAtLeastOneElementOfType(DashboardPublicationEligibilityRequirement.ModelGaugeRange.class)
                .hasAtLeastOneElementOfType(DashboardPublicationEligibilityRequirement.DataAdapter.class);
        DashboardPublicationEligibilityRequirement.ModelReference model =
                (DashboardPublicationEligibilityRequirement.ModelReference)
                        candidate.eligibilityRequirements().getFirst();
        assertThat(model).isEqualTo(new DashboardPublicationEligibilityRequirement.ModelReference(
                "pump_model", MODEL_VERSION_ID, "PG_JSONB_TEXT_V1_SHA256",
                "a".repeat(64), "TC_PROPERTY_COMPOSITE_V1"));

        ObjectNode escaped = (ObjectNode) candidate.normalizedSchema();
        escaped.put("schemaVersion", "tampered");
        byte[] canonicalBytes = candidate.postgresqlCanonicalUtf8();
        canonicalBytes[0] = 'X';
        DashboardPublicationEligibilityRequirement.ModelProperty property = candidate
                .eligibilityRequirements().stream()
                .filter(DashboardPublicationEligibilityRequirement.ModelProperty.class::isInstance)
                .map(DashboardPublicationEligibilityRequirement.ModelProperty.class::cast)
                .findFirst().orElseThrow();

        assertThat(candidate.normalizedSchema().path("schemaVersion").asString())
                .isEqualTo("tc.dashboard/v1");
        assertThat(candidate.postgresqlCanonicalUtf8()[0]).isNotEqualTo((byte) 'X');
        assertThatThrownBy(() -> candidate.modelReferences().add(
                new DashboardModelReference(1, "other", UUID.randomUUID())))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> candidate.requiredComponents().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> candidate.requiredResources().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> candidate.eligibilityRequirements().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> property.allowedDataTypes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** 十种组件和重复资源须形成ASCII排序去重且不超过迁移上限的发布清单。 */
    @Test
    @DisplayName("组件和资源清单按机器值排序去重")
    void componentAndResourceListsAreAsciiSortedAndDeduplicated() throws IOException {
        ObjectNode content = (ObjectNode) externalRequirementsSchema();
        ArrayNode components = (ArrayNode) content.path("pages").get(0).path("components");
        components.add(component("text", "TEXT", 9, "{\"content\":\"说明\"}"));
        components.add(image("alpha_image", 10, "alpha", "d".repeat(64)));
        components.add(image("alpha_image_copy", 11, "alpha", "d".repeat(64)));
        when(canonicalizer.canonicalize(any())).thenReturn(canonicalForm(content));

        DashboardPublicationCandidate candidate = factory.prepare(draft(
                content, List.of(new DashboardModelReference(0, "pump_model", MODEL_VERSION_ID))));

        assertThat(candidate.requiredComponents()).containsExactly(
                componentRequirement("ALARM_LIST"), componentRequirement("DEVICE_SELECTOR"),
                componentRequirement("GAUGE"), componentRequirement("IMAGE"),
                componentRequirement("JSON_VIEW"), componentRequirement("LINE_CHART"),
                componentRequirement("STATUS"), componentRequirement("TABLE"),
                componentRequirement("TEXT"), componentRequirement("VALUE_CARD"));
        assertThat(candidate.requiredResources()).containsExactly(
                new DashboardRequiredResource("alpha", "d".repeat(64)),
                new DashboardRequiredResource("empty_state", "b".repeat(64)));
        assertThat(candidate.requiredComponents()).hasSize(10);
    }

    /** 相同resourceId声明不同摘要时必须稳定拒绝，不能任取其中一个发布。 */
    @Test
    @DisplayName("同一资源标识的摘要冲突失败关闭")
    void sameResourceIdWithDifferentDigestsFailsClosed() throws IOException {
        ObjectNode content = (ObjectNode) externalRequirementsSchema();
        ArrayNode components = (ArrayNode) content.path("pages").get(0).path("components");
        components.add(image("conflict_image", 10, "empty_state", "e".repeat(64)));
        when(canonicalizer.canonicalize(any())).thenReturn(canonicalForm(content));

        assertThatThrownBy(() -> factory.prepare(draft(
                content, List.of(new DashboardModelReference(0, "pump_model", MODEL_VERSION_ID)))))
                .isInstanceOfSatisfying(DashboardSchemaValidationException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(
                            DashboardSchemaValidationException.Reason.INVALID_REFERENCE);
                    assertThat(failure.path()).isEqualTo("$.pages[].components[].props.resourceId");
                });
    }

    /** 发布候选必须重跑完整内部Schema校验，失败时不得请求数据库规范化。 */
    @Test
    @DisplayName("持久草稿语义失效时停止在PostgreSQL摘要前")
    void persistedDraftWithInvalidSemanticsStopsBeforeCanonicalization() {
        ObjectNode content = JSON.createObjectNode()
                .put("schemaVersion", "tc.dashboard/v1")
                .put("unexpected", true);

        assertThatThrownBy(() -> factory.prepare(draft(content, List.of())))
                .isInstanceOf(DashboardSchemaValidationException.class);
        verifyNoInteractions(canonicalizer);
    }

    /** 直接构造候选也不得接受无法由PG规范结果产生的大写或非SHA摘要。 */
    @ParameterizedTest
    @ValueSource(strings = {"ABCDEF", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    @DisplayName("候选直接构造拒绝非法摘要")
    void directCandidateConstructionRejectsInvalidDigest(String invalidDigest) {
        assertThatThrownBy(() -> directCandidate(
                invalidDigest, List.of(componentRequirement("TEXT")), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 直接构造候选须拒绝组件和资源清单的乱序、重复及超限形状。 */
    @Test
    @DisplayName("候选直接构造拒绝非规范清单")
    void directCandidateConstructionRejectsNonCanonicalLists() {
        DashboardRequiredComponent image = componentRequirement("IMAGE");
        DashboardRequiredComponent text = componentRequirement("TEXT");
        DashboardRequiredComponent textNextVersion =
                new DashboardRequiredComponent("TEXT", "1.0.1");
        DashboardRequiredResource alpha = new DashboardRequiredResource("alpha", "a".repeat(64));
        DashboardRequiredResource beta = new DashboardRequiredResource("beta", "b".repeat(64));
        DashboardRequiredResource alphaConflict =
                new DashboardRequiredResource("alpha", "b".repeat(64));

        assertThatThrownBy(() -> directCandidate(SCHEMA_DIGEST, List.of(text, image), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> directCandidate(SCHEMA_DIGEST, List.of(image, image), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> directCandidate(
                SCHEMA_DIGEST, List.of(text, textNextVersion), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> directCandidate(SCHEMA_DIGEST, List.of(image), List.of(beta, alpha)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> directCandidate(SCHEMA_DIGEST, List.of(image), List.of(alpha, alpha)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> directCandidate(
                SCHEMA_DIGEST, List.of(image), List.of(alpha, alphaConflict)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> directCandidate(
                SCHEMA_DIGEST, indexedComponents(11), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> directCandidate(
                SCHEMA_DIGEST, List.of(image), indexedResources(51)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** PG规范结果和值候选均按UTF-8字节拒绝超过512000的多字节文本。 */
    @Test
    @DisplayName("PostgreSQL规范文本按UTF-8字节执行上限")
    void postgreSqlCanonicalValuesRejectOversizedMultibyteText() {
        String oversized = "中".repeat(170_667);

        assertThat(oversized).hasSizeLessThan(512_000);
        assertThat(oversized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isGreaterThan(512_000);
        assertThatThrownBy(() -> new PostgreSqlDashboardSchemaCanonicalForm(
                oversized, "PG_JSONB_TEXT_V1_SHA256", SCHEMA_DIGEST))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DashboardPublicationCandidate(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
                JSON.createObjectNode().put("schemaVersion", "tc.dashboard/v1"), oversized,
                "PG_JSONB_TEXT_V1_SHA256", SCHEMA_DIGEST,
                List.of(), List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 历史版本须重跑同一Schema管线并保留原身份、revision和全部资格需求。 */
    @Test
    @DisplayName("历史版本重新生成完全相同的当前资格候选")
    void historicalVersionRebuildsSameCandidateWithoutChangingFrozenFacts() throws IOException {
        JsonNode content = externalRequirementsSchema();
        List<DashboardModelReference> references =
                List.of(new DashboardModelReference(0, "pump_model", MODEL_VERSION_ID));
        when(canonicalizer.canonicalize(any())).thenReturn(canonicalForm(content));
        DashboardPublicationCandidate publishedCandidate = factory.prepare(draft(content, references));
        DashboardVersion version = version(publishedCandidate,
                JSON.valueToTree(publishedCandidate.requiredComponents()),
                JSON.valueToTree(publishedCandidate.requiredResources()));

        DashboardPublicationCandidate rebuilt = factory.prepareHistoricalVersion(version);

        assertThat(rebuilt).isEqualTo(publishedCandidate);
        assertThat(version.schema()).isEqualTo(publishedCandidate.normalizedSchema());
    }

    /** 持久派生清单与重建结果不一致表示不可变事实损坏，不能降格成普通资格失败。 */
    @Test
    @DisplayName("历史版本派生清单漂移按持久完整性故障拒绝")
    void historicalVersionWithDriftedDerivedListsFailsAsIntegrityError() throws IOException {
        JsonNode content = externalRequirementsSchema();
        List<DashboardModelReference> references =
                List.of(new DashboardModelReference(0, "pump_model", MODEL_VERSION_ID));
        when(canonicalizer.canonicalize(any())).thenReturn(canonicalForm(content));
        DashboardPublicationCandidate publishedCandidate = factory.prepare(draft(content, references));
        DashboardVersion corrupted = version(
                publishedCandidate, JSON.createArrayNode(), JSON.createArrayNode());

        assertThatThrownBy(() -> factory.prepareHistoricalVersion(corrupted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不可变看板版本");
    }

    /** 从共享黄金语料读取覆盖八类外部需求的完整输入。 */
    private static JsonNode externalRequirementsSchema() throws IOException {
        return JSON.readTree(Files.readAllBytes(repositoryRoot().resolve(
                "things-link-client-contracts/contracts/dashboard-v1-golden/inputs/"
                        + "accept-external-requirements.json")));
    }

    /** 构造合法草稿事实。 */
    private static DashboardDraft draft(JsonNode content, List<DashboardModelReference> references) {
        Instant now = Instant.parse("2026-09-06T12:00:00Z");
        return new DashboardDraft(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), content, 7,
                UUID.randomUUID(), now, now, references);
    }

    /** 从发布候选构造历史版本，可替换派生清单以验证持久一致性围栏。 */
    private static DashboardVersion version(
            DashboardPublicationCandidate candidate,
            JsonNode requiredComponents,
            JsonNode requiredResources) {
        return new DashboardVersion(
                UUID.randomUUID(), candidate.tenantId(), candidate.projectId(), candidate.dashboardId(),
                2, candidate.sourceDraftRevision(), candidate.normalizedSchema(), DashboardDraft.SCHEMA_VERSION,
                candidate.schemaDigestAlgorithm(), candidate.schemaDigest(), requiredComponents, requiredResources,
                UUID.randomUUID(), Instant.parse("2026-09-06T12:30:00Z"), candidate.modelReferences());
    }

    /** 构造数据库端口固定规范结果。 */
    private static PostgreSqlDashboardSchemaCanonicalForm canonicalForm(JsonNode content) {
        return new PostgreSqlDashboardSchemaCanonicalForm(
                content.toString(), "PG_JSONB_TEXT_V1_SHA256", SCHEMA_DIGEST);
    }

    /** 构造一个位置互不重叠的组件对象。 */
    private static ObjectNode component(String id, String kind, int x, String props) throws IOException {
        return (ObjectNode) JSON.readTree(("""
                {"id":"%s","kind":"%s","componentVersion":"1.0.0",
                 "layout":{"x":%d,"y":0,"w":1,"h":1},"props":%s,"bindings":{}}
                """).formatted(id, kind, x, props));
    }

    /** 构造带精确内置资源身份的IMAGE组件。 */
    private static ObjectNode image(String id, int x, String resourceId, String digest) throws IOException {
        return component(id, "IMAGE", x, ("""
                {"resourceId":"%s","resourceDigest":"%s","alt":"资源"}
                """).formatted(resourceId, digest));
    }

    /** 构造固定1.0.0组件清单项。 */
    private static DashboardRequiredComponent componentRequirement(String kind) {
        return new DashboardRequiredComponent(kind, "1.0.0");
    }

    /** 构造超过十项的有序组件清单，证明直接构造不能绕开持久上限。 */
    private static List<DashboardRequiredComponent> indexedComponents(int size) {
        List<String> kinds = List.of(
                "ALARM_LIST", "DEVICE_SELECTOR", "GAUGE", "IMAGE", "JSON_VIEW",
                "LINE_CHART", "STATUS", "TABLE", "TEXT", "VALUE_CARD");
        List<DashboardRequiredComponent> values = new ArrayList<>();
        for (int index = 0; index < Math.min(size, kinds.size()); index++) {
            values.add(new DashboardRequiredComponent(kinds.get(index), "1.0.0"));
        }
        if (size > kinds.size()) {
            values.add(new DashboardRequiredComponent("VALUE_CARD", "1.0.1"));
        }
        return List.copyOf(values);
    }

    /** 构造超过五十项的有序资源清单，证明直接构造不能绕开持久上限。 */
    private static List<DashboardRequiredResource> indexedResources(int size) {
        List<DashboardRequiredResource> values = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            values.add(new DashboardRequiredResource(
                    "resource_%02d".formatted(index), "a".repeat(64)));
        }
        return List.copyOf(values);
    }

    /** 使用最小合法事实直接构造候选，以隔离值对象自身围栏。 */
    private static DashboardPublicationCandidate directCandidate(
            String digest, List<DashboardRequiredComponent> components,
            List<DashboardRequiredResource> resources) {
        ObjectNode schema = JSON.createObjectNode().put("schemaVersion", "tc.dashboard/v1");
        List<DashboardPublicationEligibilityRequirement> eligibility = new ArrayList<>();
        components.forEach(component -> eligibility.add(
                new DashboardPublicationEligibilityRequirement.HostComponent(
                        DashboardPublicationEligibilityRequirement.ComponentKind.valueOf(component.kind()),
                        component.componentVersion(), "1.0.0", "2.0.0")));
        resources.forEach(resource -> eligibility.add(
                new DashboardPublicationEligibilityRequirement.BuiltinResource(
                        resource.resourceId(), resource.digest())));
        return new DashboardPublicationCandidate(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
                schema, schema.toString(), "PG_JSONB_TEXT_V1_SHA256", digest,
                List.of(), components, resources, eligibility);
    }

    /** 从任意Maven工作目录向上定位仓库根。 */
    private static Path repositoryRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("things-link/pom.xml"))
                    && Files.isDirectory(candidate.resolve("things-link-client-contracts"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("无法定位ThingsLink仓库根目录");
    }
}
