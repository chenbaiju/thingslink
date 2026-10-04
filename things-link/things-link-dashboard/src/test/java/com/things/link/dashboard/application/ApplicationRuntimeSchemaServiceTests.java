package com.things.link.dashboard.application;

import com.things.link.dashboard.application.publication.DashboardPublicationCandidate;
import com.things.link.dashboard.application.publication.DashboardPublicationCandidateFactory;
import com.things.link.dashboard.application.publication.DashboardRequiredComponent;
import com.things.link.dashboard.application.publication.DashboardRequiredResource;
import com.things.link.dashboard.domain.ApplicationRuntimeSchemaRepository;
import com.things.link.dashboard.domain.CurrentApplicationRuntimeProjection;
import com.things.link.dashboard.domain.DashboardVersion;
import com.things.link.dashboard.domain.RuntimeDashboardSchemaProjection;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 单Dashboard运行Schema服务的事务、身份、内部复核及生命周期顺序单测。 */
class ApplicationRuntimeSchemaServiceTests {

    /** 冻结语法的测试应用公开键。 */
    private static final String APP_KEY = "app_0123456789abcdef0123456789abcdef";
    /** 当前应用发布代次。 */
    private static final long PUBLICATION_REVISION = 7;

    /** 服务必须加入调用方只读事务，不能脱离enduser身份与grant重验观察。 */
    @Test
    void requiresMandatoryReadOnlyTransaction() throws Exception {
        Method method = ApplicationRuntimeSchemaService.class.getMethod(
                "findSchema", UUID.class, UUID.class, String.class,
                UUID.class, long.class, UUID.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(transactional.readOnly()).isTrue();
    }

    /** 成功只投影目标Schema及其派生清单，并保持请求绑定的应用版本和发布代次。 */
    @Test
    void returnsExactSchemaAfterHistoricalIntegrityRecheck() {
        Fixture fixture = fixture(true);
        DashboardPublicationCandidate candidate = candidate(fixture);
        when(fixture.repository().findSchema(
                fixture.tenantId(), fixture.projectId(), APP_KEY, fixture.applicationVersionId(),
                PUBLICATION_REVISION, fixture.dashboardVersion().id()))
                .thenReturn(Optional.of(fixture.projection()));
        when(fixture.candidateFactory().prepareHistoricalVersion(fixture.dashboardVersion()))
                .thenReturn(candidate);

        RuntimeDashboardSchema schema = fixture.service().findSchema(
                fixture.tenantId(), fixture.projectId(), APP_KEY, fixture.applicationVersionId(),
                PUBLICATION_REVISION, fixture.dashboardVersion().id()).orElseThrow();

        assertThat(schema.applicationVersionId()).isEqualTo(fixture.applicationVersionId());
        assertThat(schema.publicationRevision()).isEqualTo(PUBLICATION_REVISION);
        assertThat(schema.dashboardVersionId()).isEqualTo(fixture.dashboardVersion().id());
        assertThat(schema.requiredComponents()).containsExactly(
                new DashboardRequiredComponent("TEXT", "1.0.0"));
        assertThat(schema.requiredResources()).containsExactly(
                new DashboardRequiredResource("builtin/logo", "a".repeat(64)));
        assertThat(schema.schema()).isEqualTo(candidate.normalizedSchema());
        assertThat(schema.schemaUtf8Bytes()).isEqualTo(256);
    }

    /** 看板撤回或软删仍先重跑历史Schema完整性，再返回确定不可运行。 */
    @Test
    void validatesPersistedSchemaBeforeFilteringUnavailableDashboard() {
        Fixture fixture = fixture(false);
        when(fixture.repository().findSchema(
                fixture.tenantId(), fixture.projectId(), APP_KEY, fixture.applicationVersionId(),
                PUBLICATION_REVISION, fixture.dashboardVersion().id()))
                .thenReturn(Optional.of(fixture.projection()));
        DashboardPublicationCandidate candidate = candidate(fixture);
        when(fixture.candidateFactory().prepareHistoricalVersion(fixture.dashboardVersion()))
                .thenReturn(candidate);

        assertThat(fixture.service().findSchema(
                fixture.tenantId(), fixture.projectId(), APP_KEY, fixture.applicationVersionId(),
                PUBLICATION_REVISION, fixture.dashboardVersion().id())).isEmpty();

        verify(fixture.candidateFactory()).prepareHistoricalVersion(fixture.dashboardVersion());
    }

    /** 历史Schema解析或派生清单漂移统一成为内部完整性异常并保留原首因。 */
    @Test
    void wrapsHistoricalSchemaValidationFailureAsInternalIntegrityFailure() {
        Fixture fixture = fixture(true);
        RuntimeException cause = new RuntimeException("required_components drift");
        when(fixture.repository().findSchema(
                fixture.tenantId(), fixture.projectId(), APP_KEY, fixture.applicationVersionId(),
                PUBLICATION_REVISION, fixture.dashboardVersion().id()))
                .thenReturn(Optional.of(fixture.projection()));
        when(fixture.candidateFactory().prepareHistoricalVersion(fixture.dashboardVersion())).thenThrow(cause);

        assertThatThrownBy(() -> fixture.service().findSchema(
                fixture.tenantId(), fixture.projectId(), APP_KEY, fixture.applicationVersionId(),
                PUBLICATION_REVISION, fixture.dashboardVersion().id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("内部语义")
                .hasCause(cause);
    }

    /** 仓储确定当前上下文或目标引用不匹配时直接为空，不运行无目标的Schema解析。 */
    @Test
    void returnsEmptyWithoutCandidateRecheckWhenTargetIsNotReferenced() {
        Fixture fixture = fixture(true);
        when(fixture.repository().findSchema(
                fixture.tenantId(), fixture.projectId(), APP_KEY, fixture.applicationVersionId(),
                PUBLICATION_REVISION, fixture.dashboardVersion().id())).thenReturn(Optional.empty());

        assertThat(fixture.service().findSchema(
                fixture.tenantId(), fixture.projectId(), APP_KEY, fixture.applicationVersionId(),
                PUBLICATION_REVISION, fixture.dashboardVersion().id())).isEmpty();

        verify(fixture.candidateFactory(), never()).prepareHistoricalVersion(fixture.dashboardVersion());
    }

    /** 仓储投影若漂移应用版本、代次或目标版本，必须按内部不变量拒绝。 */
    @Test
    void rejectsRepositoryIdentityDriftBeforeSchemaRecheck() {
        Fixture fixture = fixture(true);
        RuntimeDashboardSchemaProjection drifted = new RuntimeDashboardSchemaProjection(
                fixture.tenantId(), fixture.projectId(), UUID.randomUUID(), APP_KEY,
                UUID.randomUUID(), PUBLICATION_REVISION, true, fixture.dashboardVersion(),
                fixture.projection().navigationPages(), 256);
        when(fixture.repository().findSchema(
                fixture.tenantId(), fixture.projectId(), APP_KEY, fixture.applicationVersionId(),
                PUBLICATION_REVISION, fixture.dashboardVersion().id())).thenReturn(Optional.of(drifted));

        assertThatThrownBy(() -> fixture.service().findSchema(
                fixture.tenantId(), fixture.projectId(), APP_KEY, fixture.applicationVersionId(),
                PUBLICATION_REVISION, fixture.dashboardVersion().id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("身份发生漂移");

        verify(fixture.candidateFactory(), never()).prepareHistoricalVersion(fixture.dashboardVersion());
    }

    /** 应用快照页面导航与目标Schema不一致时必须内部拒绝，引用标题本身不参与该逐页比较。 */
    @Test
    void rejectsApplicationNavigationDriftFromTargetSchema() {
        Fixture fixture = fixture(true);
        RuntimeDashboardSchemaProjection drifted = new RuntimeDashboardSchemaProjection(
                fixture.projection().tenantId(), fixture.projection().projectId(),
                fixture.projection().applicationId(), fixture.projection().appKey(),
                fixture.projection().applicationVersionId(), fixture.projection().publicationRevision(), true,
                fixture.dashboardVersion(),
                List.of(new CurrentApplicationRuntimeProjection.Page("main", "伪造导航")), 256);
        when(fixture.repository().findSchema(
                fixture.tenantId(), fixture.projectId(), APP_KEY, fixture.applicationVersionId(),
                PUBLICATION_REVISION, fixture.dashboardVersion().id())).thenReturn(Optional.of(drifted));
        DashboardPublicationCandidate candidate = candidate(fixture);
        when(fixture.candidateFactory().prepareHistoricalVersion(fixture.dashboardVersion()))
                .thenReturn(candidate);

        assertThatThrownBy(() -> fixture.service().findSchema(
                fixture.tenantId(), fixture.projectId(), APP_KEY, fixture.applicationVersionId(),
                PUBLICATION_REVISION, fixture.dashboardVersion().id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("页面导航");
    }

    /** 创建一个带真实领域版本和Mockito端口的服务夹具。 */
    private static Fixture fixture(boolean runnable) {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID applicationVersionId = UUID.randomUUID();
        UUID dashboardId = UUID.randomUUID();
        tools.jackson.databind.node.ObjectNode schema = JsonMapper.builder().build().createObjectNode();
        schema.put("schemaVersion", "tc.dashboard/v1");
        schema.putArray("models");
        schema.putArray("pages").addObject().put("id", "main").put("title", "总览");
        DashboardVersion version = new DashboardVersion(
                UUID.randomUUID(), tenantId, projectId, dashboardId, 3, 2, schema,
                "tc.dashboard/v1", "PG_JSONB_TEXT_V1_SHA256", "d".repeat(64),
                JsonMapper.builder().build().createArrayNode(),
                JsonMapper.builder().build().createArrayNode(),
                UUID.randomUUID(), Instant.parse("2026-09-07T00:00:00Z"), List.of());
        ApplicationRuntimeSchemaRepository repository = mock(ApplicationRuntimeSchemaRepository.class);
        DashboardPublicationCandidateFactory candidateFactory = mock(DashboardPublicationCandidateFactory.class);
        RuntimeDashboardSchemaProjection projection = new RuntimeDashboardSchemaProjection(
                tenantId, projectId, UUID.randomUUID(), APP_KEY, applicationVersionId,
                PUBLICATION_REVISION, runnable, version,
                List.of(new CurrentApplicationRuntimeProjection.Page("main", "总览")), 256);
        return new Fixture(tenantId, projectId, applicationVersionId, version, repository,
                candidateFactory, projection, new ApplicationRuntimeSchemaService(repository, candidateFactory));
    }

    /** 为成功服务映射提供已经完成内部复核的候选替身。 */
    private static DashboardPublicationCandidate candidate(Fixture fixture) {
        DashboardPublicationCandidate candidate = mock(DashboardPublicationCandidate.class);
        when(candidate.dashboardId()).thenReturn(fixture.dashboardVersion().dashboardId());
        when(candidate.tenantId()).thenReturn(fixture.tenantId());
        when(candidate.projectId()).thenReturn(fixture.projectId());
        when(candidate.sourceDraftRevision()).thenReturn(fixture.dashboardVersion().sourceDraftRevision());
        when(candidate.schemaDigestAlgorithm()).thenReturn("PG_JSONB_TEXT_V1_SHA256");
        when(candidate.schemaDigest()).thenReturn("d".repeat(64));
        when(candidate.requiredComponents()).thenReturn(
                List.of(new DashboardRequiredComponent("TEXT", "1.0.0")));
        when(candidate.requiredResources()).thenReturn(
                List.of(new DashboardRequiredResource("builtin/logo", "a".repeat(64))));
        when(candidate.normalizedSchema()).thenReturn(fixture.dashboardVersion().schema());
        return candidate;
    }

    /**
     * 服务测试所需的当前应用和精确看板版本事实。
     *
     * @param tenantId 租户ID
     * @param projectId 项目ID
     * @param applicationVersionId 当前应用版本ID
     * @param dashboardVersion 精确看板版本
     * @param repository Schema持久端口替身
     * @param candidateFactory 历史Schema复核器替身
     * @param projection 仓储投影
     * @param service 被测服务
     */
    private record Fixture(
            UUID tenantId,
            UUID projectId,
            UUID applicationVersionId,
            DashboardVersion dashboardVersion,
            ApplicationRuntimeSchemaRepository repository,
            DashboardPublicationCandidateFactory candidateFactory,
            RuntimeDashboardSchemaProjection projection,
            ApplicationRuntimeSchemaService service) {
    }
}
