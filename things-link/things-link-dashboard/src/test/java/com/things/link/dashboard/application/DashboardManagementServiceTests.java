package com.things.link.dashboard.application;

import com.things.link.dashboard.application.draft.DashboardDraftContractValidator;
import com.things.link.dashboard.application.draft.DashboardDraftContractViolation;
import com.things.link.dashboard.application.draft.ValidatedDashboardDraft;
import com.things.link.dashboard.application.draft.ValidatedDashboardModelReference;
import com.things.link.dashboard.domain.DashboardCatalogEntry;
import com.things.link.dashboard.domain.DashboardCreationResult;
import com.things.link.dashboard.domain.DashboardDraft;
import com.things.link.dashboard.domain.DashboardDraftSaveResult;
import com.things.link.dashboard.domain.DashboardRepository;
import com.things.link.dashboard.domain.DashboardVersion;
import com.things.link.dashboard.domain.DashboardVersionLookupResult;
import com.things.link.dashboard.domain.DashboardVersionSummary;
import com.things.link.device.application.ThingModelVersionDescriptor;
import com.things.link.device.application.ThingModelVersionDescriptorPort;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/** 看板草稿管理的角色、持续ACTIVE许可、外部模型核验、CAS与审计语义单测。 */
@DisplayName("看板草稿管理")
class DashboardManagementServiceTests {

    /** 固定模型版本ID便于构造同一精确外部候选。 */
    private static final UUID MODEL_VERSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000201");
    /** 固定合法物模型摘要。 */
    private static final String MODEL_DIGEST = "a".repeat(64);
    /** 测试JSON构造器。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 看板持久端口模拟。 */
    private DashboardRepository repository;
    /** 项目角色与归属端口模拟。 */
    private ProjectService projectService;
    /** 项目持续写许可模拟。 */
    private ProjectLifecycleAccessService lifecycleAccessService;
    /** 严格草稿合同门面模拟。 */
    private DashboardDraftContractValidator validator;
    /** Device公开模型描述端口模拟。 */
    private ThingModelVersionDescriptorPort descriptors;
    /** 同事务审计端口模拟。 */
    private AuditLogService audits;
    /** 被测服务。 */
    private DashboardManagementService service;
    private com.things.link.project.application.PlanCapacityService capacity;
    /** 当前项目ID。 */
    private UUID projectId;
    /** 项目owner租户ID。 */
    private UUID ownerTenantId;
    /** 当前Console账号ID。 */
    private UUID accountId;
    /** 当前规范草稿候选。 */
    private ValidatedDashboardDraft validated;

