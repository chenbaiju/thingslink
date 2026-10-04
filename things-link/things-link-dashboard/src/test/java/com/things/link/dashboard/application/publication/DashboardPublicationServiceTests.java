package com.things.link.dashboard.application.publication;

import com.things.link.dashboard.application.schema.DashboardSchemaValidationException;
import com.things.link.dashboard.domain.DashboardDraft;
import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.dashboard.domain.DashboardPublicationAppendResult;
import com.things.link.dashboard.domain.DashboardPublicationRollbackResult;
import com.things.link.dashboard.domain.DashboardPublicationState;
import com.things.link.dashboard.domain.DashboardPublicationWithdrawalResult;
import com.things.link.dashboard.domain.DashboardRepository;
import com.things.link.dashboard.domain.DashboardSoftDeleteResult;
import com.things.link.dashboard.domain.DashboardVersion;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 看板发布、回滚与撤回的revision、锁内事实、受控结果与审计编排单测。 */
@DisplayName("看板发布状态业务编排")
class DashboardPublicationServiceTests {

    /** PostgreSQL规范JSON文本摘要算法固定值。 */
    private static final String DIGEST_ALGORITHM = "PG_JSONB_TEXT_V1_SHA256";
    /** 合法小写SHA-256摘要。 */
    private static final String SCHEMA_DIGEST = "a".repeat(64);
    /** 测试JSON构造器。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 看板聚合持久端口模拟。 */
    private DashboardRepository repository;
    /** 项目角色与归属端口模拟。 */
    private ProjectService projectService;
    /** 项目持续ACTIVE写许可端口模拟。 */
    private ProjectLifecycleAccessService lifecycleAccessService;
    /** 锁内候选重建工厂模拟。 */
    private DashboardPublicationCandidateFactory candidateFactory;
    /** 外部资格编排模拟。 */
    private DashboardPublicationQualificationService qualificationService;
    /** 同事务审计端口模拟。 */
    private AuditLogService audits;
    /** 被测发布服务。 */
    private DashboardPublicationService service;
    /** 当前项目ID。 */
    private UUID projectId;
    /** 当前看板ID。 */
    private UUID dashboardId;
    /** 项目真实owner租户ID。 */
    private UUID ownerTenantId;
    /** 当前Console账号ID。 */
    private UUID accountId;
    /** 锁内读取的草稿事实。 */
    private DashboardDraft lockedDraft;
    /** 由锁内草稿重建的候选。 */
    private DashboardPublicationCandidate candidate;

