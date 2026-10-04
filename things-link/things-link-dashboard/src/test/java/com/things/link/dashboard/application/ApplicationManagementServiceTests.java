package com.things.link.dashboard.application;

import com.things.link.dashboard.application.draft.ApplicationDraftContractValidator;
import com.things.link.dashboard.application.draft.ApplicationDraftContractViolation;
import com.things.link.dashboard.application.draft.ValidatedApplicationDraft;
import com.things.link.dashboard.domain.ApplicationCatalogEntry;
import com.things.link.dashboard.domain.ApplicationCreationResult;
import com.things.link.dashboard.domain.ApplicationDraft;
import com.things.link.dashboard.domain.ApplicationDraftSaveResult;
import com.things.link.dashboard.domain.ApplicationErrorCode;
import com.things.link.dashboard.domain.ApplicationKeyCollisionException;
import com.things.link.dashboard.domain.ApplicationRepository;
import com.things.link.dashboard.domain.ApplicationVersion;
import com.things.link.dashboard.domain.ApplicationVersionLookupResult;
import com.things.link.dashboard.domain.ApplicationVersionSummary;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 应用管理服务的角色、项目持续许可、随机碰撞与草稿CAS稳定分类单测。 */
class ApplicationManagementServiceTests {

    /** 单测使用的最小合法草稿树。 */
    private static final JsonNode CONTENT = JsonMapper.builder().build().createObjectNode()
            .put("formatVersion", "tc.application/v1");

    /** 应用仓储模拟。 */
    private ApplicationRepository repository;

    /** 项目角色与归属端口模拟。 */
    private ProjectService projectService;

    /** 项目持续写许可端口模拟。 */
    private ProjectLifecycleAccessService lifecycleAccessService;

    /** 草稿原文校验端口模拟。 */
    private ApplicationDraftContractValidator validator;

    /** 测试控制的appKey生成端口。 */
    private ApplicationKeyGenerator keyGenerator;

    /** 同事务审计端口模拟。 */
    private AuditLogService auditLogService;

    /** 被测应用管理服务。 */
    private ApplicationManagementService service;

    /** 当前项目ID。 */
    private UUID projectId;

    /** 当前项目owner tenant。 */
    private UUID ownerTenantId;

    /** 当前Console账号。 */
    private UUID accountId;

