package com.things.link.dashboard.application.publication;

import com.things.link.dashboard.application.draft.ApplicationDraftContractValidator;
import com.things.link.dashboard.application.draft.ValidatedApplicationDraft;
import com.things.link.dashboard.domain.ApplicationDraft;
import com.things.link.dashboard.domain.DashboardPublicationState;
import com.things.link.dashboard.domain.DashboardRepository;
import com.things.link.dashboard.domain.DashboardVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 应用发布候选的精确历史看板资格、聚合清单、导航顺序及零写入边界测试。 */
@DisplayName("应用规范发布候选")
class ApplicationPublicationCandidateFactoryTests {

    /** 测试JSON构造器。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 应用所属租户。 */
    private static final UUID TENANT_ID = uuid(1);
    /** 应用所属项目。 */
    private static final UUID PROJECT_ID = uuid(2);
    /** 应用ID。 */
    private static final UUID APPLICATION_ID = uuid(3);
    /** 草稿操作者。 */
    private static final UUID ACCOUNT_ID = uuid(4);
    /** 固定应用摘要。 */
    private static final String APPLICATION_DIGEST = "a".repeat(64);
    /** 固定看板摘要。 */
    private static final String DASHBOARD_DIGEST = "b".repeat(64);

    /** 应用草稿合同端口。 */
    private ApplicationDraftContractValidator draftValidator;
    /** 看板持久事实端口。 */
    private DashboardRepository dashboards;
    /** 看板不可变历史重验端口。 */
    private DashboardPublicationCandidateFactory dashboardCandidates;
    /** 应用快照PostgreSQL规范化端口。 */
    private ApplicationSnapshotCanonicalizer canonicalizer;
    /** 被测候选工厂。 */
    private DefaultApplicationPublicationCandidateFactory factory;

    /** 每例建立相互独立的只读端口替身。 */
    @BeforeEach
    void setUp() {
        draftValidator = mock(ApplicationDraftContractValidator.class);
        dashboards = mock(DashboardRepository.class);
        dashboardCandidates = mock(DashboardPublicationCandidateFactory.class);
        canonicalizer = mock(ApplicationSnapshotCanonicalizer.class);
        factory = new DefaultApplicationPublicationCandidateFactory(
                draftValidator, dashboards, dashboardCandidates, canonicalizer);
        when(canonicalizer.canonicalize(any())).thenReturn(new PostgreSqlApplicationSnapshotCanonicalForm(
                "{}", PostgreSqlApplicationSnapshotCanonicalForm.DIGEST_ALGORITHM, APPLICATION_DIGEST));
    }

    /** 草稿导航顺序必须保留，读取按稳定身份排序，且精确历史版本无需等于current。 */
    @Test
    @DisplayName("精确历史版本非current时仍生成保序候选")
    void historicalVersionNeedNotBeCurrentAndNavigationOrderIsPreserved() {
        UUID dashboardA = uuid(11);
        UUID dashboardB = uuid(12);
        UUID versionA = uuid(21);
        UUID versionB = uuid(22);
        ApplicationDraft draft = draft(content(List.of(
                reference(dashboardB, versionB, "导航乙"),
                reference(dashboardA, versionA, "导航甲")), dashboardB));
        accept(draft);
        DashboardVersion immutableA = version(dashboardA, versionA, 7, DASHBOARD_DIGEST);
        DashboardVersion immutableB = version(dashboardB, versionB, 9, "c".repeat(64));
        DashboardPublicationCandidate candidateA = dashboardCandidate(
                immutableA, pages("page_z", "末页", "page_a", "首页"),
                List.of(component("IMAGE"), component("TEXT")),
                List.of(resource("alpha", "d"), resource("shared", "e")));
        DashboardPublicationCandidate candidateB = dashboardCandidate(
                immutableB, pages("main", "主页面"), List.of(component("TEXT")),
                List.of(resource("beta", "f"), resource("shared", "e")));
        runnable(dashboardA, versionA, uuid(31), immutableA, candidateA);
        runnable(dashboardB, versionB, uuid(32), immutableB, candidateB);

        ApplicationPublicationCandidate result = factory.prepare(draft);

        assertThat(result.dashboardReferences())
                .extracting(ApplicationVersionDashboardReference::dashboardId)
                .containsExactly(dashboardB, dashboardA);
        assertThat(result.requiredSchemas()).containsExactly("tc.dashboard/v1");
        assertThat(result.requiredComponents()).containsExactly(component("IMAGE"), component("TEXT"));
        assertThat(result.requiredResources()).containsExactly(
                resource("alpha", "d"), resource("beta", "f"), resource("shared", "e"));
        assertThat(result.snapshot().path("dashboardRefs"))
                .extracting(value -> value.path("title").asString())
                .containsExactly("导航乙", "导航甲");
        assertThat(result.snapshot().path("dashboardRefs").get(1).path("pages"))
                .extracting(value -> value.path("id").asString())
                .containsExactly("page_z", "page_a");
        assertThat(result.snapshot().path("entryDashboardId").asString())
                .isEqualTo(dashboardB.toString());
        InOrder readOrder = inOrder(dashboards, dashboardCandidates);
        readOrder.verify(dashboards).findPublicationState(PROJECT_ID, dashboardA);
        readOrder.verify(dashboards).findVersion(PROJECT_ID, dashboardA, versionA);
        readOrder.verify(dashboardCandidates).prepareHistoricalVersion(immutableA);
        readOrder.verify(dashboards).findPublicationState(PROJECT_ID, dashboardB);
        readOrder.verify(dashboards).findVersion(PROJECT_ID, dashboardB, versionB);
        readOrder.verify(dashboardCandidates).prepareHistoricalVersion(immutableB);
    }