    /** 每例建立OWNER、ACTIVE许可上下文和revision为7的持久草稿。 */
    @BeforeEach
    void setUp() {
        repository = mock(DashboardRepository.class);
        projectService = mock(ProjectService.class);
        lifecycleAccessService = mock(ProjectLifecycleAccessService.class);
        candidateFactory = mock(DashboardPublicationCandidateFactory.class);
        qualificationService = mock(DashboardPublicationQualificationService.class);
        audits = mock(AuditLogService.class);
        service = new DashboardPublicationService(
                repository, projectService, lifecycleAccessService,
                candidateFactory, qualificationService, audits);
        projectId = UUID.randomUUID();
        dashboardId = UUID.randomUUID();
        ownerTenantId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        TenantContext.set(new TenantScope(UUID.randomUUID(), projectId, accountId));
        lockedDraft = draft(7);
        candidate = candidate(lockedDraft);
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.OWNER);
        when(projectService.requireProjectTenant(projectId)).thenReturn(ownerTenantId);
    }

    /** 清除线程身份，避免用例之间串扰。 */
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** 成功发布必须在许可与行锁内重建候选，再追加版本并记录最小审计字段。 */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ProjectRole.class, names = {"OWNER", "ADMIN"})
    @DisplayName("成功发布按固定顺序重建锁内草稿并记录最小审计")
    void managementRolesPublishByRebuildingLockedDraftAndWritingMinimalAuditInOrder(ProjectRole role) {
        when(projectService.requireRoleInProject(projectId)).thenReturn(role);
        stubQualifiedCandidate();
        when(repository.appendPublication(any(DashboardVersion.class), eq(11L)))
                .thenReturn(new DashboardPublicationAppendResult(
                        DashboardPublicationAppendResult.Status.PUBLISHED, 4L, 12L));

        DashboardVersion published = service.publish(projectId, dashboardId, "7", "11");

        ArgumentCaptor<DashboardVersion> versionCaptor = ArgumentCaptor.forClass(DashboardVersion.class);
        ArgumentCaptor<AuditLogEntry> auditCaptor = ArgumentCaptor.forClass(AuditLogEntry.class);
        InOrder order = inOrder(
                projectService, lifecycleAccessService, repository,
                candidateFactory, qualificationService, audits);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(projectService).requireProjectTenant(projectId);
        order.verify(lifecycleAccessService).requireActiveForWrite(ownerTenantId, projectId);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(repository).lockPublicationState(projectId, dashboardId);
        order.verify(repository).findDraft(projectId, dashboardId);
        order.verify(candidateFactory).prepare(lockedDraft);
        order.verify(qualificationService).qualify(candidate);
        order.verify(repository).appendPublication(versionCaptor.capture(), eq(11L));
        order.verify(audits).record(auditCaptor.capture());

        DashboardVersion appended = versionCaptor.getValue();
        assertThat(published).isEqualTo(appended);
        assertThat(appended.tenantId()).isEqualTo(ownerTenantId);
        assertThat(appended.projectId()).isEqualTo(projectId);
        assertThat(appended.dashboardId()).isEqualTo(dashboardId);
        assertThat(appended.versionNumber()).isEqualTo(4);
        assertThat(appended.sourceDraftRevision()).isEqualTo(7);
        assertThat(appended.schema()).isEqualTo(lockedDraft.content());
        assertThat(appended.schemaDigestAlgorithm()).isEqualTo(DIGEST_ALGORITHM);
        assertThat(appended.schemaDigest()).isEqualTo(SCHEMA_DIGEST);
        assertThat(appended.requiredComponents()).isEmpty();
        assertThat(appended.requiredResources()).isEmpty();
        assertThat(appended.publishedByAccountId()).isEqualTo(accountId);

        AuditLogEntry audit = auditCaptor.getValue();
        assertThat(audit.tenantId()).isEqualTo(ownerTenantId);
        assertThat(audit.projectId()).isEqualTo(projectId);
        assertThat(audit.actorAccountId()).isEqualTo(accountId);
        assertThat(audit.targetType()).isEqualTo("dashboard");
        assertThat(audit.targetId()).isEqualTo(dashboardId);
        assertThat(audit.action()).isEqualTo("dashboard.published");
        assertThat(audit.details()).isEqualTo(Map.of(
                "versionId", appended.id().toString(),
                "versionNumber", "4",
                "sourceDraftRevision", "7",
                "publicationRevision", "12"));
    }

    /** OPERATOR与VIEWER必须在项目锁、看板读取及资格端口之前稳定拒绝。 */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ProjectRole.class, names = {"OPERATOR", "VIEWER"})
    @DisplayName("只读角色不能发布看板")
    void readOnlyRolesCannotPublish(ProjectRole role) {
        when(projectService.requireRoleInProject(projectId)).thenReturn(role);

        assertBusinessError(() -> service.publish(projectId, dashboardId, "7", "11"),
                DashboardErrorCode.DASHBOARD_MANAGEMENT_FORBIDDEN);

        verify(lifecycleAccessService, never()).requireActiveForWrite(any(), any());
        verify(repository, never()).lockPublicationState(any(), any());
        verify(qualificationService, never()).qualify(any());
        verify(audits, never()).record(any());
    }

    /** 项目ACTIVE锁等待期间角色被移除时，锁后复核必须阻止看板行锁和发布资格。 */
    @Test
    @DisplayName("项目锁后角色复核失败停止发布")
    void roleRemovalWhileWaitingForProjectLockStopsPublication() {
        when(projectService.requireRoleInProject(projectId))
                .thenReturn(ProjectRole.OWNER, ProjectRole.VIEWER);

        assertBusinessError(() -> service.publish(projectId, dashboardId, "7", "11"),
                DashboardErrorCode.DASHBOARD_MANAGEMENT_FORBIDDEN);

        verify(lifecycleAccessService).requireActiveForWrite(ownerTenantId, projectId);
        verify(repository, never()).lockPublicationState(any(), any());
        verify(qualificationService, never()).qualify(any());
        verify(audits, never()).record(any());
    }

    /** 任一调用方revision过期都必须在读取草稿和准备候选前停止。 */
    @ParameterizedTest(name = "draft={0}, publication={1}")
    @CsvSource({"6, 11", "7, 10"})
    @DisplayName("任一revision过期均在锁内短路")
    void staleEitherRevisionStopsBeforeDraftAndCandidateWork(
            String expectedDraftRevision, String expectedPublicationRevision) {
        when(repository.lockPublicationState(projectId, dashboardId))
                .thenReturn(Optional.of(state(7, 11, 3)));

        assertBusinessError(() -> service.publish(
                projectId, dashboardId, expectedDraftRevision, expectedPublicationRevision),
                DashboardErrorCode.DASHBOARD_PUBLICATION_CONFLICT);

        verify(repository, never()).findDraft(any(), any());
        verify(candidateFactory, never()).prepare(any());
        verify(qualificationService, never()).qualify(any());
        verify(repository, never()).appendPublication(any(), any(Long.class));
        verify(audits, never()).record(any());
    }

    /** 行锁状态与随后重取草稿不一致时不得使用预锁候选或进入资格核验。 */
    @Test
    @DisplayName("锁内草稿revision漂移停止候选重建")
    void lockedStateAndDraftMismatchStopsBeforeRebuildingCandidate() {
        when(repository.lockPublicationState(projectId, dashboardId))
                .thenReturn(Optional.of(state(7, 11, 3)));
        when(repository.findDraft(projectId, dashboardId)).thenReturn(Optional.of(draft(8)));

        assertBusinessError(() -> service.publish(projectId, dashboardId, "7", "11"),
                DashboardErrorCode.DASHBOARD_PUBLICATION_CONFLICT);

        verify(candidateFactory, never()).prepare(any());
        verify(qualificationService, never()).qualify(any());
        verify(repository, never()).appendPublication(any(), any(Long.class));
        verify(audits, never()).record(any());
    }

    /** 全部资格拒绝原因都必须映射为冻结的安全业务分类并停止任何发布写。 */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("qualificationFailures")
    @DisplayName("资格异常映射为稳定安全业务错误")
    void qualificationFailuresMapToStableSafeBusinessErrors(
            DashboardPublicationQualificationException.Reason reason,
            DashboardErrorCode expectedError) {
        when(repository.lockPublicationState(projectId, dashboardId))
                .thenReturn(Optional.of(state(7, 11, 3)));
        when(repository.findDraft(projectId, dashboardId)).thenReturn(Optional.of(lockedDraft));
        when(candidateFactory.prepare(lockedDraft)).thenReturn(candidate);
        when(qualificationService.qualify(candidate))
                .thenThrow(new DashboardPublicationQualificationException(reason));

        assertBusinessError(() -> service.publish(projectId, dashboardId, "7", "11"), expectedError);

        verify(repository, never()).appendPublication(any(), any(Long.class));
        verify(audits, never()).record(any());
    }

    /** 受控追加入口的全部失败分类必须稳定转换，且失败后绝不能记录发布审计。 */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("appendFailures")
    @DisplayName("原子追加失败映射为稳定业务分类")
    void appendFailuresMapToStableBusinessErrors(
            DashboardPublicationAppendResult appendResult,
            DashboardErrorCode expectedError) {
        stubQualifiedCandidate();
        when(repository.appendPublication(any(DashboardVersion.class), eq(11L)))
                .thenReturn(appendResult);

        assertBusinessError(() -> service.publish(projectId, dashboardId, "7", "11"), expectedError);

        verify(audits, never()).record(any());
    }

    /** OWNER与ADMIN回滚只消费历史版本和发布轴，并记录最小审计。 */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ProjectRole.class, names = {"OWNER", "ADMIN"})
    @DisplayName("管理角色重新核验历史版本后只切换发布指针")
    void managementRolesRollbackQualifiedHistoricalVersionWithoutReadingDraft(ProjectRole role) {
        UUID targetVersionId = UUID.randomUUID();
        DashboardVersion target = version(targetVersionId, 2);
        DashboardPublicationState lockedState = new DashboardPublicationState(
                dashboardId, ownerTenantId, projectId, 19, 11,
                UUID.randomUUID(), 3, null);
        when(projectService.requireRoleInProject(projectId)).thenReturn(role);
        when(repository.lockPublicationState(projectId, dashboardId)).thenReturn(Optional.of(lockedState));
        when(repository.findVersion(projectId, dashboardId, targetVersionId)).thenReturn(Optional.of(target));
        when(candidateFactory.prepareHistoricalVersion(target)).thenReturn(candidate);
        when(qualificationService.qualify(candidate))
                .thenReturn(new QualifiedDashboardPublicationCandidate(candidate));
        when(repository.rollbackPublication(
                eq(projectId), eq(dashboardId), eq(targetVersionId), eq(11L), eq(accountId), any(Instant.class)))
                .thenReturn(new DashboardPublicationRollbackResult(
                        DashboardPublicationRollbackResult.Status.ROLLED_BACK, 12L));

        DashboardVersion rolledBack = service.rollback(projectId, dashboardId, targetVersionId, "11");

        assertThat(rolledBack).isEqualTo(target);
        ArgumentCaptor<AuditLogEntry> auditCaptor = ArgumentCaptor.forClass(AuditLogEntry.class);
        InOrder order = inOrder(
                projectService, lifecycleAccessService, repository,
                candidateFactory, qualificationService, audits);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(projectService).requireProjectTenant(projectId);
        order.verify(lifecycleAccessService).requireActiveForWrite(ownerTenantId, projectId);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(repository).lockPublicationState(projectId, dashboardId);
        order.verify(repository).findVersion(projectId, dashboardId, targetVersionId);
        order.verify(candidateFactory).prepareHistoricalVersion(target);
        order.verify(qualificationService).qualify(candidate);
        order.verify(repository).rollbackPublication(
                eq(projectId), eq(dashboardId), eq(targetVersionId), eq(11L), eq(accountId), any(Instant.class));
        order.verify(audits).record(auditCaptor.capture());
        verify(repository, never()).findDraft(any(), any());
        verify(repository, never()).appendPublication(any(), any(Long.class));

        AuditLogEntry audit = auditCaptor.getValue();
        assertThat(audit.action()).isEqualTo("dashboard.rolled_back");
        assertThat(audit.details()).isEqualTo(Map.of(
                "versionId", targetVersionId.toString(),
                "versionNumber", "2",
                "publicationRevision", "12"));
    }

    /** 只读角色必须在项目锁、版本读取和资格核验前拒绝回滚。 */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ProjectRole.class, names = {"OPERATOR", "VIEWER"})
    @DisplayName("只读角色不能回滚看板")
    void readOnlyRolesCannotRollback(ProjectRole role) {
        when(projectService.requireRoleInProject(projectId)).thenReturn(role);

        assertBusinessError(() -> service.rollback(
                projectId, dashboardId, UUID.randomUUID(), "11"),
                DashboardErrorCode.DASHBOARD_MANAGEMENT_FORBIDDEN);

        verify(lifecycleAccessService, never()).requireActiveForWrite(any(), any());
        verify(repository, never()).lockPublicationState(any(), any());
        verify(qualificationService, never()).qualify(any());
        verify(audits, never()).record(any());
    }

    /** 旧发布revision、计数耗尽与目标已是当前版本都必须在读取历史版本前短路。 */
    @ParameterizedTest(name = "expected={0}, current={1}, max={2}")
    @CsvSource({"10, 11, 3", "11, 9223372036854775807, 3"})
    @DisplayName("回滚并发冲突在历史版本读取前短路")
    void rollbackPublicationConflictStopsBeforeHistoricalVersion(
            String expectedRevision, long actualRevision, long latestVersionNumber) {
        UUID targetVersionId = UUID.randomUUID();
        when(repository.lockPublicationState(projectId, dashboardId)).thenReturn(Optional.of(
                new DashboardPublicationState(
                        dashboardId, ownerTenantId, projectId, 99, actualRevision,
                        UUID.randomUUID(), latestVersionNumber, null)));

        assertBusinessError(() -> service.rollback(
                projectId, dashboardId, targetVersionId, expectedRevision),
                DashboardErrorCode.DASHBOARD_PUBLICATION_CONFLICT);

        verify(repository, never()).findVersion(any(), any(), any());
        verify(qualificationService, never()).qualify(any());
        verify(repository, never()).rollbackPublication(any(), any(), any(), any(Long.class), any(), any());
    }

    /** 目标已是当前版本时不能制造无意义revision和审计。 */
    @Test
    @DisplayName("目标已是当前版本按发布冲突拒绝")
    void rollbackToCurrentVersionIsRejectedBeforeQualification() {
        UUID targetVersionId = UUID.randomUUID();
        when(repository.lockPublicationState(projectId, dashboardId)).thenReturn(Optional.of(
                new DashboardPublicationState(
                        dashboardId, ownerTenantId, projectId, 7, 11,
                        targetVersionId, 3, null)));

        assertBusinessError(() -> service.rollback(
                projectId, dashboardId, targetVersionId, "11"),
                DashboardErrorCode.DASHBOARD_PUBLICATION_CONFLICT);

        verify(repository, never()).findVersion(any(), any(), any());
        verify(audits, never()).record(any());
    }

    /** 精确目标不存在、跨项目或跨看板统一使用专用安全错误。 */
    @Test
    @DisplayName("回滚目标不可见不进入当前资格核验")
    void missingRollbackTargetMapsToSafeTargetNotFound() {
        UUID targetVersionId = UUID.randomUUID();
        when(repository.lockPublicationState(projectId, dashboardId)).thenReturn(Optional.of(
                new DashboardPublicationState(
                        dashboardId, ownerTenantId, projectId, 7, 11,
                        UUID.randomUUID(), 3, null)));
        when(repository.findVersion(projectId, dashboardId, targetVersionId)).thenReturn(Optional.empty());

        assertBusinessError(() -> service.rollback(
                projectId, dashboardId, targetVersionId, "11"),
                DashboardErrorCode.DASHBOARD_ROLLBACK_TARGET_NOT_FOUND);

        verify(candidateFactory, never()).prepareHistoricalVersion(any());
        verify(qualificationService, never()).qualify(any());
        verify(audits, never()).record(any());
    }

    /** 历史Schema按当前合同不兼容是可恢复资格拒绝，持久完整性异常则不能被吞掉。 */
    @Test
    @DisplayName("历史版本Schema不兼容与持久完整性异常保持不同边界")
    void historicalSchemaIncompatibilityIsRecoverableButIntegrityFailurePropagates() {
        UUID targetVersionId = UUID.randomUUID();
        DashboardVersion target = version(targetVersionId, 2);
        stubRollbackCandidate(target);
        when(candidateFactory.prepareHistoricalVersion(target))
                .thenThrow(new DashboardSchemaValidationException(
                        DashboardSchemaValidationException.Reason.INVALID_VALUE,
                        "历史Schema当前不兼容"));

        assertBusinessError(() -> service.rollback(
                projectId, dashboardId, targetVersionId, "11"),
                DashboardErrorCode.DASHBOARD_PUBLICATION_INVALID);

        resetRollbackCandidate(target);
        IllegalStateException integrityFailure = new IllegalStateException("不可变事实漂移");
        when(candidateFactory.prepareHistoricalVersion(target)).thenThrow(integrityFailure);
        assertThatThrownBy(() -> service.rollback(projectId, dashboardId, targetVersionId, "11"))
                .isSameAs(integrityFailure);
        verify(repository, never()).rollbackPublication(any(), any(), any(), any(Long.class), any(), any());
        verify(audits, never()).record(any());
    }

    /** 历史版本必须按当前八类外部事实重新资格，失败时不切换指针。 */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("qualificationFailures")
    @DisplayName("回滚复用当前资格安全错误分类")
    void rollbackQualificationFailuresMapToStableSafeBusinessErrors(
            DashboardPublicationQualificationException.Reason reason,
            DashboardErrorCode expectedError) {
        UUID targetVersionId = UUID.randomUUID();
        DashboardVersion target = version(targetVersionId, 2);
        stubRollbackCandidate(target);
        when(qualificationService.qualify(candidate))
                .thenThrow(new DashboardPublicationQualificationException(reason));

        assertBusinessError(() -> service.rollback(
                projectId, dashboardId, targetVersionId, "11"), expectedError);

        verify(repository, never()).rollbackPublication(any(), any(), any(), any(Long.class), any(), any());
        verify(audits, never()).record(any());
    }

    /** 受控回滚入口的全部失败分类必须稳定转换且零审计。 */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("rollbackFailures")
    @DisplayName("受控回滚失败映射为稳定业务分类")
    void rollbackFailuresMapToStableBusinessErrors(
            DashboardPublicationRollbackResult rollbackResult,
            DashboardErrorCode expectedError) {
        UUID targetVersionId = UUID.randomUUID();
        DashboardVersion target = version(targetVersionId, 2);
        stubRollbackCandidate(target);
        when(qualificationService.qualify(candidate))
                .thenReturn(new QualifiedDashboardPublicationCandidate(candidate));
        when(repository.rollbackPublication(
                eq(projectId), eq(dashboardId), eq(targetVersionId), eq(11L), eq(accountId), any(Instant.class)))
                .thenReturn(rollbackResult);

        assertBusinessError(() -> service.rollback(
                projectId, dashboardId, targetVersionId, "11"), expectedError);

        verify(audits, never()).record(any());
    }

    /** OWNER与ADMIN撤回只读取当前不可变版本、清空指针并记录最小审计，不重跑发布资格。 */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ProjectRole.class, names = {"OWNER", "ADMIN"})
    @DisplayName("管理角色撤回当前发布版本并记录最小审计")
    void managementRolesWithdrawCurrentVersionWithoutRequalification(ProjectRole role) {
        UUID currentVersionId = UUID.randomUUID();
        DashboardVersion currentVersion = version(currentVersionId, 3);
        when(projectService.requireRoleInProject(projectId)).thenReturn(role);
        when(repository.lockPublicationState(projectId, dashboardId)).thenReturn(Optional.of(
                new DashboardPublicationState(
                        dashboardId, ownerTenantId, projectId, 19, 11,
                        currentVersionId, 3, null)));
        when(repository.findVersion(projectId, dashboardId, currentVersionId))
                .thenReturn(Optional.of(currentVersion));
        when(repository.withdrawPublication(
                eq(projectId), eq(dashboardId), eq(11L), eq(accountId), any(Instant.class)))
                .thenReturn(new DashboardPublicationWithdrawalResult(
                        DashboardPublicationWithdrawalResult.Status.WITHDRAWN,
                        currentVersionId, 12L));

        DashboardVersion withdrawn = service.withdraw(projectId, dashboardId, "11");

        assertThat(withdrawn).isEqualTo(currentVersion);
        ArgumentCaptor<AuditLogEntry> auditCaptor = ArgumentCaptor.forClass(AuditLogEntry.class);
        InOrder order = inOrder(projectService, lifecycleAccessService, repository, audits);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(projectService).requireProjectTenant(projectId);
        order.verify(lifecycleAccessService).requireActiveForWrite(ownerTenantId, projectId);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(repository).lockPublicationState(projectId, dashboardId);
        order.verify(repository).findVersion(projectId, dashboardId, currentVersionId);
        order.verify(repository).withdrawPublication(
                eq(projectId), eq(dashboardId), eq(11L), eq(accountId), any(Instant.class));
        order.verify(audits).record(auditCaptor.capture());
        verify(candidateFactory, never()).prepare(any());
        verify(candidateFactory, never()).prepareHistoricalVersion(any());
        verify(qualificationService, never()).qualify(any());

        AuditLogEntry audit = auditCaptor.getValue();
        assertThat(audit.action()).isEqualTo("dashboard.withdrawn");
        assertThat(audit.details()).isEqualTo(Map.of(
                "previousVersionId", currentVersionId.toString(),
                "previousVersionNumber", "3",
                "publicationRevision", "12"));
    }

    /** OPERATOR与VIEWER必须在ACTIVE锁、看板行锁和当前版本读取前稳定拒绝撤回。 */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ProjectRole.class, names = {"OPERATOR", "VIEWER"})
    @DisplayName("只读角色不能撤回看板")
    void readOnlyRolesCannotWithdraw(ProjectRole role) {
        when(projectService.requireRoleInProject(projectId)).thenReturn(role);

        assertBusinessError(() -> service.withdraw(projectId, dashboardId, "11"),
                DashboardErrorCode.DASHBOARD_MANAGEMENT_FORBIDDEN);

        verify(lifecycleAccessService, never()).requireActiveForWrite(any(), any());
        verify(repository, never()).lockPublicationState(any(), any());
        verify(repository, never()).withdrawPublication(any(), any(), any(Long.class), any(), any());
        verify(audits, never()).record(any());
    }

    /** 项目ACTIVE锁拒绝必须原样终止撤回，不能进入看板行锁或写入审计。 */
    @Test
    @DisplayName("非ACTIVE项目不能撤回看板")
    void inactiveProjectStopsWithdrawalBeforeDashboardLock() {
        BusinessException inactive = new BusinessException(ProjectErrorCode.PROJECT_READ_ONLY);
        doThrow(inactive).when(lifecycleAccessService)
                .requireActiveForWrite(ownerTenantId, projectId);

        assertThatThrownBy(() -> service.withdraw(projectId, dashboardId, "11")).isSameAs(inactive);

        verify(repository, never()).lockPublicationState(any(), any());
        verify(repository, never()).withdrawPublication(any(), any(), any(Long.class), any(), any());
        verify(audits, never()).record(any());
    }

    /** 空指针、旧revision与Long耗尽均在当前版本读取前按同一可恢复冲突拒绝。 */
    @ParameterizedTest(name = "expected={0}, actual={1}, current={2}")
    @CsvSource(value = {
            "10,11,present",
            "11,11,null",
            "9223372036854775807,9223372036854775807,present"
    }, nullValues = "null")
    @DisplayName("撤回空指针或错误revision不制造状态变化")
    void withdrawalConflictStopsBeforeCurrentVersionRead(
            String expectedRevision, long actualRevision, String currentMarker) {
        UUID currentVersionId = currentMarker == null ? null : UUID.randomUUID();
        when(repository.lockPublicationState(projectId, dashboardId)).thenReturn(Optional.of(
                new DashboardPublicationState(
                        dashboardId, ownerTenantId, projectId, 19, actualRevision,
                        currentVersionId, 3, null)));

        assertBusinessError(() -> service.withdraw(
                projectId, dashboardId, expectedRevision),
                DashboardErrorCode.DASHBOARD_PUBLICATION_CONFLICT);

        verify(repository, never()).findVersion(any(), any(), any());
        verify(repository, never()).withdrawPublication(any(), any(), any(Long.class), any(), any());
        verify(audits, never()).record(any());
    }

    /** 受控撤回入口的失败分类统一映射为不存在或发布冲突，且不写审计。 */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("withdrawalFailures")
    @DisplayName("受控撤回失败映射为稳定业务分类")
    void withdrawalFailuresMapToStableBusinessErrors(
            DashboardPublicationWithdrawalResult result,
            DashboardErrorCode expectedError) {
        UUID currentVersionId = UUID.randomUUID();
        DashboardVersion currentVersion = version(currentVersionId, 3);
        when(repository.lockPublicationState(projectId, dashboardId)).thenReturn(Optional.of(
                new DashboardPublicationState(
                        dashboardId, ownerTenantId, projectId, 19, 11,
                        currentVersionId, 3, null)));
        when(repository.findVersion(projectId, dashboardId, currentVersionId))
                .thenReturn(Optional.of(currentVersion));
        when(repository.withdrawPublication(
                eq(projectId), eq(dashboardId), eq(11L), eq(accountId), any(Instant.class)))
                .thenReturn(result);

        assertBusinessError(() -> service.withdraw(projectId, dashboardId, "11"), expectedError);

        verify(audits, never()).record(any());
    }

    /** OWNER与ADMIN无论当前指针是否存在都可软删一次，并记录允许空旧版本身份的最小审计。 */
    @ParameterizedTest(name = "{0}, current={1}")
    @MethodSource("softDeleteSuccessCases")
    @DisplayName("管理角色软删已发布或已撤回看板")
    void managementRolesSoftDeleteWithOrWithoutCurrentVersion(
            ProjectRole role, boolean hasCurrentVersion) {
        UUID currentVersionId = hasCurrentVersion ? UUID.randomUUID() : null;
        DashboardVersion currentVersion = hasCurrentVersion ? version(currentVersionId, 3) : null;
        when(projectService.requireRoleInProject(projectId)).thenReturn(role);
        when(repository.lockPublicationState(projectId, dashboardId)).thenReturn(Optional.of(
                new DashboardPublicationState(
                        dashboardId, ownerTenantId, projectId, 19, 11,
                        currentVersionId, 3, null)));
        if (hasCurrentVersion) {
            when(repository.findVersion(projectId, dashboardId, currentVersionId))
                    .thenReturn(Optional.of(currentVersion));
        }
        when(repository.softDelete(
                eq(projectId), eq(dashboardId), eq(11L), eq(accountId), any(Instant.class)))
                .thenAnswer(invocation -> new DashboardSoftDeleteResult(
                        DashboardSoftDeleteResult.Status.DELETED,
                        currentVersionId, 12L, invocation.getArgument(4, Instant.class)));

        service.softDelete(projectId, dashboardId, "11");

        ArgumentCaptor<AuditLogEntry> auditCaptor = ArgumentCaptor.forClass(AuditLogEntry.class);
        InOrder order = inOrder(projectService, lifecycleAccessService, repository, audits);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(projectService).requireProjectTenant(projectId);
        order.verify(lifecycleAccessService).requireActiveForWrite(ownerTenantId, projectId);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(repository).lockPublicationState(projectId, dashboardId);
        if (hasCurrentVersion) {
            order.verify(repository).findVersion(projectId, dashboardId, currentVersionId);
        }
        order.verify(repository).softDelete(
                eq(projectId), eq(dashboardId), eq(11L), eq(accountId), any(Instant.class));
        order.verify(audits).record(auditCaptor.capture());
        if (!hasCurrentVersion) {
            verify(repository, never()).findVersion(any(), any(), any());
        }
        verify(candidateFactory, never()).prepare(any());
        verify(candidateFactory, never()).prepareHistoricalVersion(any());
        verify(qualificationService, never()).qualify(any());

        AuditLogEntry audit = auditCaptor.getValue();
        assertThat(audit.action()).isEqualTo("dashboard.deleted");
        assertThat(audit.details()).hasSize(4);
        assertThat(audit.details().get("previousVersionId"))
                .isEqualTo(hasCurrentVersion ? currentVersionId.toString() : null);
        assertThat(audit.details().get("previousVersionNumber"))
                .isEqualTo(hasCurrentVersion ? "3" : null);
        assertThat(audit.details().get("publicationRevision")).isEqualTo("12");
        assertThat(audit.details().get("deletedAt")).isNotNull();
    }

    /** OPERATOR与VIEWER必须在ACTIVE锁和目录行锁前拒绝软删。 */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ProjectRole.class, names = {"OPERATOR", "VIEWER"})
    @DisplayName("只读角色不能软删看板")
    void readOnlyRolesCannotSoftDelete(ProjectRole role) {
        when(projectService.requireRoleInProject(projectId)).thenReturn(role);

        assertBusinessError(() -> service.softDelete(projectId, dashboardId, "11"),
                DashboardErrorCode.DASHBOARD_MANAGEMENT_FORBIDDEN);

        verify(lifecycleAccessService, never()).requireActiveForWrite(any(), any());
        verify(repository, never()).lockPublicationState(any(), any());
        verify(repository, never()).softDelete(any(), any(), any(Long.class), any(), any());
        verify(audits, never()).record(any());
    }

    /** ARCHIVED项目的持续写许可失败必须原样传播并停止看板软删。 */
    @Test
    @DisplayName("归档项目不能软删看板")
    void archivedProjectStopsSoftDeleteBeforeDashboardLock() {
        BusinessException archived = new BusinessException(ProjectErrorCode.PROJECT_READ_ONLY);
        doThrow(archived).when(lifecycleAccessService)
                .requireActiveForWrite(ownerTenantId, projectId);

        assertThatThrownBy(() -> service.softDelete(projectId, dashboardId, "11"))
                .isSameAs(archived);

        verify(repository, never()).lockPublicationState(any(), any());
        verify(repository, never()).softDelete(any(), any(), any(Long.class), any(), any());
        verify(audits, never()).record(any());
    }

    /** 非规范十进制revision必须在角色、数据库锁及审计前按60039拒绝。 */
    @ParameterizedTest(name = "revision={0}")
    @CsvSource(value = {"''", "01", "-1", "9223372036854775808"})
    @DisplayName("非法revision不能进入软删授权或持久层")
    void invalidRevisionStopsSoftDeleteBeforeAuthorization(String revision) {
        assertBusinessError(() -> service.softDelete(projectId, dashboardId, revision),
                DashboardErrorCode.DASHBOARD_PUBLICATION_INVALID);

        verify(projectService, never()).requireRoleInProject(any());
        verify(repository, never()).lockPublicationState(any(), any());
        verify(audits, never()).record(any());
    }

    /** 目标不可见、旧revision与Long耗尽都必须在当前版本读取和删除入口前短路。 */
    @ParameterizedTest(name = "visible={0}, expected={1}, actual={2}")
    @CsvSource({
            "false,11,11",
            "true,10,11",
            "true,9223372036854775807,9223372036854775807"
    })
    @DisplayName("不可见或冲突的看板不进入受控软删")
    void unavailableOrConflictingDashboardStopsSoftDelete(
            boolean visible, String expectedRevision, long actualRevision) {
        if (visible) {
            when(repository.lockPublicationState(projectId, dashboardId)).thenReturn(Optional.of(
                    new DashboardPublicationState(
                            dashboardId, ownerTenantId, projectId, 19, actualRevision,
                            UUID.randomUUID(), 3, null)));
        } else {
            when(repository.lockPublicationState(projectId, dashboardId)).thenReturn(Optional.empty());
        }

        assertBusinessError(() -> service.softDelete(
                projectId, dashboardId, expectedRevision),
                visible ? DashboardErrorCode.DASHBOARD_PUBLICATION_CONFLICT
                        : DashboardErrorCode.DASHBOARD_NOT_FOUND);

        verify(repository, never()).findVersion(any(), any(), any());
        verify(repository, never()).softDelete(any(), any(), any(Long.class), any(), any());
        verify(audits, never()).record(any());
    }

    /** 数据库受控软删的不可见和CAS失败分类必须稳定转换，且失败后零审计。 */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("softDeleteFailures")
    @DisplayName("受控软删失败映射为稳定业务分类")
    void softDeleteFailuresMapToStableBusinessErrors(
            DashboardSoftDeleteResult result,
            DashboardErrorCode expectedError) {
        when(repository.lockPublicationState(projectId, dashboardId)).thenReturn(Optional.of(
                new DashboardPublicationState(
                        dashboardId, ownerTenantId, projectId, 19, 11,
                        null, 3, null)));
        when(repository.softDelete(
                eq(projectId), eq(dashboardId), eq(11L), eq(accountId), any(Instant.class)))
                .thenReturn(result);

        assertBusinessError(() -> service.softDelete(projectId, dashboardId, "11"), expectedError);

        verify(audits, never()).record(any());
    }

    /** @return 八类资格拒绝原因及其对外安全分类 */
    private static Stream<Arguments> qualificationFailures() {
        return Stream.of(
                Arguments.of(DashboardPublicationQualificationException.Reason.MODEL_REFERENCE_INVALID,
                        DashboardErrorCode.DASHBOARD_MODEL_REFERENCE_INVALID),
                Arguments.of(DashboardPublicationQualificationException.Reason.HOST_COMPONENT_UNAVAILABLE,
                        DashboardErrorCode.DASHBOARD_PUBLICATION_DEPENDENCY_UNAVAILABLE),
                Arguments.of(DashboardPublicationQualificationException.Reason.DATA_ADAPTER_UNAVAILABLE,
                        DashboardErrorCode.DASHBOARD_PUBLICATION_DEPENDENCY_UNAVAILABLE),
                Arguments.of(DashboardPublicationQualificationException.Reason.BUILTIN_RESOURCE_UNAVAILABLE,
                        DashboardErrorCode.DASHBOARD_PUBLICATION_INVALID),
                Arguments.of(DashboardPublicationQualificationException.Reason.DEFAULT_DEVICE_INVALID,
                        DashboardErrorCode.DASHBOARD_PUBLICATION_INVALID),
                Arguments.of(DashboardPublicationQualificationException.Reason.MODEL_PROPERTY_INVALID,
                        DashboardErrorCode.DASHBOARD_PUBLICATION_INVALID),
                Arguments.of(DashboardPublicationQualificationException.Reason.MODEL_RANGE_INVALID,
                        DashboardErrorCode.DASHBOARD_PUBLICATION_INVALID),
                Arguments.of(DashboardPublicationQualificationException.Reason.BUDGET_EXCEEDED,
                        DashboardErrorCode.DASHBOARD_PUBLICATION_INVALID));
    }

    /** @return 受控追加失败分类及其稳定业务错误 */
    private static Stream<Arguments> appendFailures() {
        return Stream.of(
                Arguments.of(new DashboardPublicationAppendResult(
                                DashboardPublicationAppendResult.Status.NOT_FOUND, null, null),
                        DashboardErrorCode.DASHBOARD_NOT_FOUND),
                Arguments.of(new DashboardPublicationAppendResult(
                                DashboardPublicationAppendResult.Status.DRAFT_CONFLICT, null, 11L),
                        DashboardErrorCode.DASHBOARD_PUBLICATION_CONFLICT),
                Arguments.of(new DashboardPublicationAppendResult(
                                DashboardPublicationAppendResult.Status.PUBLICATION_CONFLICT, null, 12L),
                        DashboardErrorCode.DASHBOARD_PUBLICATION_CONFLICT),
                Arguments.of(new DashboardPublicationAppendResult(
                                DashboardPublicationAppendResult.Status.REVISION_EXHAUSTED, null, null),
                        DashboardErrorCode.DASHBOARD_PUBLICATION_CONFLICT));
    }

    /** @return 受控回滚失败分类及其稳定业务错误 */
    private static Stream<Arguments> rollbackFailures() {
        return Stream.of(
                Arguments.of(new DashboardPublicationRollbackResult(
                                DashboardPublicationRollbackResult.Status.DASHBOARD_NOT_FOUND, null),
                        DashboardErrorCode.DASHBOARD_NOT_FOUND),
                Arguments.of(new DashboardPublicationRollbackResult(
                                DashboardPublicationRollbackResult.Status.TARGET_NOT_FOUND, null),
                        DashboardErrorCode.DASHBOARD_ROLLBACK_TARGET_NOT_FOUND),
                Arguments.of(new DashboardPublicationRollbackResult(
                                DashboardPublicationRollbackResult.Status.CURRENT_VERSION, 11L),
                        DashboardErrorCode.DASHBOARD_PUBLICATION_CONFLICT),
                Arguments.of(new DashboardPublicationRollbackResult(
                                DashboardPublicationRollbackResult.Status.PUBLICATION_CONFLICT, 12L),
                        DashboardErrorCode.DASHBOARD_PUBLICATION_CONFLICT),
                Arguments.of(new DashboardPublicationRollbackResult(
                                DashboardPublicationRollbackResult.Status.REVISION_EXHAUSTED, 11L),
                        DashboardErrorCode.DASHBOARD_PUBLICATION_CONFLICT));
    }

    /** @return 受控撤回失败分类及其稳定业务错误 */
    private static Stream<Arguments> withdrawalFailures() {
        return Stream.of(
                Arguments.of(new DashboardPublicationWithdrawalResult(
                                DashboardPublicationWithdrawalResult.Status.DASHBOARD_NOT_FOUND,
                                null, null),
                        DashboardErrorCode.DASHBOARD_NOT_FOUND),
                Arguments.of(new DashboardPublicationWithdrawalResult(
                                DashboardPublicationWithdrawalResult.Status.NOT_PUBLISHED,
                                null, 11L),
                        DashboardErrorCode.DASHBOARD_PUBLICATION_CONFLICT),
                Arguments.of(new DashboardPublicationWithdrawalResult(
                                DashboardPublicationWithdrawalResult.Status.PUBLICATION_CONFLICT,
                                null, 12L),
                        DashboardErrorCode.DASHBOARD_PUBLICATION_CONFLICT),
                Arguments.of(new DashboardPublicationWithdrawalResult(
                                DashboardPublicationWithdrawalResult.Status.REVISION_EXHAUSTED,
                                null, 11L),
                        DashboardErrorCode.DASHBOARD_PUBLICATION_CONFLICT));
    }

    /** @return 管理角色和有无当前指针的软删成功组合 */
    private static Stream<Arguments> softDeleteSuccessCases() {
        return Stream.of(
                Arguments.of(ProjectRole.OWNER, true),
                Arguments.of(ProjectRole.OWNER, false),
                Arguments.of(ProjectRole.ADMIN, true),
                Arguments.of(ProjectRole.ADMIN, false));
    }

    /** @return 受控软删失败分类及其稳定业务错误 */
    private static Stream<Arguments> softDeleteFailures() {
        return Stream.of(
                Arguments.of(new DashboardSoftDeleteResult(
                                DashboardSoftDeleteResult.Status.DASHBOARD_NOT_FOUND,
                                null, null, null),
                        DashboardErrorCode.DASHBOARD_NOT_FOUND),
                Arguments.of(new DashboardSoftDeleteResult(
                                DashboardSoftDeleteResult.Status.PUBLICATION_CONFLICT,
                                null, 12L, null),
                        DashboardErrorCode.DASHBOARD_PUBLICATION_CONFLICT),
                Arguments.of(new DashboardSoftDeleteResult(
                                DashboardSoftDeleteResult.Status.REVISION_EXHAUSTED,
                                null, 11L, null),
                        DashboardErrorCode.DASHBOARD_PUBLICATION_CONFLICT));
    }

    /** 配置锁内状态、草稿、候选及资格成功的公共路径。 */
    private void stubQualifiedCandidate() {
        when(repository.lockPublicationState(projectId, dashboardId))
                .thenReturn(Optional.of(state(7, 11, 3)));
        when(repository.findDraft(projectId, dashboardId)).thenReturn(Optional.of(lockedDraft));
        when(candidateFactory.prepare(lockedDraft)).thenReturn(candidate);
        when(qualificationService.qualify(candidate))
                .thenReturn(new QualifiedDashboardPublicationCandidate(candidate));
    }

    /** 配置锁内状态、历史版本和重建候选，但由各用例决定资格结果。 */
    private void stubRollbackCandidate(DashboardVersion target) {
        when(repository.lockPublicationState(projectId, dashboardId)).thenReturn(Optional.of(
                new DashboardPublicationState(
                        dashboardId, ownerTenantId, projectId, 19, 11,
                        UUID.randomUUID(), 3, null)));
        when(repository.findVersion(projectId, dashboardId, target.id())).thenReturn(Optional.of(target));
        when(candidateFactory.prepareHistoricalVersion(target)).thenReturn(candidate);
    }

    /** 清除并重建回滚前置模拟，供同一用例核对两类异常不会互相吞并。 */
    private void resetRollbackCandidate(DashboardVersion target) {
        org.mockito.Mockito.reset(repository, candidateFactory);
        stubRollbackCandidate(target);
    }

    /** @return 指定身份与版本号的合法历史不可变版本 */
    private DashboardVersion version(UUID versionId, long versionNumber) {
        return new DashboardVersion(
                versionId, ownerTenantId, projectId, dashboardId, versionNumber, 7,
                legalSchema(), DashboardDraft.SCHEMA_VERSION, DIGEST_ALGORITHM, SCHEMA_DIGEST,
                JSON.createArrayNode(), JSON.createArrayNode(), accountId,
                Instant.parse("2026-09-06T00:00:00Z"), List.of());
    }

    /** @return 指定revision且无模型引用的合法持久草稿 */
    private DashboardDraft draft(long revision) {
        Instant timestamp = Instant.parse("2026-09-06T00:00:00Z");
        return new DashboardDraft(
                dashboardId, ownerTenantId, projectId, legalSchema(), revision,
                accountId, timestamp, timestamp, List.of());
    }

    /** @return 从指定锁内草稿身份和内容建立的最小合法候选 */
    private DashboardPublicationCandidate candidate(DashboardDraft draft) {
        return new DashboardPublicationCandidate(
                draft.dashboardId(), draft.tenantId(), draft.projectId(), draft.revision(),
                draft.content(), draft.content().toString(), DIGEST_ALGORITHM, SCHEMA_DIGEST,
                draft.modelReferences(), List.of(), List.of(), List.of());
    }

    /** @return 指定三条版本轴且未删除的锁内发布状态 */
    private DashboardPublicationState state(
            long draftRevision, long publicationRevision, long latestVersionNumber) {
        return new DashboardPublicationState(
                dashboardId, ownerTenantId, projectId, draftRevision, publicationRevision,
                publicationRevision == 0 ? null : UUID.randomUUID(), latestVersionNumber, null);
    }

    /** @return 满足持久值对象约束的最小完整空画布Schema */
    private static ObjectNode legalSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("schemaVersion", "tc.dashboard/v1");
        schema.putObject("presentation").put("mode", "RESPONSIVE_GRID");
        schema.putArray("models");
        schema.putArray("variables");
        schema.putArray("pages")
                .addObject()
                .put("id", "main")
                .put("title", "看板")
                .putArray("components");
        return schema;
    }

    /** 断言公开错误码，并确保异常不携带内部资格细节。 */
    private static void assertBusinessError(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            DashboardErrorCode expectedError) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, failure -> {
            assertThat(failure.errorCode()).isEqualTo(expectedError);
            assertThat(failure.details()).isEmpty();
        });
    }
}