    /** 每例建立默认OWNER、ACTIVE许可所需归属及匹配模型描述。 */
    @BeforeEach
    void setUp() {
        repository = mock(DashboardRepository.class);
        projectService = mock(ProjectService.class);
        lifecycleAccessService = mock(ProjectLifecycleAccessService.class);
        validator = mock(DashboardDraftContractValidator.class);
        descriptors = mock(ThingModelVersionDescriptorPort.class);
        audits = mock(AuditLogService.class);
        capacity = mock(com.things.link.project.application.PlanCapacityService.class);
        when(capacity.dashboardsLimit(any(), any())).thenReturn(10L);
        service = new DashboardManagementService(
                repository, projectService, lifecycleAccessService, validator, descriptors, audits,
                capacity, mock(com.things.link.project.application.SubscriptionExpansionGuard.class));
        projectId = UUID.randomUUID();
        ownerTenantId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        TenantContext.set(new TenantScope(UUID.randomUUID(), projectId, accountId));
        validated = validated(0, "初始草稿");
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.OWNER);
        when(projectService.requireProjectTenant(projectId)).thenReturn(ownerTenantId);
        when(validator.validate(anyString(), any(byte[].class))).thenReturn(validated);
        when(descriptors.find(projectId, MODEL_VERSION_ID)).thenReturn(Optional.of(descriptor()));
    }

    /** 清理线程身份，避免角色用例之间相互继承。 */
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** OWNER、ADMIN、OPERATOR和VIEWER均可读取目录页、详情和草稿。 */
    @ParameterizedTest
    @EnumSource(ProjectRole.class)
    @DisplayName("四种项目角色均可读取看板管理事实")
    void allProjectRolesCanReadDashboardManagementFacts(ProjectRole role) {
        UUID dashboardId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        DashboardCatalogEntry catalog = catalog(dashboardId, "可读看板");
        DashboardDraft draft = draft(dashboardId, 0, validated);
        DashboardVersion version = version(dashboardId, versionId, 1, 0);
        DashboardVersionSummary summary = summary(version);
        when(projectService.requireRoleInProject(projectId)).thenReturn(role);
        when(repository.page(projectId, null, 20)).thenReturn(CursorPage.last(List.of(catalog)));
        when(repository.find(projectId, dashboardId)).thenReturn(Optional.of(catalog));
        when(repository.findDraft(projectId, dashboardId)).thenReturn(Optional.of(draft));
        when(repository.pageVersions(projectId, dashboardId, null, 20))
                .thenReturn(Optional.of(CursorPage.last(List.of(summary))));
        when(repository.findVersionForManagement(projectId, dashboardId, versionId))
                .thenReturn(DashboardVersionLookupResult.found(version));

        assertThat(service.list(projectId, null, 20).items()).containsExactly(catalog);
        assertThat(service.find(projectId, dashboardId)).isEqualTo(catalog);
        assertThat(service.getDraft(projectId, dashboardId)).isEqualTo(draft);
        assertThat(service.listVersions(projectId, dashboardId, null, 20).items()).containsExactly(summary);
        assertThat(service.getVersion(projectId, dashboardId, versionId)).isEqualTo(version);
    }

    /** OWNER与ADMIN创建均须按锁前角色、owner tenant、ACTIVE许可、锁后角色的固定顺序执行。 */
    @ParameterizedTest
    @EnumSource(value = ProjectRole.class, names = {"OWNER", "ADMIN"})
    @DisplayName("管理角色在持续ACTIVE许可内校验并创建看板")
    void managementRolesCreateAfterLockedRoleRecheck(ProjectRole role) {
        when(projectService.requireRoleInProject(projectId)).thenReturn(role);

        DashboardCatalogEntry created = service.create(projectId, "设备总览", new byte[]{1});

        InOrder order = inOrder(projectService, lifecycleAccessService, validator, descriptors, repository, audits);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(projectService).requireProjectTenant(projectId);
        order.verify(lifecycleAccessService).requireActiveForWrite(ownerTenantId, projectId);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(validator).validate(eq("0"), any(byte[].class));
        order.verify(descriptors).find(projectId, MODEL_VERSION_ID);
        order.verify(repository).create(any(DashboardCatalogEntry.class), any(DashboardDraft.class));
        order.verify(audits).record(any(AuditLogEntry.class));
        assertThat(created.tenantId()).isEqualTo(ownerTenantId);
        assertThat(created.createdBy()).isEqualTo(accountId);

        ArgumentCaptor<AuditLogEntry> audit = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(audits).record(audit.capture());
        assertThat(audit.getValue().targetType()).isEqualTo("dashboard");
        assertThat(audit.getValue().targetId()).isEqualTo(created.id());
        assertThat(audit.getValue().action()).isEqualTo("dashboard.created");
        assertThat(audit.getValue().details()).isEqualTo(java.util.Map.of("draftRevision", "0"));
    }

    /** 首次幂等创建持久化恢复映射；同请求重放返回原身份且不重验外部模型或重复审计。 */
    @Test
    @DisplayName("同一看板创建请求稳定恢复首次身份")
    void sameIdempotentCreationRestoresFirstDashboardWithoutDuplicateAudit() {
        byte[] source = new byte[]{1};
        DashboardCatalogEntry created = service.createIdempotent(
                projectId, "dashboard-key", "设备总览", source);
        ArgumentCaptor<DashboardCreationResult> mapping =
                ArgumentCaptor.forClass(DashboardCreationResult.class);
        verify(repository).createIdempotent(any(), any(), mapping.capture());
        DashboardCreationResult persisted = mapping.getValue();
        when(repository.findCreationResult(ownerTenantId, projectId, accountId,
                persisted.idempotencyKeyDigest())).thenReturn(Optional.of(persisted));
        when(repository.findCreationDashboard(projectId, created.id())).thenReturn(Optional.of(created));
        clearInvocations(validator, descriptors);

        DashboardCatalogEntry replayed = service.createIdempotent(
                projectId, "dashboard-key", "设备总览", source);

        assertThat(replayed).isSameAs(created);
        verify(repository, times(1)).createIdempotent(any(), any(), any());
        verify(audits, times(1)).record(any());
        verify(validator, never()).validate(anyString(), any());
        verify(descriptors, never()).find(any(), any());
        assertThat(persisted.dashboardId()).isEqualTo(created.id());
        assertThat(persisted.idempotencyKeyDigest()).matches("[0-9a-f]{64}");
        assertThat(persisted.requestDigest()).matches("[0-9a-f]{64}");
    }

    /** 同键异请求在恢复目录前拒绝；已软删结果则稳定10014并保留原映射。 */
    @Test
    @DisplayName("同键异请求和已删除结果使用公共幂等错误")
    void conflictingAndDeletedIdempotentCreationsUseCommonErrors() {
        byte[] source = new byte[]{1};
        DashboardCatalogEntry created = service.createIdempotent(
                projectId, "dashboard-key", "设备总览", source);
        ArgumentCaptor<DashboardCreationResult> mapping =
                ArgumentCaptor.forClass(DashboardCreationResult.class);
        verify(repository).createIdempotent(any(), any(), mapping.capture());
        DashboardCreationResult persisted = mapping.getValue();
        when(repository.findCreationResult(ownerTenantId, projectId, accountId,
                persisted.idempotencyKeyDigest())).thenReturn(Optional.of(persisted));

        assertBusinessError(() -> service.createIdempotent(
                projectId, "dashboard-key", "另一请求", source), 10009);
        DashboardCatalogEntry deleted = new DashboardCatalogEntry(
                created.id(), created.tenantId(), created.projectId(), created.managementName(),
                created.publicationRevision(), created.currentVersionId(), created.createdBy(),
                created.updatedBy(), created.createdAt(), created.updatedAt(), created.updatedAt());
        when(repository.findCreationDashboard(projectId, created.id())).thenReturn(Optional.of(deleted));
        assertBusinessError(() -> service.createIdempotent(
                projectId, "dashboard-key", "设备总览", source), 10014);

        verify(repository, times(1)).createIdempotent(any(), any(), any());
        verify(audits, times(1)).record(any());
    }

    /** 锁前OWNER若在ACTIVE许可持有后降级，必须在Schema与任何持久写入前再次拒绝。 */
    @Test
    @DisplayName("锁后角色降级停止草稿校验与持久写入")
    void roleDowngradeAfterLifecycleLockStopsBeforeValidationAndPersistence() {
        when(projectService.requireRoleInProject(projectId))
                .thenReturn(ProjectRole.OWNER, ProjectRole.VIEWER);

        assertBusinessError(() -> service.create(projectId, "降级看板", new byte[]{1}), 60035);

        verify(lifecycleAccessService).requireActiveForWrite(ownerTenantId, projectId);
        verify(validator, never()).validate(anyString(), any());
        verify(descriptors, never()).find(any(), any());
        verify(repository, never()).create(any(), any());
        verify(audits, never()).record(any());
    }

    /** OPERATOR与VIEWER写入必须在ACTIVE许可、Schema和外部模型端口前稳定拒绝。 */
    @ParameterizedTest
    @EnumSource(value = ProjectRole.class, names = {"OPERATOR", "VIEWER"})
    @DisplayName("只读角色不能管理看板")
    void readOnlyRolesCannotManageDashboards(ProjectRole role) {
        when(projectService.requireRoleInProject(projectId)).thenReturn(role);

        assertBusinessError(() -> service.create(projectId, "越权看板", new byte[]{1}), 60035);

        verify(lifecycleAccessService, never()).requireActiveForWrite(any(), any());
        verify(validator, never()).validate(anyString(), any());
        verify(descriptors, never()).find(any(), any());
        verify(repository, never()).create(any(), any());
        verify(audits, never()).record(any());
    }

    /** 缺失、错项目及任一不可变摘要字段不匹配均须收敛为无详情的同一安全错误。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDescriptors")
    @DisplayName("外部模型不匹配统一安全失败")
    void invalidExternalModelReferencesUseOneSafeFailure(String scenario) {
        ThingModelVersionDescriptor actual = invalidDescriptor(scenario);
        when(descriptors.find(projectId, MODEL_VERSION_ID)).thenReturn(Optional.ofNullable(actual));

        assertThatThrownBy(() -> service.create(projectId, "模型失败", new byte[]{1}))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.errorCode().code()).isEqualTo(60038);
                    assertThat(failure.details()).isEmpty();
                    assertThat(failure.getMessage()).isEqualTo("看板外部模型引用不合法");
                    assertThat(failure.getMessage()).doesNotContain(
                            MODEL_VERSION_ID.toString(), MODEL_DIGEST, scenario);
                });
        verify(repository, never()).create(any(), any());
        verify(audits, never()).record(any());
    }

    /** 草稿结构拒绝只公开稳定reason和安全路径，且不能继续枚举模型或写数据库。 */
    @Test
    @DisplayName("草稿合同失败停止外部查询与持久写入")
    void draftContractFailureStopsBeforeExternalLookupAndPersistence() {
        when(validator.validate(anyString(), any(byte[].class))).thenThrow(
                new DashboardDraftContractViolation(
                        "PARSE_DUPLICATE_KEY", "$.pages[0]", "固定安全说明"));

        assertThatThrownBy(() -> service.create(projectId, "非法草稿", new byte[]{1}))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.errorCode().code()).isEqualTo(60036);
                    assertThat(failure.details()).containsExactly("PARSE_DUPLICATE_KEY@$.pages[0]");
                });
        verify(descriptors, never()).find(any(), any());
        verify(repository, never()).create(any(), any());
        verify(audits, never()).record(any());
    }

    /** 仓储不存在、旧revision与Long耗尽必须映射到稳定且互不猜测的公开结果。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedSaveResults")
    @DisplayName("草稿CAS失败映射稳定业务错误")
    void draftCasFailuresMapToStableBusinessErrors(
            String scenario, DashboardDraftSaveResult result, int expectedCode) {
        UUID dashboardId = UUID.randomUUID();
        when(repository.saveDraft(eq(projectId), eq(dashboardId), eq(0L), any(), any(),
                eq(accountId), any(Instant.class))).thenReturn(result);

        assertBusinessError(() -> service.saveDraft(
                projectId, dashboardId, "0", new byte[]{1}), expectedCode);

        verify(audits, never()).record(any());
    }

    /** 成功CAS必须返回同次保存事实，并只审计递增后的revision。 */
    @Test
    @DisplayName("草稿CAS成功记录精确revision审计")
    void successfulDraftCasAuditsReturnedRevision() {
        UUID dashboardId = UUID.randomUUID();
        ValidatedDashboardDraft saveCandidate = validated(7, "保存候选");
        DashboardDraft saved = draft(dashboardId, 8, saveCandidate);
        when(validator.validate(eq("7"), any(byte[].class))).thenReturn(saveCandidate);
        when(repository.saveDraft(eq(projectId), eq(dashboardId), eq(7L), any(), any(),
                eq(accountId), any(Instant.class))).thenReturn(DashboardDraftSaveResult.saved(saved));

        assertThat(service.saveDraft(projectId, dashboardId, "7", new byte[]{1})).isEqualTo(saved);

        ArgumentCaptor<AuditLogEntry> audit = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(audits).record(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo("dashboard.draft_saved");
        assertThat(audit.getValue().details()).isEqualTo(java.util.Map.of("revision", "8"));
    }

    /** ADMIN改名成功后必须返回仓储事实，并记录不携带草稿详情的同事务审计。 */
    @Test
    @DisplayName("管理角色改名返回新目录并记录审计")
    void managementRoleRenameReturnsUpdatedCatalogAndAudits() {
        UUID dashboardId = UUID.randomUUID();
        DashboardCatalogEntry renamed = catalog(dashboardId, "生产总览");
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.ADMIN);
        when(repository.rename(eq(projectId), eq(dashboardId), eq("生产总览"),
                eq(accountId), any(Instant.class))).thenReturn(true);
        when(repository.find(projectId, dashboardId)).thenReturn(Optional.of(renamed));

        assertThat(service.rename(projectId, dashboardId, "生产总览")).isEqualTo(renamed);

        ArgumentCaptor<AuditLogEntry> audit = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(audits).record(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo("dashboard.renamed");
        assertThat(audit.getValue().details()).isEmpty();
    }

    /** 可见目录或草稿缺失均使用同一60034，避免读取入口暴露内部事实差异。 */
    @Test
    @DisplayName("目录和草稿读取不存在统一返回看板不可见")
    void missingCatalogAndDraftReadsUseSameNotFoundError() {
        UUID dashboardId = UUID.randomUUID();
        when(repository.find(projectId, dashboardId)).thenReturn(Optional.empty());
        when(repository.findDraft(projectId, dashboardId)).thenReturn(Optional.empty());

        assertBusinessError(() -> service.find(projectId, dashboardId), 60034);
        assertBusinessError(() -> service.getDraft(projectId, dashboardId), 60034);
    }

    /** 历史分页先区分不可见看板，普通版本详情缺失独立使用60047而不扩大回滚专用60042。 */
    @Test
    @DisplayName("历史读取区分看板不可见与普通版本不可见")
    void versionHistoryDistinguishesDashboardAndVersionVisibility() {
        UUID dashboardId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(repository.pageVersions(projectId, dashboardId, null, 50)).thenReturn(Optional.empty());
        when(repository.findVersionForManagement(projectId, dashboardId, versionId))
                .thenReturn(DashboardVersionLookupResult.dashboardNotFound(),
                        DashboardVersionLookupResult.versionNotFound());

        assertBusinessError(() -> service.listVersions(projectId, dashboardId, null, 50), 60034);
        assertBusinessError(() -> service.getVersion(projectId, dashboardId, versionId), 60034);
        assertBusinessError(() -> service.getVersion(projectId, dashboardId, versionId), 60047);

        verify(lifecycleAccessService, never()).requireActiveForWrite(any(), any());
        verify(audits, never()).record(any());
    }

    /** 构造外部模型统一安全失败的完整差异矩阵。 */
    private static Stream<Arguments> invalidDescriptors() {
        return Stream.of(
                Arguments.of("missing"), Arguments.of("wrong-version"), Arguments.of("wrong-project"),
                Arguments.of("wrong-algorithm"), Arguments.of("wrong-digest"), Arguments.of("wrong-profile"));
    }

    /** 只改变当前场景目标字段，保证每个反例真正执行对应精确比较分支。 */
    private ThingModelVersionDescriptor invalidDescriptor(String scenario) {
        return switch (scenario) {
            case "missing" -> null;
            case "wrong-version" -> new ThingModelVersionDescriptor(
                    UUID.randomUUID(), projectId,
                    "PG_JSONB_TEXT_V1_SHA256", MODEL_DIGEST, "TC_PROPERTY_COMPOSITE_V1");
            case "wrong-project" -> new ThingModelVersionDescriptor(
                    MODEL_VERSION_ID, UUID.randomUUID(),
                    "PG_JSONB_TEXT_V1_SHA256", MODEL_DIGEST, "TC_PROPERTY_COMPOSITE_V1");
            case "wrong-algorithm" -> new ThingModelVersionDescriptor(
                    MODEL_VERSION_ID, projectId, "OTHER", MODEL_DIGEST, "TC_PROPERTY_COMPOSITE_V1");
            case "wrong-digest" -> new ThingModelVersionDescriptor(
                    MODEL_VERSION_ID, projectId,
                    "PG_JSONB_TEXT_V1_SHA256", "b".repeat(64), "TC_PROPERTY_COMPOSITE_V1");
            case "wrong-profile" -> new ThingModelVersionDescriptor(
                    MODEL_VERSION_ID, projectId,
                    "PG_JSONB_TEXT_V1_SHA256", MODEL_DIGEST, "OTHER");
            default -> throw new IllegalArgumentException("未知测试场景");
        };
    }

    /** 构造仓储CAS稳定分类与公开错误映射矩阵。 */
    private static Stream<Arguments> rejectedSaveResults() {
        return Stream.of(
                Arguments.of("not-found", DashboardDraftSaveResult.notFound(), 60034),
                Arguments.of("conflict", DashboardDraftSaveResult.revisionConflict(), 60037),
                Arguments.of("exhausted", DashboardDraftSaveResult.revisionExhausted(), 60037));
    }

    /** 构造模型数组与外部摘要候选一致的规范草稿结果。 */
    private ValidatedDashboardDraft validated(long revision, String title) {
        ValidatedDashboardModelReference model = modelReference();
        ObjectNode content = JSON.createObjectNode()
                .put("schemaVersion", "tc.dashboard/v1")
                .put("title", title);
        ArrayNode models = content.putArray("models");
        models.addObject()
                .put("key", model.modelKey())
                .put("versionId", model.thingModelVersionId().toString())
                .put("digestAlgorithm", model.digestAlgorithm())
                .put("digest", model.digest())
                .put("profile", model.profile());
        return new ValidatedDashboardDraft(revision, content, List.of(model));
    }

    /** 构造固定完整模型核验需求。 */
    private ValidatedDashboardModelReference modelReference() {
        return new ValidatedDashboardModelReference(
                0, "pump_model", MODEL_VERSION_ID,
                "PG_JSONB_TEXT_V1_SHA256", MODEL_DIGEST, "TC_PROPERTY_COMPOSITE_V1");
    }

    /** 构造匹配当前项目的device公开模型描述。 */
    private ThingModelVersionDescriptor descriptor() {
        return new ThingModelVersionDescriptor(
                MODEL_VERSION_ID, projectId,
                "PG_JSONB_TEXT_V1_SHA256", MODEL_DIGEST, "TC_PROPERTY_COMPOSITE_V1");
    }

    /** 构造未发布看板目录返回事实。 */
    private DashboardCatalogEntry catalog(UUID dashboardId, String name) {
        Instant at = Instant.parse("2026-09-06T12:00:00Z");
        return new DashboardCatalogEntry(
                dashboardId, ownerTenantId, projectId, name, 0, null,
                accountId, accountId, at, at, null);
    }

    /** 构造给定revision且与当前规范模型候选一致的草稿事实。 */
    private DashboardDraft draft(
            UUID dashboardId, long revision, ValidatedDashboardDraft source) {
        Instant at = Instant.parse("2026-09-06T12:00:00Z");
        return new DashboardDraft(
                dashboardId, ownerTenantId, projectId, source.content(), revision,
                accountId, at, at, source.persistenceReferences());
    }

    /** 构造不含模型关系的不可变版本，供管理历史读取验证。 */
    private DashboardVersion version(UUID dashboardId, UUID versionId, long number, long sourceRevision) {
        Instant at = Instant.parse("2026-09-06T12:00:00Z");
        ObjectNode schema = JSON.createObjectNode()
                .put("schemaVersion", DashboardDraft.SCHEMA_VERSION);
        schema.putArray("models");
        return new DashboardVersion(
                versionId, ownerTenantId, projectId, dashboardId, number, sourceRevision,
                schema, DashboardDraft.SCHEMA_VERSION, DashboardVersion.SCHEMA_DIGEST_ALGORITHM,
                "b".repeat(64), JSON.createArrayNode(), JSON.createArrayNode(), accountId, at, List.of());
    }

    /** 从完整版本构造列表使用的固定大小摘要投影。 */
    private static DashboardVersionSummary summary(DashboardVersion version) {
        return new DashboardVersionSummary(
                version.id(), version.tenantId(), version.projectId(), version.dashboardId(),
                version.versionNumber(), version.sourceDraftRevision(), version.schemaVersion(),
                version.schemaDigestAlgorithm(), version.schemaDigest(), version.publishedAt());
    }

    /** 断言公开业务错误码，避免依赖可变中文堆栈文本。 */
    private void assertBusinessError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(
                BusinessException.class,
                failure -> assertThat(failure.errorCode().code()).isEqualTo(code));
    }
}