    /** 发布事务须按稳定看板ID加锁，但最终关系仍保持草稿导航顺序。 */
    @Test
    @DisplayName("锁内候选按看板ID稳定加锁并保持导航顺序")
    void lockedCandidateUsesStableDashboardLockOrderAndPreservesNavigationOrder() {
        UUID dashboardA = uuid(11);
        UUID dashboardB = uuid(12);
        UUID versionA = uuid(21);
        UUID versionB = uuid(22);
        ApplicationDraft draft = draft(content(List.of(
                reference(dashboardB, versionB, "导航乙"),
                reference(dashboardA, versionA, "导航甲")), dashboardB));
        accept(draft);
        DashboardVersion immutableA = version(dashboardA, versionA, 7, DASHBOARD_DIGEST);
        DashboardVersion immutableB = version(dashboardB, versionB, 9, "c".repeat(64));
        when(dashboards.lockPublicationState(PROJECT_ID, dashboardA))
                .thenReturn(Optional.of(state(dashboardA, uuid(31), null)));
        when(dashboards.lockPublicationState(PROJECT_ID, dashboardB))
                .thenReturn(Optional.of(state(dashboardB, uuid(32), null)));
        when(dashboards.findVersion(PROJECT_ID, dashboardA, versionA)).thenReturn(Optional.of(immutableA));
        when(dashboards.findVersion(PROJECT_ID, dashboardB, versionB)).thenReturn(Optional.of(immutableB));
        when(dashboardCandidates.prepareHistoricalVersion(immutableA)).thenReturn(
                dashboardCandidate(immutableA, pages("a", "甲"), List.of(), List.of()));
        when(dashboardCandidates.prepareHistoricalVersion(immutableB)).thenReturn(
                dashboardCandidate(immutableB, pages("b", "乙"), List.of(), List.of()));

        ApplicationPublicationCandidate result = factory.prepareLocked(draft);

        assertThat(result.dashboardReferences())
                .extracting(ApplicationVersionDashboardReference::dashboardId)
                .containsExactly(dashboardB, dashboardA);
        InOrder lockOrder = inOrder(dashboards, dashboardCandidates);
        lockOrder.verify(dashboards).lockPublicationState(PROJECT_ID, dashboardA);
        lockOrder.verify(dashboards).findVersion(PROJECT_ID, dashboardA, versionA);
        lockOrder.verify(dashboardCandidates).prepareHistoricalVersion(immutableA);
        lockOrder.verify(dashboards).lockPublicationState(PROJECT_ID, dashboardB);
        lockOrder.verify(dashboards).findVersion(PROJECT_ID, dashboardB, versionB);
        lockOrder.verify(dashboardCandidates).prepareHistoricalVersion(immutableB);
        verify(dashboards, never()).findPublicationState(any(), any());
    }