    /** 每个用例建立确定项目身份与默认OWNER授权。 */
    @BeforeEach
    void setUp() {
        repository = mock(ApplicationRepository.class);
        projectService = mock(ProjectService.class);
        lifecycleAccessService = mock(ProjectLifecycleAccessService.class);
        validator = mock(ApplicationDraftContractValidator.class);
        keyGenerator = mock(ApplicationKeyGenerator.class);
        auditLogService = mock(AuditLogService.class);
        service = new ApplicationManagementService(
                repository, projectService, lifecycleAccessService, validator, keyGenerator, auditLogService);
        projectId = UUID.randomUUID();
        ownerTenantId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        TenantContext.set(new TenantScope(UUID.randomUUID(), projectId, accountId));
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.OWNER);
        when(projectService.requireProjectTenant(projectId)).thenReturn(ownerTenantId);
        when(validator.validate(anyString(), any(byte[].class)))
                .thenAnswer(invocation -> new ValidatedApplicationDraft(
                        Long.parseLong(invocation.getArgument(0)), CONTENT));
        when(keyGenerator.generate()).thenReturn("app_00000000000000000000000000000001");
    }

    /** 应用管理与发布错误必须保持登记表冻结的码位和HTTP语义。 */
    @Test
    void exposesStableApplicationErrorCodes() {
        assertThat(ApplicationErrorCode.values())
                .extracting(ApplicationErrorCode::code)
                .containsExactly(60030, 60031, 60032, 60033, 60043, 60044, 60045, 60046, 60048);
        assertThat(ApplicationErrorCode.values())
                .extracting(ApplicationErrorCode::httpStatus)
                .containsExactly(404, 403, 400, 409, 400, 409, 503, 404, 404);
    }

    /** ThreadLocal身份必须清除，避免用例间继承Console账号。 */
    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    /** 四种项目角色均可列出管理目录，读取不要求ACTIVE写许可。 */
    @ParameterizedTest
    @EnumSource(ProjectRole.class)
    void allowsEveryProjectRoleToListApplications(ProjectRole role) {
        when(projectService.requireRoleInProject(projectId)).thenReturn(role);
        when(repository.page(projectId, "cursor", 20)).thenReturn(CursorPage.last(List.of()));

        assertThat(service.list(projectId, "cursor", 20).items()).isEmpty();

        verify(repository).page(projectId, "cursor", 20);
        verify(lifecycleAccessService, never()).requireActiveForWrite(any(), any());
    }

    /** 应用读取在授权之后把不存在、跨项目和软删除统一压缩为60030。 */
    @Test
    void mapsInvisibleApplicationToStableNotFound() {
        UUID applicationId = UUID.randomUUID();
        when(repository.find(projectId, applicationId)).thenReturn(Optional.empty());

        assertBusinessError(() -> service.find(projectId, applicationId),
                ApplicationErrorCode.APPLICATION_NOT_FOUND);
    }

    /** 草稿读取同样先确认项目成员，再使用应用不可见的统一分类。 */
    @Test
    void mapsInvisibleDraftToStableNotFound() {
        UUID applicationId = UUID.randomUUID();
        when(repository.findDraft(projectId, applicationId)).thenReturn(Optional.empty());

        assertBusinessError(() -> service.getDraft(projectId, applicationId),
                ApplicationErrorCode.APPLICATION_NOT_FOUND);
    }

    /** 四种项目角色均可读取轻量历史与完整版本，且读取不要求ACTIVE写许可。 */
    @ParameterizedTest
    @EnumSource(ProjectRole.class)
    void allowsEveryProjectRoleToReadApplicationVersionHistory(ProjectRole role) {
        UUID applicationId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant publishedAt = Instant.parse("2026-09-06T12:00:00Z");
        String digest = "a".repeat(64);
        ApplicationVersionSummary summary = new ApplicationVersionSummary(
                versionId, ownerTenantId, projectId, applicationId, 2, 7,
                ApplicationVersion.SNAPSHOT_DIGEST_ALGORITHM, digest, publishedAt);
        ApplicationVersion version = new ApplicationVersion(
                versionId, ownerTenantId, projectId, applicationId, 2, 7, CONTENT,
                ApplicationVersion.SNAPSHOT_DIGEST_ALGORITHM, digest, accountId, publishedAt);
        when(projectService.requireRoleInProject(projectId)).thenReturn(role);
        when(repository.pageVersions(projectId, applicationId, "cursor", 20))
                .thenReturn(Optional.of(CursorPage.last(List.of(summary))));
        when(repository.findVersionForManagement(projectId, applicationId, versionId))
                .thenReturn(ApplicationVersionLookupResult.found(version));

        assertThat(service.listVersions(projectId, applicationId, "cursor", 20).items())
                .containsExactly(summary);
        assertThat(service.getVersion(projectId, applicationId, versionId)).isEqualTo(version);

        verify(lifecycleAccessService, never()).requireActiveForWrite(any(), any());
        verify(auditLogService, never()).record(any());
    }

    /** 历史分页先区分不可见应用，普通版本详情缺失独立使用60048而不扩大回滚专用60046。 */
    @Test
    void versionHistoryDistinguishesApplicationAndVersionVisibility() {
        UUID applicationId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(repository.pageVersions(projectId, applicationId, null, 50)).thenReturn(Optional.empty());
        when(repository.findVersionForManagement(projectId, applicationId, versionId))
                .thenReturn(ApplicationVersionLookupResult.applicationNotFound(),
                        ApplicationVersionLookupResult.versionNotFound());

        assertBusinessError(() -> service.listVersions(projectId, applicationId, null, 50),
                ApplicationErrorCode.APPLICATION_NOT_FOUND);
        assertBusinessError(() -> service.getVersion(projectId, applicationId, versionId),
                ApplicationErrorCode.APPLICATION_NOT_FOUND);
        assertBusinessError(() -> service.getVersion(projectId, applicationId, versionId),
                ApplicationErrorCode.APPLICATION_VERSION_NOT_FOUND);

        verify(lifecycleAccessService, never()).requireActiveForWrite(any(), any());
        verify(auditLogService, never()).record(any());
    }

    /** 创建先持ACTIVE项目许可并锁后复核角色，随后才写入使用owner tenant的聚合。 */
    @Test
    void createsApplicationAfterLockedRoleRecheckUsingOwnerTenant() {
        service.create(projectId, "工厂总览", new byte[]{1});

        InOrder order = inOrder(projectService, lifecycleAccessService, repository, auditLogService);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(projectService).requireProjectTenant(projectId);
        order.verify(lifecycleAccessService).requireActiveForWrite(ownerTenantId, projectId);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(repository).create(any(), any());
        order.verify(auditLogService).record(any());
        ArgumentCaptor<ApplicationCatalogEntry> application = ArgumentCaptor.forClass(ApplicationCatalogEntry.class);
        ArgumentCaptor<ApplicationDraft> draft = ArgumentCaptor.forClass(ApplicationDraft.class);
        verify(repository).create(application.capture(), draft.capture());
        assertThat(application.getValue().tenantId()).isEqualTo(ownerTenantId);
        assertThat(application.getValue().createdBy()).isEqualTo(accountId);
        assertThat(draft.getValue().applicationId()).isEqualTo(application.getValue().id());
        assertThat(draft.getValue().revision()).isZero();
        verify(validator).validate(eq("0"), any(byte[].class));
        ArgumentCaptor<AuditLogEntry> audit = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogService).record(audit.capture());
        assertThat(audit.getValue().tenantId()).isEqualTo(ownerTenantId);
        assertThat(audit.getValue().actorAccountId()).isEqualTo(accountId);
        assertThat(audit.getValue().targetId()).isEqualTo(application.getValue().id());
        assertThat(audit.getValue().targetType()).isEqualTo("application");
        assertThat(audit.getValue().action()).isEqualTo("application.created");
        assertThat(audit.getValue().details()).isEqualTo(java.util.Map.of("draftRevision", "0"));
    }

    /** 同身份同键同请求恢复首次身份且不重复审计，异请求与已软删结果保持公共稳定错误。 */
    @Test
    void idempotentCreationRestoresOnlyTheOriginalLiveResult() {
        byte[] source = {1, 2, 3};
        ApplicationCatalogEntry created = service.createIdempotent(
                projectId, "create-key", "幂等应用", source);
        ArgumentCaptor<ApplicationCreationResult> mapping =
                ArgumentCaptor.forClass(ApplicationCreationResult.class);
        verify(repository).createIdempotent(any(), any(), mapping.capture());
        ApplicationCreationResult persisted = mapping.getValue();
        when(repository.findCreationResult(
                ownerTenantId, projectId, accountId, persisted.idempotencyKeyDigest()))
                .thenReturn(Optional.of(persisted));
        when(repository.findCreationApplication(projectId, created.id())).thenReturn(Optional.of(created));

        ApplicationCatalogEntry replayed = service.createIdempotent(
                projectId, "create-key", "幂等应用", source);
        assertThat(replayed).isEqualTo(created);
        assertBusinessError(() -> service.createIdempotent(
                        projectId, "create-key", "另一应用", source),
                CommonErrorCode.RESOURCE_STATE_CONFLICT);

        ApplicationCatalogEntry deleted = new ApplicationCatalogEntry(
                created.id(), created.tenantId(), created.projectId(), created.appKey(), created.managementName(),
                created.publicationRevision(), created.currentVersionId(), created.createdBy(), created.updatedBy(),
                created.createdAt(), created.updatedAt(), created.createdAt().plusSeconds(1));
        when(repository.findCreationApplication(projectId, created.id())).thenReturn(Optional.of(deleted));
        assertBusinessError(() -> service.createIdempotent(
                        projectId, "create-key", "幂等应用", source),
                CommonErrorCode.IDEMPOTENCY_RESULT_NOT_REPLAYABLE);

        assertThat(persisted.tenantId()).isEqualTo(ownerTenantId);
        assertThat(persisted.projectId()).isEqualTo(projectId);
        assertThat(persisted.accountId()).isEqualTo(accountId);
        assertThat(persisted.applicationId()).isEqualTo(created.id());
        // 固定域标签、UTF-8与四字节大端长度帧，防止后续重构让已保存映射永久无法重放。
        assertThat(persisted.idempotencyKeyDigest())
                .isEqualTo("1bbbd71116d258dabc2b68e63dadd3ae4a72022fe70fbbbf76b44060da7c6424");
        assertThat(persisted.requestDigest())
                .isEqualTo("ef11092bbd594ffa48ca97366f0ad42d7f7ecfd21c48a7a6da3bb97b9decfb26");
        verify(repository, times(4)).lockCreationRequest(
                ownerTenantId, projectId, accountId, persisted.idempotencyKeyDigest());
        verify(repository).createIdempotent(any(), any(), any());
        verify(keyGenerator).generate();
        verify(validator).validate(eq("0"), any(byte[].class));
        verify(auditLogService).record(any());
    }

    /** 创建幂等键缺失不能静默回落到旧非幂等入口，也不能占用数据库串行锁。 */
    @Test
    void rejectsMissingCreationIdempotencyKeyBeforeRepositoryLock() {
        assertBusinessError(() -> service.createIdempotent(
                        projectId, " ", "幂等应用", new byte[]{1}),
                CommonErrorCode.INVALID_PARAMETER);

        verify(repository, never()).lockCreationRequest(any(), any(), any(), anyString());
        verify(repository, never()).createIdempotent(any(), any(), any());
        verify(auditLogService, never()).record(any());
    }

    /** OPERATOR是有效成员但没有应用管理权，必须在取得项目锁和访问仓储之前返回60031。 */
    @Test
    void rejectsOperatorWriteBeforeLifecycleAndRepository() {
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.OPERATOR);

        assertBusinessError(() -> service.create(projectId, "总览", new byte[]{1}),
                ApplicationErrorCode.APPLICATION_MANAGEMENT_FORBIDDEN);

        verify(lifecycleAccessService, never()).requireActiveForWrite(any(), any());
        verify(repository, never()).create(any(), any());
        verify(auditLogService, never()).record(any());
    }

    /** 锁等待期间从ADMIN降为VIEWER时，第二次角色查询必须阻止应用写入。 */
    @Test
    void rejectsRoleLostWhileWaitingForProjectPermit() {
        when(projectService.requireRoleInProject(projectId))
                .thenReturn(ProjectRole.ADMIN, ProjectRole.VIEWER);

        assertBusinessError(() -> service.create(projectId, "总览", new byte[]{1}),
                ApplicationErrorCode.APPLICATION_MANAGEMENT_FORBIDDEN);

        verify(lifecycleAccessService).requireActiveForWrite(ownerTenantId, projectId);
        verify(repository, never()).create(any(), any());
        verify(auditLogService, never()).record(any());
    }

    /** 精确appKey碰撞只更换key，应用ID、草稿、操作者与审计时刻保持同一逻辑候选。 */
    @Test
    void retriesOnlyApplicationKeyAfterExactCollision() {
        when(keyGenerator.generate()).thenReturn(
                "app_00000000000000000000000000000001",
                "app_00000000000000000000000000000002",
                "app_00000000000000000000000000000003");
        doThrow(new ApplicationKeyCollisionException(), new ApplicationKeyCollisionException())
                .doNothing().when(repository).create(any(), any());

        ApplicationCatalogEntry created = service.create(projectId, "总览", new byte[]{1});

        ArgumentCaptor<ApplicationCatalogEntry> applications = ArgumentCaptor.forClass(ApplicationCatalogEntry.class);
        ArgumentCaptor<ApplicationDraft> drafts = ArgumentCaptor.forClass(ApplicationDraft.class);
        verify(repository, times(3)).create(applications.capture(), drafts.capture());
        assertThat(applications.getAllValues()).extracting(ApplicationCatalogEntry::appKey)
                .containsExactly(
                        "app_00000000000000000000000000000001",
                        "app_00000000000000000000000000000002",
                        "app_00000000000000000000000000000003");
        assertThat(applications.getAllValues()).extracting(ApplicationCatalogEntry::id).containsOnly(created.id());
        assertThat(applications.getAllValues()).extracting(ApplicationCatalogEntry::createdAt)
                .containsOnly(created.createdAt());
        assertThat(drafts.getAllValues()).allSatisfy(draft -> {
            assertThat(draft.applicationId()).isEqualTo(created.id());
            assertThat(draft.createdAt()).isEqualTo(created.createdAt());
        });
    }

    /** 第五个精确碰撞耗尽预算后抛内部异常，交由全局边界收敛90000。 */
    @Test
    void mapsFiveApplicationKeyCollisionsToInternalError() {
        when(keyGenerator.generate()).thenReturn(
                "app_00000000000000000000000000000001",
                "app_00000000000000000000000000000002",
                "app_00000000000000000000000000000003",
                "app_00000000000000000000000000000004",
                "app_00000000000000000000000000000005");
        doThrow(new ApplicationKeyCollisionException()).when(repository).create(any(), any());

        assertThatThrownBy(() -> service.create(projectId, "总览", new byte[]{1}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("应用公开定位符连续五次发生碰撞")
                .hasCauseInstanceOf(ApplicationKeyCollisionException.class);

        verify(repository, times(5)).create(any(), any());
        verify(auditLogService, never()).record(any());
    }

    /** 非appKey数据库故障不得被重试或改写为随机碰撞。 */
    @Test
    void propagatesUnrelatedRepositoryFailureWithoutRetry() {
        IllegalStateException failure = new IllegalStateException("database unavailable");
        doThrow(failure).when(repository).create(any(), any());

        assertThatThrownBy(() -> service.create(projectId, "总览", new byte[]{1})).isSameAs(failure);

        verify(repository).create(any(), any());
        verify(keyGenerator).generate();
        verify(auditLogService, never()).record(any());
    }

    /** 草稿解析拒绝映射60032，并只携带稳定reason与脱敏路径。 */
    @Test
    void mapsDraftContractViolationToStableInvalidError() {
        ApplicationDraftContractViolation violation = new ApplicationDraftContractViolation(
                ApplicationDraftContractViolation.Reason.UNKNOWN_FIELD, "$.dashboardRefs[0]", "未知字段");
        when(validator.validate(eq("0"), any(byte[].class))).thenThrow(violation);

        assertThatThrownBy(() -> service.create(projectId, "总览", new byte[]{1}))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.errorCode()).isEqualTo(ApplicationErrorCode.APPLICATION_DRAFT_INVALID);
                    assertThat(business.details()).containsExactly("UNKNOWN_FIELD@$.dashboardRefs[0]");
                });
        verify(repository, never()).create(any(), any());
        verify(auditLogService, never()).record(any());
    }

    /** ADMIN可改名；写许可和锁后复核完成后才接触应用目录。 */
    @Test
    void renamesApplicationForAdministrator() {
        UUID applicationId = UUID.randomUUID();
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.ADMIN);
        when(repository.rename(eq(projectId), eq(applicationId), eq("新名称"), eq(accountId), any(Instant.class)))
                .thenReturn(true);
        ApplicationCatalogEntry renamed = application(applicationId, "新名称");
        when(repository.find(projectId, applicationId)).thenReturn(Optional.of(renamed));

        assertThat(service.rename(projectId, applicationId, "新名称")).isEqualTo(renamed);

        verify(projectService, times(2)).requireRoleInProject(projectId);
        verify(lifecycleAccessService).requireActiveForWrite(ownerTenantId, projectId);
        ArgumentCaptor<AuditLogEntry> audit = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogService).record(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo("application.renamed");
        assertThat(audit.getValue().details()).isEmpty();
    }

    /** 目录改名未命中时统一映射60030。 */
    @Test
    void mapsMissingRenameTargetToStableNotFound() {
        UUID applicationId = UUID.randomUUID();
        when(repository.rename(eq(projectId), eq(applicationId), anyString(), eq(accountId), any(Instant.class)))
                .thenReturn(false);

        assertBusinessError(() -> service.rename(projectId, applicationId, "新名称"),
                ApplicationErrorCode.APPLICATION_NOT_FOUND);
        verify(auditLogService, never()).record(any());
    }

    /** CAS成功直接返回同一持久语句生成的新revision事实。 */
    @Test
    void returnsAtomicSavedDraftResult() {
        UUID applicationId = UUID.randomUUID();
        ApplicationDraft saved = draft(applicationId, 8);
        when(repository.saveDraft(eq(projectId), eq(applicationId), eq(7L), any(), eq(accountId), any(Instant.class)))
                .thenReturn(ApplicationDraftSaveResult.saved(saved));

        assertThat(service.saveDraft(projectId, applicationId, "7", new byte[]{1})).isEqualTo(saved);
        ArgumentCaptor<AuditLogEntry> audit = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogService).record(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo("application.draft_saved");
        assertThat(audit.getValue().details()).isEqualTo(java.util.Map.of("revision", "8"));
    }

    /** CAS原子NOT_FOUND结果映射60030，不执行事后查询猜测。 */
    @Test
    void mapsAtomicDraftNotFoundWithoutFollowUpQuery() {
        UUID applicationId = UUID.randomUUID();
        when(repository.saveDraft(eq(projectId), eq(applicationId), eq(7L), any(), eq(accountId), any(Instant.class)))
                .thenReturn(ApplicationDraftSaveResult.notFound());

        assertBusinessError(() -> service.saveDraft(projectId, applicationId, "7", new byte[]{1}),
                ApplicationErrorCode.APPLICATION_NOT_FOUND);

        verify(repository, never()).findDraft(any(), any());
        verify(auditLogService, never()).record(any());
    }

    /** 陈旧revision与持久revision耗尽都稳定映射60033。 */
    @Test
    void mapsBothAtomicRevisionFailuresToConflict() {
        UUID applicationId = UUID.randomUUID();
        List<ApplicationDraftSaveResult> failures = List.of(
                ApplicationDraftSaveResult.revisionConflict(),
                ApplicationDraftSaveResult.revisionExhausted());
        for (ApplicationDraftSaveResult failure : failures) {
            when(repository.saveDraft(eq(projectId), eq(applicationId), eq(7L), any(), eq(accountId), any(Instant.class)))
                    .thenReturn(failure);
            assertBusinessError(() -> service.saveDraft(projectId, applicationId, "7", new byte[]{1}),
                    ApplicationErrorCode.APPLICATION_DRAFT_CONFLICT);
        }
        verify(auditLogService, never()).record(any());
    }

    /** Long最大revision仍进入仓储，由同一持久结果把活目标耗尽分类为60033。 */
    @Test
    void mapsMaximumRevisionExhaustionFromAtomicResultToConflict() {
        UUID applicationId = UUID.randomUUID();
        when(repository.saveDraft(
                eq(projectId), eq(applicationId), eq(Long.MAX_VALUE), any(), eq(accountId), any(Instant.class)))
                .thenReturn(ApplicationDraftSaveResult.revisionExhausted());

        assertBusinessError(() -> service.saveDraft(
                        projectId, applicationId, Long.toString(Long.MAX_VALUE), new byte[]{1}),
                ApplicationErrorCode.APPLICATION_DRAFT_CONFLICT);

        verify(validator).validate(eq(Long.toString(Long.MAX_VALUE)), any(byte[].class));
        verify(repository).saveDraft(
                eq(projectId), eq(applicationId), eq(Long.MAX_VALUE), any(), eq(accountId), any(Instant.class));
        verify(auditLogService, never()).record(any());
    }

    /** Long最大revision的目标若不存在仍返回60030，不能被应用层预判耗尽遮蔽。 */
    @Test
    void preservesNotFoundForMaximumRevisionTarget() {
        UUID applicationId = UUID.randomUUID();
        when(repository.saveDraft(
                eq(projectId), eq(applicationId), eq(Long.MAX_VALUE), any(), eq(accountId), any(Instant.class)))
                .thenReturn(ApplicationDraftSaveResult.notFound());

        assertBusinessError(() -> service.saveDraft(
                        projectId, applicationId, Long.toString(Long.MAX_VALUE), new byte[]{1}),
                ApplicationErrorCode.APPLICATION_NOT_FOUND);

        verify(auditLogService, never()).record(any());
    }

    /** 管理名称按Title合同原样保存，Unicode空格和C1控制字符使用普通参数错误拒绝。 */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"\u00a0", "名称\u0085换行"})
    void rejectsWhitespaceOnlyOrControlManagementName(String managementName) {
        assertBusinessError(() -> service.create(projectId, managementName, new byte[]{1}),
                CommonErrorCode.INVALID_PARAMETER);
        verify(repository, never()).create(any(), any());
        verify(auditLogService, never()).record(any());
    }

    /** 构造同项目未删除应用目录夹具。 */
    private ApplicationCatalogEntry application(UUID applicationId, String managementName) {
        Instant now = Instant.now();
        return new ApplicationCatalogEntry(
                applicationId, ownerTenantId, projectId, "app_00000000000000000000000000000001",
                managementName, 0, null, accountId, accountId, now, now, null);
    }

    /** 构造指定revision的同项目草稿夹具。 */
    private ApplicationDraft draft(UUID applicationId, long revision) {
        Instant now = Instant.now();
        return new ApplicationDraft(
                applicationId, ownerTenantId, projectId, CONTENT, revision, accountId, now, now);
    }

    /** 断言业务异常携带精确ErrorCode对象，避免仅比较消息掩盖码位错误。 */
    private static void assertBusinessError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
                                            com.things.link.shared.error.ErrorCode errorCode) {
        assertThatThrownBy(action)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode())
                .isEqualTo(errorCode);
    }
}