    /** 候选和规范化端口必须接收完整快照，调用方改写返回副本不能污染候选。 */
    @Test
    @DisplayName("快照规范化输入完整且候选防御复制")
    void canonicalSnapshotAndCandidateAreDefensivelyIsolated() {
        UUID dashboardId = uuid(13);
        UUID versionId = uuid(23);
        ApplicationDraft draft = draft(content(
                List.of(reference(dashboardId, versionId, "工厂总览")), dashboardId));
        accept(draft);
        DashboardVersion version = version(dashboardId, versionId, 1, DASHBOARD_DIGEST);
        runnable(dashboardId, versionId, versionId, version,
                dashboardCandidate(version, pages("main", "总览"), List.of(component("TEXT")), List.of()));
        ArgumentCaptor<JsonNode> snapshot = ArgumentCaptor.forClass(JsonNode.class);

        ApplicationPublicationCandidate result = factory.prepare(draft);

        verify(canonicalizer).canonicalize(snapshot.capture());
        assertThat(snapshot.getValue().path("formatVersion").asString()).isEqualTo("tc.application/v1");
        assertThat(snapshot.getValue().path("dashboardRefs")).hasSize(1);
        assertThat(snapshot.getValue().path("requiredSchemas").get(0).asString())
                .isEqualTo("tc.dashboard/v1");
        ((ObjectNode) result.snapshot()).put("displayName", "已篡改");
        assertThat(result.snapshot().path("displayName").asString()).isEqualTo("测试应用");
        assertThatThrownBy(() -> result.dashboardReferences().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.requiredComponents().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** 空草稿是合法编辑检查点但不是发布候选，必须在访问看板端口前拒绝。 */
    @Test
    @DisplayName("空引用草稿不能生成发布候选")
    void emptyDraftCannotProducePublicationCandidate() {
        ApplicationDraft draft = draft(content(List.of(), null));
        accept(draft);

        assertFailure(() -> factory.prepare(draft),
                ApplicationPublicationQualificationException.Reason.EMPTY_APPLICATION);
        verifyNoInteractions(dashboards, dashboardCandidates, canonicalizer);
    }

    /** 看板不存在、已撤回或已删除以及精确版本缺失必须分别安全拒绝。 */
    @Test
    @DisplayName("看板不可见不可运行或精确版本缺失均失败关闭")
    void missingWithdrawnDeletedAndMissingVersionFactsFailClosed() {
        UUID dashboardId = uuid(14);
        UUID versionId = uuid(24);
        ApplicationDraft draft = draft(content(
                List.of(reference(dashboardId, versionId, "运行入口")), dashboardId));
        accept(draft);

        assertFailure(() -> factory.prepare(draft),
                ApplicationPublicationQualificationException.Reason.DASHBOARD_REFERENCE_INVALID);

        when(dashboards.findPublicationState(PROJECT_ID, dashboardId))
                .thenReturn(Optional.of(state(dashboardId, null, null)));
        assertFailure(() -> factory.prepare(draft),
                ApplicationPublicationQualificationException.Reason.DASHBOARD_NOT_RUNNABLE);

        when(dashboards.findPublicationState(PROJECT_ID, dashboardId))
                .thenReturn(Optional.of(state(dashboardId, versionId, Instant.EPOCH)));
        assertFailure(() -> factory.prepare(draft),
                ApplicationPublicationQualificationException.Reason.DASHBOARD_NOT_RUNNABLE);

        when(dashboards.findPublicationState(PROJECT_ID, dashboardId))
                .thenReturn(Optional.of(state(dashboardId, versionId, null)));
        when(dashboards.findVersion(PROJECT_ID, dashboardId, versionId)).thenReturn(Optional.empty());
        assertFailure(() -> factory.prepare(draft),
                ApplicationPublicationQualificationException.Reason.DASHBOARD_REFERENCE_INVALID);
        verify(dashboardCandidates, never()).prepareHistoricalVersion(any());
    }

    /** 持久端口或历史重验值发生身份漂移时应视为内部完整性故障，且消息不得携带ID。 */
    @Test
    @DisplayName("看板状态版本或历史候选身份漂移不被信任")
    void identityDriftIsRejectedWithoutLeakingIdentifiers() {
        UUID dashboardId = uuid(15);
        UUID versionId = uuid(25);
        ApplicationDraft draft = draft(content(
                List.of(reference(dashboardId, versionId, "身份核验")), dashboardId));
        accept(draft);
        when(dashboards.findPublicationState(PROJECT_ID, dashboardId)).thenReturn(Optional.of(
                new DashboardPublicationState(dashboardId, TENANT_ID, uuid(99), 0, 1, versionId, 1, null)));

        assertReferenceFailureWithoutIdentifiers(() -> factory.prepare(draft), dashboardId, versionId);

        DashboardVersion version = version(dashboardId, versionId, 1, DASHBOARD_DIGEST);
        when(dashboards.findPublicationState(PROJECT_ID, dashboardId))
                .thenReturn(Optional.of(state(dashboardId, versionId, null)));
        when(dashboards.findVersion(PROJECT_ID, dashboardId, versionId)).thenReturn(Optional.of(version));
        when(dashboardCandidates.prepareHistoricalVersion(version)).thenReturn(new DashboardPublicationCandidate(
                uuid(98), TENANT_ID, PROJECT_ID, 0, pages("main", "入口"), "{}",
                PostgreSqlDashboardSchemaCanonicalForm.DIGEST_ALGORITHM, DASHBOARD_DIGEST,
                List.of(), List.of(), List.of(), List.of()));

        assertReferenceFailureWithoutIdentifiers(() -> factory.prepare(draft), dashboardId, versionId);
    }

    /** 跨看板聚合遇到同组件不同版本时不得任取其一。 */
    @Test
    @DisplayName("聚合同组件不同版本时失败关闭")
    void aggregateComponentVersionConflictFailsClosed() {
        UUID dashboardA = uuid(16);
        UUID dashboardB = uuid(17);
        UUID versionA = uuid(26);
        UUID versionB = uuid(27);
        ApplicationDraft draft = draft(content(List.of(
                reference(dashboardA, versionA, "甲"), reference(dashboardB, versionB, "乙")), dashboardA));
        accept(draft);
        DashboardVersion immutableA = version(dashboardA, versionA, 1, DASHBOARD_DIGEST);
        DashboardVersion immutableB = version(dashboardB, versionB, 1, "c".repeat(64));
        runnable(dashboardA, versionA, versionA, immutableA,
                dashboardCandidate(immutableA, pages("a", "甲"),
                        List.of(component("TEXT", "1.0.0")), List.of(resource("shared", "d"))));
        runnable(dashboardB, versionB, versionB, immutableB,
                dashboardCandidate(immutableB, pages("b", "乙"),
                        List.of(component("TEXT", "1.0.1")), List.of(resource("shared", "e"))));

        assertFailure(() -> factory.prepare(draft),
                ApplicationPublicationQualificationException.Reason.AGGREGATE_REQUIREMENT_INVALID);
        verify(canonicalizer, never()).canonicalize(any());
    }

    /** 跨看板聚合遇到同资源不同摘要时不得任取其一。 */
    @Test
    @DisplayName("聚合同资源不同摘要时失败关闭")
    void aggregateResourceDigestConflictFailsClosed() {
        UUID dashboardA = uuid(41);
        UUID dashboardB = uuid(42);
        UUID versionA = uuid(43);
        UUID versionB = uuid(44);
        ApplicationDraft draft = draft(content(List.of(
                reference(dashboardA, versionA, "甲"), reference(dashboardB, versionB, "乙")), dashboardA));
        accept(draft);
        DashboardVersion immutableA = version(dashboardA, versionA, 1, DASHBOARD_DIGEST);
        DashboardVersion immutableB = version(dashboardB, versionB, 1, "c".repeat(64));
        runnable(dashboardA, versionA, versionA, immutableA,
                dashboardCandidate(immutableA, pages("a", "甲"), List.of(),
                        List.of(resource("shared", "d"))));
        runnable(dashboardB, versionB, versionB, immutableB,
                dashboardCandidate(immutableB, pages("b", "乙"), List.of(),
                        List.of(resource("shared", "e"))));

        assertFailure(() -> factory.prepare(draft),
                ApplicationPublicationQualificationException.Reason.AGGREGATE_REQUIREMENT_INVALID);
        verify(canonicalizer, never()).canonicalize(any());
    }

    /** 应用级资源并集最多50项，多个各自合法看板不能绕过聚合上限。 */
    @Test
    @DisplayName("跨看板资源并集超过五十项时拒绝")
    void aggregateResourceLimitCannotBeBypassedAcrossDashboards() {
        UUID dashboardA = uuid(18);
        UUID dashboardB = uuid(19);
        UUID versionA = uuid(28);
        UUID versionB = uuid(29);
        ApplicationDraft draft = draft(content(List.of(
                reference(dashboardA, versionA, "甲"), reference(dashboardB, versionB, "乙")), dashboardA));
        accept(draft);
        DashboardVersion immutableA = version(dashboardA, versionA, 1, DASHBOARD_DIGEST);
        DashboardVersion immutableB = version(dashboardB, versionB, 1, "c".repeat(64));
        List<DashboardRequiredResource> fifty = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            fifty.add(resource("r%02d".formatted(index), "d"));
        }
        runnable(dashboardA, versionA, versionA, immutableA,
                dashboardCandidate(immutableA, pages("a", "甲"), List.of(), fifty));
        runnable(dashboardB, versionB, versionB, immutableB,
                dashboardCandidate(immutableB, pages("b", "乙"), List.of(),
                        List.of(resource("z", "e"))));

        assertFailure(() -> factory.prepare(draft),
                ApplicationPublicationQualificationException.Reason.AGGREGATE_REQUIREMENT_INVALID);
        verify(canonicalizer, never()).canonicalize(any());
    }

    /** 让草稿校验端口返回与持久revision不同的结果必须作为内部故障停止。 */
    @Test
    @DisplayName("草稿校验结果必须绑定指定持久revision")
    void validatedDraftRevisionMustMatchPersistedRevision() {
        UUID dashboardId = uuid(20);
        UUID versionId = uuid(30);
        ApplicationDraft draft = draft(content(
                List.of(reference(dashboardId, versionId, "入口")), dashboardId));
        when(draftValidator.validate(eq("7"), any(byte[].class)))
                .thenReturn(new ValidatedApplicationDraft(6, draft.content()));

        assertThatThrownBy(() -> factory.prepare(draft))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("应用草稿校验结果绑定了错误revision");
        verifyNoInteractions(dashboards, dashboardCandidates, canonicalizer);
    }

    /** 配置草稿校验成功返回。 */
    private void accept(ApplicationDraft draft) {
        when(draftValidator.validate(eq(Long.toString(draft.revision())), any(byte[].class)))
                .thenReturn(new ValidatedApplicationDraft(draft.revision(), draft.content()));
    }

    /** 配置一个当前可运行但精确目标可为历史版本的看板事实。 */
    private void runnable(
            UUID dashboardId, UUID versionId, UUID currentVersionId,
            DashboardVersion version, DashboardPublicationCandidate candidate) {
        when(dashboards.findPublicationState(PROJECT_ID, dashboardId))
                .thenReturn(Optional.of(state(dashboardId, currentVersionId, null)));
        when(dashboards.findVersion(PROJECT_ID, dashboardId, versionId)).thenReturn(Optional.of(version));
        when(dashboardCandidates.prepareHistoricalVersion(version)).thenReturn(candidate);
    }

    /** 构造应用持久草稿。 */
    private static ApplicationDraft draft(ObjectNode content) {
        Instant now = Instant.parse("2026-09-06T12:00:00Z");
        return new ApplicationDraft(APPLICATION_ID, TENANT_ID, PROJECT_ID, content, 7, ACCOUNT_ID, now, now);
    }

    /** 构造完整应用草稿JSON。 */
    private static ObjectNode content(List<ObjectNode> references, UUID entryDashboardId) {
        ObjectNode content = JSON.createObjectNode()
                .put("formatVersion", "tc.application/v1")
                .put("displayName", "测试应用");
        content.putObject("hostCompatibility")
                .put("minInclusive", "1.0.0")
                .put("maxExclusive", "2.0.0");
        ArrayNode values = content.putArray("dashboardRefs");
        references.forEach(values::add);
        if (entryDashboardId == null) {
            content.putNull("entryDashboardId");
        } else {
            content.put("entryDashboardId", entryDashboardId.toString());
        }
        return content;
    }

    /** 构造一个草稿精确看板引用。 */
    private static ObjectNode reference(UUID dashboardId, UUID versionId, String title) {
        return JSON.createObjectNode()
                .put("dashboardId", dashboardId.toString())
                .put("dashboardVersionId", versionId.toString())
                .put("title", title);
    }

    /** 构造看板三轴状态。 */
    private static DashboardPublicationState state(
            UUID dashboardId, UUID currentVersionId, Instant deletedAt) {
        return new DashboardPublicationState(
                dashboardId, TENANT_ID, PROJECT_ID, 3,
                currentVersionId == null ? 2 : 3, currentVersionId, 9, deletedAt);
    }

    /** 构造精确不可变看板版本。 */
    private static DashboardVersion version(
            UUID dashboardId, UUID versionId, long versionNumber, String digest) {
        ObjectNode schema = pages("main", "总览");
        return new DashboardVersion(
                versionId, TENANT_ID, PROJECT_ID, dashboardId, versionNumber, 2,
                schema, "tc.dashboard/v1", PostgreSqlDashboardSchemaCanonicalForm.DIGEST_ALGORITHM,
                digest, JSON.createArrayNode(), JSON.createArrayNode(), ACCOUNT_ID,
                Instant.parse("2026-09-06T12:00:00Z"), List.of());
    }

    /** 构造已由看板历史重验产生的候选。 */
    private static DashboardPublicationCandidate dashboardCandidate(
            DashboardVersion version, ObjectNode schema,
            List<DashboardRequiredComponent> components, List<DashboardRequiredResource> resources) {
        List<DashboardPublicationEligibilityRequirement> requirements = new ArrayList<>();
        components.forEach(component -> requirements.add(
                new DashboardPublicationEligibilityRequirement.HostComponent(
                        DashboardPublicationEligibilityRequirement.ComponentKind.valueOf(component.kind()),
                        component.componentVersion(), "1.0.0", "2.0.0")));
        resources.forEach(resource -> requirements.add(
                new DashboardPublicationEligibilityRequirement.BuiltinResource(
                        resource.resourceId(), resource.digest())));
        return new DashboardPublicationCandidate(
                version.dashboardId(), TENANT_ID, PROJECT_ID, version.sourceDraftRevision(),
                schema, schema.toString(), PostgreSqlDashboardSchemaCanonicalForm.DIGEST_ALGORITHM,
                version.schemaDigest(), List.of(), components, resources, requirements);
    }

    /** 构造保序页面Schema。 */
    private static ObjectNode pages(String... idAndTitle) {
        ObjectNode schema = JSON.createObjectNode().put("schemaVersion", "tc.dashboard/v1");
        schema.putObject("presentation").put("mode", "RESPONSIVE_GRID");
        schema.putArray("models");
        schema.putArray("variables");
        ArrayNode pages = schema.putArray("pages");
        for (int index = 0; index < idAndTitle.length; index += 2) {
            pages.addObject().put("id", idAndTitle[index]).put("title", idAndTitle[index + 1])
                    .putArray("components");
        }
        return schema;
    }

    /** 构造1.0.0组件需求。 */
    private static DashboardRequiredComponent component(String kind) {
        return component(kind, "1.0.0");
    }

    /** 构造指定版本组件需求。 */
    private static DashboardRequiredComponent component(String kind, String version) {
        return new DashboardRequiredComponent(kind, version);
    }

    /** 构造资源需求；短摘要标记扩展为合法SHA-256。 */
    private static DashboardRequiredResource resource(String id, String digestMarker) {
        return new DashboardRequiredResource(id, digestMarker.repeat(64));
    }

    /** 断言安全业务原因。 */
    private static void assertFailure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            ApplicationPublicationQualificationException.Reason reason) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(ApplicationPublicationQualificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason));
    }

    /** 断言身份漂移收敛为不含外部ID的安全引用拒绝。 */
    private static void assertReferenceFailureWithoutIdentifiers(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            UUID dashboardId, UUID versionId) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(ApplicationPublicationQualificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                ApplicationPublicationQualificationException.Reason.DASHBOARD_REFERENCE_INVALID))
                .hasMessageNotContaining(dashboardId.toString())
                .hasMessageNotContaining(versionId.toString());
    }

    /** 构造可读确定UUID。 */
    private static UUID uuid(int value) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(value));
    }
}
