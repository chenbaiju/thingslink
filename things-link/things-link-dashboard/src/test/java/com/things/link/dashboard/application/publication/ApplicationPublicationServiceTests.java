package com.things.link.dashboard.application.publication;

import com.things.link.dashboard.domain.ApplicationDraft;
import com.things.link.dashboard.domain.ApplicationErrorCode;
import com.things.link.dashboard.domain.ApplicationPublicationAppendResult;
import com.things.link.dashboard.domain.ApplicationPublicationRollbackResult;
import com.things.link.dashboard.domain.ApplicationPublicationState;
import com.things.link.dashboard.domain.ApplicationPublicationWithdrawalResult;
import com.things.link.dashboard.domain.ApplicationRepository;
import com.things.link.dashboard.domain.ApplicationSoftDeleteResult;
import com.things.link.dashboard.domain.ApplicationVersion;
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
import tools.jackson.databind.node.ArrayNode;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** S12-1c2至1c5应用发布生命周期的锁内事实、受控结果分类和唯一审计编排测试。 */
@DisplayName("应用发布状态业务编排")
class ApplicationPublicationServiceTests {

    /** 测试JSON构造器。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** PostgreSQL应用快照摘要算法。 */
    private static final String DIGEST_ALGORITHM = "PG_JSONB_TEXT_V1_SHA256";
    /** 合法应用快照摘要。 */
    private static final String SNAPSHOT_DIGEST = "a".repeat(64);

    /** 应用持久端口替身。 */
    private ApplicationRepository repository;
    /** 项目角色和owner tenant端口替身。 */
    private ProjectService projectService;
    /** 项目持续ACTIVE写许可端口替身。 */
    private ProjectLifecycleAccessService lifecycleAccessService;
    /** 全部引用看板锁内候选工厂替身。 */
    private ApplicationPublicationCandidateFactory candidateFactory;
    /** 当前宿主资格服务替身。 */
    private ApplicationPublicationQualificationService qualificationService;
    /** 同事务审计端口替身。 */
    private AuditLogService audits;
    /** 被测应用发布服务。 */
    private ApplicationPublicationService service;
    /** 当前项目ID。 */
    private UUID projectId;
    /** 当前应用ID。 */
    private UUID applicationId;
    /** 项目真实owner租户ID。 */
    private UUID ownerTenantId;
    /** 当前Console账号ID。 */
    private UUID accountId;
    /** 锁内重新读取的应用草稿。 */
    private ApplicationDraft lockedDraft;
    /** 锁内按稳定看板顺序重建的候选。 */
    private ApplicationPublicationCandidate candidate;

    /** 每例建立OWNER、ACTIVE许可上下文和revision为7的持久草稿。 */
    @BeforeEach
    void setUp() {
        repository = mock(ApplicationRepository.class);
        projectService = mock(ProjectService.class);
        lifecycleAccessService = mock(ProjectLifecycleAccessService.class);
        candidateFactory = mock(ApplicationPublicationCandidateFactory.class);
        qualificationService = mock(ApplicationPublicationQualificationService.class);
        audits = mock(AuditLogService.class);
        service = new ApplicationPublicationService(
                repository, projectService, lifecycleAccessService,
                candidateFactory, qualificationService, audits);
        projectId = UUID.randomUUID();
        applicationId = UUID.randomUUID();
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

    /** 管理角色必须在项目许可、应用锁和看板锁内重建之后原子追加并记录一次最小审计。 */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ProjectRole.class, names = {"OWNER", "ADMIN"})
    @DisplayName("管理角色按固定顺序发布锁内候选并记录唯一审计")
    void managementRolesPublishLockedCandidateAndRecordSingleAuditInOrder(ProjectRole role) {
        when(projectService.requireRoleInProject(projectId)).thenReturn(role);
        stubQualifiedCandidate();
        when(repository.appendPublication(any(ApplicationVersion.class), eq(11L)))
                .thenReturn(new ApplicationPublicationAppendResult(
                        ApplicationPublicationAppendResult.Status.PUBLISHED, 4L, 12L));

        ApplicationVersion published = service.publish(projectId, applicationId, "7", "11");

        ArgumentCaptor<ApplicationVersion> versionCaptor = ArgumentCaptor.forClass(ApplicationVersion.class);
        ArgumentCaptor<AuditLogEntry> auditCaptor = ArgumentCaptor.forClass(AuditLogEntry.class);
        InOrder order = inOrder(
                projectService, lifecycleAccessService, repository,
                candidateFactory, qualificationService, audits);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(projectService).requireProjectTenant(projectId);
        order.verify(lifecycleAccessService).requireActiveForWrite(ownerTenantId, projectId);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(repository).lockPublicationState(projectId, applicationId);
        order.verify(repository).findDraft(projectId, applicationId);
        order.verify(candidateFactory).prepareLocked(lockedDraft);
        order.verify(qualificationService).qualify(candidate);
        order.verify(repository).appendPublication(versionCaptor.capture(), eq(11L));
        order.verify(audits).record(auditCaptor.capture());
        verify(candidateFactory, never()).prepare(any());

        ApplicationVersion appended = versionCaptor.getValue();
        assertThat(published).isEqualTo(appended);
        assertThat(appended.tenantId()).isEqualTo(ownerTenantId);
        assertThat(appended.projectId()).isEqualTo(projectId);
        assertThat(appended.applicationId()).isEqualTo(applicationId);
        assertThat(appended.versionNumber()).isEqualTo(4);
        assertThat(appended.sourceDraftRevision()).isEqualTo(7);
        assertThat(appended.snapshot()).isEqualTo(candidate.snapshot());
        assertThat(appended.snapshotDigestAlgorithm()).isEqualTo(DIGEST_ALGORITHM);
        assertThat(appended.snapshotDigest()).isEqualTo(SNAPSHOT_DIGEST);
        assertThat(appended.publishedByAccountId()).isEqualTo(accountId);

        AuditLogEntry audit = auditCaptor.getValue();
        assertThat(audit.tenantId()).isEqualTo(ownerTenantId);
        assertThat(audit.projectId()).isEqualTo(projectId);
        assertThat(audit.actorAccountId()).isEqualTo(accountId);
        assertThat(audit.targetType()).isEqualTo("application");
        assertThat(audit.targetId()).isEqualTo(applicationId);
        assertThat(audit.action()).isEqualTo("application.published");
        assertThat(audit.details()).isEqualTo(Map.of(
                "versionId", appended.id().toString(),
                "versionNumber", "4",
                "sourceDraftRevision", "7",
                "publicationRevision", "12"));
    }

    /** 非管理角色必须在项目锁和任何发布事实读取之前拒绝。 */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ProjectRole.class, names = {"OPERATOR", "VIEWER"})
    @DisplayName("只读角色不能发布应用")
    void readOnlyRolesCannotPublish(ProjectRole role) {
        when(projectService.requireRoleInProject(projectId)).thenReturn(role);

        assertBusinessError(() -> service.publish(projectId, applicationId, "7", "11"),
                ApplicationErrorCode.APPLICATION_MANAGEMENT_FORBIDDEN);

        verify(lifecycleAccessService, never()).requireActiveForWrite(any(), any());
        verify(repository, never()).lockPublicationState(any(), any());
        verifyNoInteractions(candidateFactory, qualificationService, audits);
    }

    /** 项目锁等待中角色变化时锁后复核必须阻止应用行锁和候选准备。 */
    @Test
    @DisplayName("项目锁后角色复核失败停止应用发布")
    void roleRemovalWhileWaitingForProjectLockStopsPublication() {
        when(projectService.requireRoleInProject(projectId))
                .thenReturn(ProjectRole.OWNER, ProjectRole.VIEWER);

        assertBusinessError(() -> service.publish(projectId, applicationId, "7", "11"),
                ApplicationErrorCode.APPLICATION_MANAGEMENT_FORBIDDEN);

        verify(lifecycleAccessService).requireActiveForWrite(ownerTenantId, projectId);
        verify(repository, never()).lockPublicationState(any(), any());
        verifyNoInteractions(candidateFactory, qualificationService, audits);
    }

    /** 非规范revision不能进入项目许可或仓储，避免把请求解析失败误作并发冲突。 */
    @ParameterizedTest(name = "draft={0}, publication={1}")
    @CsvSource(value = {"NULL,11", "01,11", "+1,11", "7,NULL", "7,01", "7,9223372036854775808"},
            nullValues = "NULL")
    @DisplayName("非法双revision在加锁前稳定拒绝")
    void invalidRevisionSyntaxStopsBeforeAuthorization(String draftRevision, String publicationRevision) {
        assertBusinessError(() -> service.publish(
                projectId, applicationId, draftRevision, publicationRevision),
                ApplicationErrorCode.APPLICATION_PUBLICATION_INVALID);

        verifyNoInteractions(projectService, lifecycleAccessService, repository,
                candidateFactory, qualificationService, audits);
    }

    /** 任一调用方revision过期或计数耗尽均应在读取草稿、加看板锁和准备候选前停止。 */
    @ParameterizedTest(name = "expectedDraft={0}, expectedPublication={1}, actualDraft={2}, actualPublication={3}, latest={4}")
    @CsvSource({
            "6,11,7,11,3",
            "7,10,7,11,3",
            "7,9223372036854775807,7,9223372036854775807,3",
            "7,11,7,11,9223372036854775807"
    })
    @DisplayName("双revision冲突或计数耗尽在锁内短路")
    void staleRevisionOrExhaustedCounterStopsBeforeCandidateWork(
            String expectedDraftRevision,
            String expectedPublicationRevision,
            long actualDraftRevision,
            long actualPublicationRevision,
            long latestVersionNumber) {
        when(repository.lockPublicationState(projectId, applicationId)).thenReturn(Optional.of(
                state(actualDraftRevision, actualPublicationRevision, latestVersionNumber)));

        assertBusinessError(() -> service.publish(
                projectId, applicationId, expectedDraftRevision, expectedPublicationRevision),
                ApplicationErrorCode.APPLICATION_PUBLICATION_CONFLICT);

        verify(repository, never()).findDraft(any(), any());
        verifyNoInteractions(candidateFactory, qualificationService, audits);
        verify(repository, never()).appendPublication(any(), any(Long.class));
    }

    /** 锁状态返回另一应用身份时属于仓储内部完整性故障，不能按该状态分配版本号。 */
    @Test
    @DisplayName("应用锁状态身份漂移以内部故障停止")
    void lockedStateIdentityDriftStopsBeforeDraftRead() {
        when(repository.lockPublicationState(projectId, applicationId)).thenReturn(Optional.of(
                new ApplicationPublicationState(
                        UUID.randomUUID(), ownerTenantId, projectId, 7, 11,
                        UUID.randomUUID(), 3, null)));

        assertThatThrownBy(() -> service.publish(projectId, applicationId, "7", "11"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("应用锁状态身份与请求不一致");

        verify(repository, never()).findDraft(any(), any());
        verifyNoInteractions(candidateFactory, qualificationService, audits);
        verify(repository, never()).appendPublication(any(), any(Long.class));
    }

    /** 锁状态已经通过目录与草稿连接生成，随后草稿消失只能是仓储实现或持久完整性故障。 */
    @Test
    @DisplayName("锁状态存在但草稿缺失以内部故障停止")
    void missingDraftAfterLockedStateIsInternalFailure() {
        when(repository.lockPublicationState(projectId, applicationId))
                .thenReturn(Optional.of(state(7, 11, 3)));
        when(repository.findDraft(projectId, applicationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.publish(projectId, applicationId, "7", "11"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("应用锁状态存在但锁后草稿缺失");

        verifyNoInteractions(candidateFactory, qualificationService, audits);
        verify(repository, never()).appendPublication(any(), any(Long.class));
    }

    /** 应用锁状态和随后取得的草稿revision不一致属于内部漂移，不能伪装为客户端并发冲突。 */
    @Test
    @DisplayName("锁后草稿身份或revision漂移以内部故障停止")
    void lockedStateAndDraftMismatchStopsBeforeLockedCandidatePreparation() {
        when(repository.lockPublicationState(projectId, applicationId))
                .thenReturn(Optional.of(state(7, 11, 3)));
        when(repository.findDraft(projectId, applicationId)).thenReturn(Optional.of(draft(8)));

        assertThatThrownBy(() -> service.publish(projectId, applicationId, "7", "11"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("应用锁后草稿身份或revision与锁状态不一致");

        verify(candidateFactory, never()).prepareLocked(any());
        verifyNoInteractions(qualificationService, audits);
        verify(repository, never()).appendPublication(any(), any(Long.class));
    }

    /** 候选工厂不得把锁后草稿重定向到同项目另一应用，否则受控追加会绕过原应用锁。 */
    @Test
    @DisplayName("锁内候选身份漂移以内部故障停止")
    void candidateIdentityDriftStopsBeforeQualification() {
        when(repository.lockPublicationState(projectId, applicationId))
                .thenReturn(Optional.of(state(7, 11, 3)));
        when(repository.findDraft(projectId, applicationId)).thenReturn(Optional.of(lockedDraft));
        ApplicationPublicationCandidate drifted = candidate(new ApplicationDraft(
                UUID.randomUUID(), ownerTenantId, projectId, lockedDraft.content(), 7,
                accountId, lockedDraft.createdAt(), lockedDraft.updatedAt()));
        when(candidateFactory.prepareLocked(lockedDraft)).thenReturn(drifted);

        assertThatThrownBy(() -> service.publish(projectId, applicationId, "7", "11"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("应用发布候选身份或revision与锁后草稿不一致");

        verifyNoInteractions(qualificationService, audits);
        verify(repository, never()).appendPublication(any(), any(Long.class));
    }

    /** 资格结果必须封装刚核验的原候选实例，不能在宿主检查后换入另一份同身份快照。 */
    @Test
    @DisplayName("宿主资格替换候选实例以内部故障停止")
    void qualificationCandidateReplacementStopsBeforeAppend() {
        when(repository.lockPublicationState(projectId, applicationId))
                .thenReturn(Optional.of(state(7, 11, 3)));
        when(repository.findDraft(projectId, applicationId)).thenReturn(Optional.of(lockedDraft));
        when(candidateFactory.prepareLocked(lockedDraft)).thenReturn(candidate);
        ApplicationPublicationCandidate replacement = candidate(lockedDraft);
        when(qualificationService.qualify(candidate))
                .thenReturn(new QualifiedApplicationPublicationCandidate(replacement));

        assertThatThrownBy(() -> service.publish(projectId, applicationId, "7", "11"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("应用宿主资格未绑定锁内原候选实例");

        verify(repository, never()).appendPublication(any(), any(Long.class));
        verifyNoInteractions(audits);
    }

    /** 候选重建的全部已知合同拒绝都收敛为不泄漏内部引用的400并保持零写。 */
    @ParameterizedTest(name = "{0}")
    @EnumSource(ApplicationPublicationQualificationException.Reason.class)
    @DisplayName("锁内候选拒绝映射为安全应用发布条件错误")
    void candidateFailuresMapToSafePublicationInvalid(
            ApplicationPublicationQualificationException.Reason reason) {
        when(repository.lockPublicationState(projectId, applicationId))
                .thenReturn(Optional.of(state(7, 11, 3)));
        when(repository.findDraft(projectId, applicationId)).thenReturn(Optional.of(lockedDraft));
        when(candidateFactory.prepareLocked(lockedDraft))
                .thenThrow(new ApplicationPublicationQualificationException(reason));

        assertBusinessError(() -> service.publish(projectId, applicationId, "7", "11"),
                ApplicationErrorCode.APPLICATION_PUBLICATION_INVALID);

        verifyNoInteractions(qualificationService, audits);
        verify(repository, never()).appendPublication(any(), any(Long.class));
    }

    /** 宿主缺失使用503，其余宿主资格拒绝使用安全400，二者均不进入受控写入口。 */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("qualificationFailures")
    @DisplayName("宿主资格异常映射为稳定安全业务错误")
    void qualificationFailuresMapToStableBusinessErrors(
            ApplicationPublicationQualificationException.Reason reason,
            ApplicationErrorCode expectedError) {
        when(repository.lockPublicationState(projectId, applicationId))
                .thenReturn(Optional.of(state(7, 11, 3)));
        when(repository.findDraft(projectId, applicationId)).thenReturn(Optional.of(lockedDraft));
        when(candidateFactory.prepareLocked(lockedDraft)).thenReturn(candidate);
        when(qualificationService.qualify(candidate))
                .thenThrow(new ApplicationPublicationQualificationException(reason));

        assertBusinessError(() -> service.publish(projectId, applicationId, "7", "11"), expectedError);

        verify(repository, never()).appendPublication(any(), any(Long.class));
        verifyNoInteractions(audits);
    }

    /** 受控入口所有失败分类必须稳定映射且不得记录成功审计。 */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("appendFailures")
    @DisplayName("应用原子追加失败映射为稳定业务分类")
    void appendFailuresMapToStableBusinessErrors(
            ApplicationPublicationAppendResult result,
            ApplicationErrorCode expectedError) {
        stubQualifiedCandidate();
        when(repository.appendPublication(any(ApplicationVersion.class), eq(11L))).thenReturn(result);

        assertBusinessError(() -> service.publish(projectId, applicationId, "7", "11"), expectedError);

        verifyNoInteractions(audits);
    }

    /** 成功分类携带的版本号或publicationRevision漂移属于内部完整性故障，不能写审计掩盖。 */
    @ParameterizedTest(name = "version={0}, publication={1}")
    @CsvSource({"3,12", "4,13"})
    @DisplayName("受控成功结果必须精确匹配预分配双轴")
    void inconsistentPublishedResultFailsBeforeAudit(long versionNumber, long publicationRevision) {
        stubQualifiedCandidate();
        when(repository.appendPublication(any(ApplicationVersion.class), eq(11L)))
                .thenReturn(new ApplicationPublicationAppendResult(
                        ApplicationPublicationAppendResult.Status.PUBLISHED,
                        versionNumber, publicationRevision));

        assertThatThrownBy(() -> service.publish(projectId, applicationId, "7", "11"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("应用受控发布入口返回了非预期版本或发布revision");
        verifyNoInteractions(audits);
    }

    /** OWNER与ADMIN回滚只消费发布轴和不可变历史，不读取或改写当前草稿。 */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ProjectRole.class, names = {"OWNER", "ADMIN"})
    @DisplayName("管理角色重验历史应用版本后只切换指针")
    void managementRolesRollbackQualifiedHistoricalVersionWithoutReadingDraft(ProjectRole role) {
        UUID targetVersionId = UUID.randomUUID();
        ApplicationVersion target = version(targetVersionId, 2);
        when(projectService.requireRoleInProject(projectId)).thenReturn(role);
        stubRollbackCandidate(target);
        when(qualificationService.qualify(candidate))
                .thenReturn(new QualifiedApplicationPublicationCandidate(candidate));
        when(repository.rollbackPublication(
                eq(projectId), eq(applicationId), eq(targetVersionId), eq(11L),
                eq(accountId), any(Instant.class)))
                .thenReturn(new ApplicationPublicationRollbackResult(
                        ApplicationPublicationRollbackResult.Status.ROLLED_BACK, 12L));

        ApplicationVersion rolledBack = service.rollback(
                projectId, applicationId, targetVersionId, "11");

        assertThat(rolledBack).isEqualTo(target);
        ArgumentCaptor<AuditLogEntry> auditCaptor = ArgumentCaptor.forClass(AuditLogEntry.class);
        InOrder order = inOrder(
                projectService, lifecycleAccessService, repository,
                candidateFactory, qualificationService, audits);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(projectService).requireProjectTenant(projectId);
        order.verify(lifecycleAccessService).requireActiveForWrite(ownerTenantId, projectId);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(repository).lockPublicationState(projectId, applicationId);
        order.verify(repository).findVersion(projectId, applicationId, targetVersionId);
        order.verify(repository).findVersion(projectId, applicationId, uuid(31));
        order.verify(candidateFactory).prepareHistoricalVersionLocked(target);
        order.verify(qualificationService).qualify(candidate);
        order.verify(repository).rollbackPublication(
                eq(projectId), eq(applicationId), eq(targetVersionId), eq(11L),
                eq(accountId), any(Instant.class));
        order.verify(audits).record(auditCaptor.capture());
        verify(repository, never()).findDraft(any(), any());
        verify(repository, never()).appendPublication(any(), any(Long.class));

        AuditLogEntry audit = auditCaptor.getValue();
        assertThat(audit.targetType()).isEqualTo("application");
        assertThat(audit.targetId()).isEqualTo(applicationId);
        assertThat(audit.action()).isEqualTo("application.rolled_back");
        assertThat(audit.details()).isEqualTo(Map.of(
                "targetVersionId", targetVersionId.toString(),
                "targetVersionNumber", "2",
                "previousVersionId", uuid(31).toString(),
                "previousVersionNumber", "3",
                "publicationRevision", "12"));
    }

    /** 只读角色必须在项目锁、历史读取和资格核验前拒绝应用回滚。 */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ProjectRole.class, names = {"OPERATOR", "VIEWER"})
    @DisplayName("只读角色不能回滚应用")
    void readOnlyRolesCannotRollback(ProjectRole role) {
        when(projectService.requireRoleInProject(projectId)).thenReturn(role);

        assertBusinessError(() -> service.rollback(
                projectId, applicationId, UUID.randomUUID(), "11"),
                ApplicationErrorCode.APPLICATION_MANAGEMENT_FORBIDDEN);

        verify(lifecycleAccessService, never()).requireActiveForWrite(any(), any());
        verify(repository, never()).lockPublicationState(any(), any());
        verifyNoInteractions(candidateFactory, qualificationService, audits);
    }

    /** 非规范revision、陈旧revision、Long耗尽和当前目标都不能进入历史资格或受控写入口。 */
    @ParameterizedTest(name = "expected={0}, actual={1}, currentTarget={2}")
    @CsvSource(value = {
            "NULL,11,false", "01,11,false", "+1,11,false",
            "10,11,false", "11,9223372036854775807,false", "11,11,true"
    }, nullValues = "NULL")
    @DisplayName("应用回滚revision和当前目标冲突在历史重验前拒绝")
    void rollbackRevisionConflictStopsBeforeHistoricalQualification(
            String expectedRevision, long actualRevision, boolean currentTarget) {
        UUID targetVersionId = UUID.randomUUID();
        if (expectedRevision != null && expectedRevision.matches("0|[1-9][0-9]*")) {
            when(repository.lockPublicationState(projectId, applicationId)).thenReturn(Optional.of(
                    new ApplicationPublicationState(
                            applicationId, ownerTenantId, projectId, 7, actualRevision,
                            currentTarget ? targetVersionId : UUID.randomUUID(), 3, null)));
        }

        ApplicationErrorCode expectedError = expectedRevision == null
                || !expectedRevision.matches("0|[1-9][0-9]*")
                ? ApplicationErrorCode.APPLICATION_PUBLICATION_INVALID
                : ApplicationErrorCode.APPLICATION_PUBLICATION_CONFLICT;
        assertBusinessError(() -> service.rollback(
                projectId, applicationId, targetVersionId, expectedRevision), expectedError);

        verify(repository, never()).findVersion(any(), any(), any());
        verify(candidateFactory, never()).prepareHistoricalVersionLocked(any());
        verify(repository, never()).rollbackPublication(
                any(), any(), any(), any(Long.class), any(), any());
        verifyNoInteractions(qualificationService, audits);
    }

    /** 精确版本不存在、跨项目或跨应用必须统一为不泄漏归属的60046。 */
    @Test
    @DisplayName("应用回滚目标不可见映射为专用安全错误")
    void missingRollbackTargetMapsToSafeTargetNotFound() {
        UUID targetVersionId = UUID.randomUUID();
        when(repository.lockPublicationState(projectId, applicationId))
                .thenReturn(Optional.of(state(7, 11, 3)));
        when(repository.findVersion(projectId, applicationId, targetVersionId))
                .thenReturn(Optional.empty());

        assertBusinessError(() -> service.rollback(
                projectId, applicationId, targetVersionId, "11"),
                ApplicationErrorCode.APPLICATION_ROLLBACK_TARGET_NOT_FOUND);

        verify(candidateFactory, never()).prepareHistoricalVersionLocked(any());
        verifyNoInteractions(qualificationService, audits);
        verify(repository, never()).rollbackPublication(
                any(), any(), any(), any(Long.class), any(), any());
    }

    /** 历史快照或当前宿主资格拒绝沿既有60043/60045边界，且不写指针和审计。 */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("rollbackQualificationFailures")
    @DisplayName("应用回滚重验失败映射为稳定安全错误")
    void rollbackQualificationFailuresMapToStableBusinessErrors(
            ApplicationPublicationQualificationException.Reason reason,
            ApplicationErrorCode expectedError,
            boolean failDuringPreparation) {
        UUID targetVersionId = UUID.randomUUID();
        ApplicationVersion target = version(targetVersionId, 2);
        stubRollbackCandidate(target);
        if (failDuringPreparation) {
            when(candidateFactory.prepareHistoricalVersionLocked(target))
                    .thenThrow(new ApplicationPublicationQualificationException(reason));
        } else {
            when(qualificationService.qualify(candidate))
                    .thenThrow(new ApplicationPublicationQualificationException(reason));
        }

        assertBusinessError(() -> service.rollback(
                projectId, applicationId, targetVersionId, "11"), expectedError);

        verify(repository, never()).rollbackPublication(
                any(), any(), any(), any(Long.class), any(), any());
        verifyNoInteractions(audits);
    }

    /** 受控回滚入口的全部失败分类必须稳定映射且不误记成功审计。 */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("rollbackFailures")
    @DisplayName("应用受控回滚失败映射为稳定业务分类")
    void rollbackFailuresMapToStableBusinessErrors(
            ApplicationPublicationRollbackResult result,
            ApplicationErrorCode expectedError) {
        UUID targetVersionId = UUID.randomUUID();
        ApplicationVersion target = version(targetVersionId, 2);
        stubRollbackCandidate(target);
        when(qualificationService.qualify(candidate))
                .thenReturn(new QualifiedApplicationPublicationCandidate(candidate));
        when(repository.rollbackPublication(
                eq(projectId), eq(applicationId), eq(targetVersionId), eq(11L),
                eq(accountId), any(Instant.class))).thenReturn(result);

        assertBusinessError(() -> service.rollback(
                projectId, applicationId, targetVersionId, "11"), expectedError);

        verifyNoInteractions(audits);
    }

    /** OWNER与ADMIN撤回只读取锁内当前版本、清空指针并记录最小审计，不重跑任何发布资格。 */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ProjectRole.class, names = {"OWNER", "ADMIN"})
    @DisplayName("管理角色撤回当前应用版本并记录唯一审计")
    void managementRolesWithdrawCurrentVersionWithoutQualification(ProjectRole role) {
        UUID currentVersionId = uuid(31);
        ApplicationVersion current = version(currentVersionId, 3);
        when(projectService.requireRoleInProject(projectId)).thenReturn(role);
        stubWithdrawalCurrent(current);
        when(repository.withdrawPublication(
                eq(projectId), eq(applicationId), eq(11L), eq(accountId), any(Instant.class)))
                .thenReturn(new ApplicationPublicationWithdrawalResult(
                        ApplicationPublicationWithdrawalResult.Status.WITHDRAWN,
                        currentVersionId, 12L));

        ApplicationVersion withdrawn = service.withdraw(projectId, applicationId, "11");

        assertThat(withdrawn).isEqualTo(current);
        ArgumentCaptor<AuditLogEntry> auditCaptor = ArgumentCaptor.forClass(AuditLogEntry.class);
        InOrder order = inOrder(projectService, lifecycleAccessService, repository, audits);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(projectService).requireProjectTenant(projectId);
        order.verify(lifecycleAccessService).requireActiveForWrite(ownerTenantId, projectId);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(repository).lockPublicationState(projectId, applicationId);
        order.verify(repository).findVersion(projectId, applicationId, currentVersionId);
        order.verify(repository).withdrawPublication(
                eq(projectId), eq(applicationId), eq(11L), eq(accountId), any(Instant.class));
        order.verify(audits).record(auditCaptor.capture());
        verify(repository, never()).findDraft(any(), any());
        verify(repository, never()).appendPublication(any(), any(Long.class));
        verify(repository, never()).rollbackPublication(
                any(), any(), any(), any(Long.class), any(), any());
        verifyNoInteractions(candidateFactory, qualificationService);

        AuditLogEntry audit = auditCaptor.getValue();
        assertThat(audit.tenantId()).isEqualTo(ownerTenantId);
        assertThat(audit.projectId()).isEqualTo(projectId);
        assertThat(audit.actorAccountId()).isEqualTo(accountId);
        assertThat(audit.targetType()).isEqualTo("application");
        assertThat(audit.targetId()).isEqualTo(applicationId);
        assertThat(audit.action()).isEqualTo("application.withdrawn");
        assertThat(audit.details()).isEqualTo(Map.of(
                "previousVersionId", currentVersionId.toString(),
                "previousVersionNumber", "3",
                "publicationRevision", "12"));
    }

    /** OPERATOR与VIEWER必须在项目许可、应用锁和当前版本读取前拒绝撤回。 */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ProjectRole.class, names = {"OPERATOR", "VIEWER"})
    @DisplayName("只读角色不能撤回应用")
    void readOnlyRolesCannotWithdrawApplication(ProjectRole role) {
        when(projectService.requireRoleInProject(projectId)).thenReturn(role);

        assertBusinessError(() -> service.withdraw(projectId, applicationId, "11"),
                ApplicationErrorCode.APPLICATION_MANAGEMENT_FORBIDDEN);

        verify(lifecycleAccessService, never()).requireActiveForWrite(any(), any());
        verify(repository, never()).lockPublicationState(any(), any());
        verifyNoInteractions(candidateFactory, qualificationService, audits);
    }

    /** ARCHIVED项目的既有50017必须原样终止撤回，不能被应用发布错误覆盖。 */
    @Test
    @DisplayName("归档项目不能撤回应用")
    void archivedProjectCannotWithdrawApplication() {
        BusinessException archived = new BusinessException(ProjectErrorCode.PROJECT_READ_ONLY);
        doThrow(archived).when(lifecycleAccessService)
                .requireActiveForWrite(ownerTenantId, projectId);

        assertThatThrownBy(() -> service.withdraw(projectId, applicationId, "11"))
                .isSameAs(archived);

        verify(repository, never()).lockPublicationState(any(), any());
        verifyNoInteractions(candidateFactory, qualificationService, audits);
    }

    /** 应用不存在、跨项目或已软删在授权后统一隐藏为60030，不能读取当前版本。 */
    @Test
    @DisplayName("不可见应用不能撤回")
    void invisibleApplicationCannotBeWithdrawn() {
        when(repository.lockPublicationState(projectId, applicationId)).thenReturn(Optional.empty());

        assertBusinessError(() -> service.withdraw(projectId, applicationId, "11"),
                ApplicationErrorCode.APPLICATION_NOT_FOUND);

        verify(repository, never()).findVersion(any(), any(), any());
        verify(repository, never()).withdrawPublication(any(), any(), any(Long.class), any(), any());
        verifyNoInteractions(candidateFactory, qualificationService, audits);
    }

    /** 非规范、陈旧、Long耗尽和空指针均须在当前版本读取前停止且不制造状态变化。 */
    @ParameterizedTest(name = "expected={0}, actual={1}, current={2}")
    @CsvSource(value = {
            "NULL,11,present", "01,11,present", "+1,11,present",
            "10,11,present", "11,9223372036854775807,present", "11,11,null"
    }, nullValues = "NULL")
    @DisplayName("应用撤回非法或冲突发布轴不读取当前版本")
    void withdrawalRevisionOrStateConflictStopsBeforeCurrentVersionRead(
            String expectedRevision, long actualRevision, String currentMarker) {
        if (expectedRevision != null && expectedRevision.matches("0|[1-9][0-9]*")) {
            UUID currentVersionId = "present".equals(currentMarker) ? uuid(31) : null;
            when(repository.lockPublicationState(projectId, applicationId)).thenReturn(Optional.of(
                    new ApplicationPublicationState(
                            applicationId, ownerTenantId, projectId, 7, actualRevision,
                            currentVersionId, 3, null)));
        }

        ApplicationErrorCode expectedError = expectedRevision == null
                || !expectedRevision.matches("0|[1-9][0-9]*")
                ? ApplicationErrorCode.APPLICATION_PUBLICATION_INVALID
                : ApplicationErrorCode.APPLICATION_PUBLICATION_CONFLICT;
        assertBusinessError(() -> service.withdraw(
                projectId, applicationId, expectedRevision), expectedError);

        verify(repository, never()).findVersion(any(), any(), any());
        verify(repository, never()).withdrawPublication(any(), any(), any(Long.class), any(), any());
        verifyNoInteractions(candidateFactory, qualificationService, audits);
    }

    /** 受控撤回入口的失败分类必须映射为不可见或发布冲突，且不得记录成功审计。 */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("withdrawalFailures")
    @DisplayName("应用受控撤回失败映射为稳定业务分类")
    void withdrawalFailuresMapToStableBusinessErrors(
            ApplicationPublicationWithdrawalResult result,
            ApplicationErrorCode expectedError) {
        ApplicationVersion current = version(uuid(31), 3);
        stubWithdrawalCurrent(current);
        when(repository.withdrawPublication(
                eq(projectId), eq(applicationId), eq(11L), eq(accountId), any(Instant.class)))
                .thenReturn(result);

        assertBusinessError(() -> service.withdraw(projectId, applicationId, "11"), expectedError);

        verifyNoInteractions(candidateFactory, qualificationService, audits);
    }

    /** 成功分类的原版本或提交后revision漂移属于内部故障，不能写审计掩盖。 */
    @ParameterizedTest(name = "previous={0}, publication={1}")
    @CsvSource({"00000000-0000-0000-0000-000000000032,12", "00000000-0000-0000-0000-000000000031,13"})
    @DisplayName("应用撤回成功结果必须匹配锁内原版本和单步revision")
    void inconsistentWithdrawalSuccessFailsBeforeAudit(UUID previousVersionId, long publicationRevision) {
        ApplicationVersion current = version(uuid(31), 3);
        stubWithdrawalCurrent(current);
        when(repository.withdrawPublication(
                eq(projectId), eq(applicationId), eq(11L), eq(accountId), any(Instant.class)))
                .thenReturn(new ApplicationPublicationWithdrawalResult(
                        ApplicationPublicationWithdrawalResult.Status.WITHDRAWN,
                        previousVersionId, publicationRevision));

        assertThatThrownBy(() -> service.withdraw(projectId, applicationId, "11"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("应用受控撤回入口返回了非预期原版本或发布revision");
        verifyNoInteractions(audits);
    }

    /** OWNER与ADMIN无论当前指针是否存在都可软删一次，并记录允许空原版本的完整最小审计。 */
    @ParameterizedTest(name = "{0}, current={1}")
    @MethodSource("applicationSoftDeleteSuccessCases")
    @DisplayName("管理角色软删已发布或已撤回应用")
    void managementRolesSoftDeleteWithOrWithoutCurrentVersion(
            ProjectRole role, boolean hasCurrentVersion) {
        UUID currentVersionId = hasCurrentVersion ? uuid(31) : null;
        ApplicationVersion current = hasCurrentVersion ? version(currentVersionId, 3) : null;
        when(projectService.requireRoleInProject(projectId)).thenReturn(role);
        when(repository.lockPublicationState(projectId, applicationId))
                .thenReturn(Optional.of(new ApplicationPublicationState(
                        applicationId, ownerTenantId, projectId, 7, 11,
                        currentVersionId, 3, null)));
        if (hasCurrentVersion) {
            when(repository.findVersion(projectId, applicationId, currentVersionId))
                    .thenReturn(Optional.of(current));
        }
        when(repository.softDelete(
                eq(projectId), eq(applicationId), eq(11L), eq(accountId), any(Instant.class)))
                .thenAnswer(invocation -> new ApplicationSoftDeleteResult(
                        ApplicationSoftDeleteResult.Status.DELETED,
                        currentVersionId, 12L, invocation.getArgument(4, Instant.class)));

        service.softDelete(projectId, applicationId, "11");

        ArgumentCaptor<AuditLogEntry> auditCaptor = ArgumentCaptor.forClass(AuditLogEntry.class);
        InOrder order = inOrder(projectService, lifecycleAccessService, repository, audits);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(projectService).requireProjectTenant(projectId);
        order.verify(lifecycleAccessService).requireActiveForWrite(ownerTenantId, projectId);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(repository).lockPublicationState(projectId, applicationId);
        if (hasCurrentVersion) {
            order.verify(repository).findVersion(projectId, applicationId, currentVersionId);
        }
        order.verify(repository).softDelete(
                eq(projectId), eq(applicationId), eq(11L), eq(accountId), any(Instant.class));
        order.verify(audits).record(auditCaptor.capture());
        if (!hasCurrentVersion) {
            verify(repository, never()).findVersion(any(), any(), any());
        }
        verify(repository, never()).findDraft(any(), any());
        verifyNoInteractions(candidateFactory, qualificationService);

        AuditLogEntry audit = auditCaptor.getValue();
        assertThat(audit.tenantId()).isEqualTo(ownerTenantId);
        assertThat(audit.projectId()).isEqualTo(projectId);
        assertThat(audit.actorAccountId()).isEqualTo(accountId);
        assertThat(audit.targetType()).isEqualTo("application");
        assertThat(audit.targetId()).isEqualTo(applicationId);
        assertThat(audit.action()).isEqualTo("application.deleted");
        assertThat(audit.details()).hasSize(4);
        assertThat(audit.details().get("previousVersionId"))
                .isEqualTo(hasCurrentVersion ? currentVersionId.toString() : null);
        assertThat(audit.details().get("previousVersionNumber"))
                .isEqualTo(hasCurrentVersion ? "3" : null);
        assertThat(audit.details().get("publicationRevision")).isEqualTo("12");
        assertThat(audit.details().get("deletedAt")).isNotNull();
    }

    /** OPERATOR与VIEWER必须在项目许可、应用锁和受控入口前拒绝软删。 */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ProjectRole.class, names = {"OPERATOR", "VIEWER"})
    @DisplayName("只读角色不能软删应用")
    void readOnlyRolesCannotSoftDeleteApplication(ProjectRole role) {
        when(projectService.requireRoleInProject(projectId)).thenReturn(role);

        assertBusinessError(() -> service.softDelete(projectId, applicationId, "11"),
                ApplicationErrorCode.APPLICATION_MANAGEMENT_FORBIDDEN);

        verify(lifecycleAccessService, never()).requireActiveForWrite(any(), any());
        verify(repository, never()).lockPublicationState(any(), any());
        verify(repository, never()).softDelete(any(), any(), any(Long.class), any(), any());
        verifyNoInteractions(candidateFactory, qualificationService, audits);
    }

    /** ARCHIVED项目的既有50017必须原样终止软删，不能被应用发布错误覆盖。 */
    @Test
    @DisplayName("归档项目不能软删应用")
    void archivedProjectCannotSoftDeleteApplication() {
        BusinessException archived = new BusinessException(ProjectErrorCode.PROJECT_READ_ONLY);
        doThrow(archived).when(lifecycleAccessService)
                .requireActiveForWrite(ownerTenantId, projectId);

        assertThatThrownBy(() -> service.softDelete(projectId, applicationId, "11"))
                .isSameAs(archived);

        verify(repository, never()).lockPublicationState(any(), any());
        verify(repository, never()).softDelete(any(), any(), any(Long.class), any(), any());
        verifyNoInteractions(candidateFactory, qualificationService, audits);
    }

    /** 不存在、跨项目或带deletedAt的重复删除目标均统一隐藏为60030且不读取历史。 */
    @ParameterizedTest(name = "persistedDeleted={0}")
    @CsvSource({"false", "true"})
    @DisplayName("不可见或已删除应用不能重复软删")
    void invisibleOrDeletedApplicationCannotBeSoftDeleted(boolean persistedDeleted) {
        if (persistedDeleted) {
            when(repository.lockPublicationState(projectId, applicationId))
                    .thenReturn(Optional.of(new ApplicationPublicationState(
                            applicationId, ownerTenantId, projectId, 7, 12,
                            null, 3, Instant.parse("2026-09-06T12:00:00Z"))));
        } else {
            when(repository.lockPublicationState(projectId, applicationId)).thenReturn(Optional.empty());
        }

        assertBusinessError(() -> service.softDelete(projectId, applicationId, "12"),
                ApplicationErrorCode.APPLICATION_NOT_FOUND);

        verify(repository, never()).findVersion(any(), any(), any());
        verify(repository, never()).softDelete(any(), any(), any(Long.class), any(), any());
        verifyNoInteractions(candidateFactory, qualificationService, audits);
    }

    /** 非规范、陈旧和Long耗尽发布轴均须在当前版本读取前停止且不制造删除事实。 */
    @ParameterizedTest(name = "expected={0}, actual={1}")
    @CsvSource(value = {
            "NULL,11", "01,11", "+1,11", "10,11", "11,9223372036854775807"
    }, nullValues = "NULL")
    @DisplayName("应用软删非法或冲突发布轴不读取当前版本")
    void softDeleteRevisionConflictStopsBeforeCurrentVersionRead(
            String expectedRevision, long actualRevision) {
        if (expectedRevision != null && expectedRevision.matches("0|[1-9][0-9]*")) {
            when(repository.lockPublicationState(projectId, applicationId))
                    .thenReturn(Optional.of(new ApplicationPublicationState(
                            applicationId, ownerTenantId, projectId, 7, actualRevision,
                            uuid(31), 3, null)));
        }

        ApplicationErrorCode expectedError = expectedRevision == null
                || !expectedRevision.matches("0|[1-9][0-9]*")
                ? ApplicationErrorCode.APPLICATION_PUBLICATION_INVALID
                : ApplicationErrorCode.APPLICATION_PUBLICATION_CONFLICT;
        assertBusinessError(() -> service.softDelete(
                projectId, applicationId, expectedRevision), expectedError);

        verify(repository, never()).findVersion(any(), any(), any());
        verify(repository, never()).softDelete(any(), any(), any(Long.class), any(), any());
        verifyNoInteractions(candidateFactory, qualificationService, audits);
    }

    /** 受控软删入口全部失败分类必须映射为不可见或发布冲突，且不得误记成功审计。 */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("applicationSoftDeleteFailures")
    @DisplayName("应用受控软删失败映射为稳定业务分类")
    void applicationSoftDeleteFailuresMapToStableBusinessErrors(
            ApplicationSoftDeleteResult result,
            ApplicationErrorCode expectedError) {
        when(repository.lockPublicationState(projectId, applicationId))
                .thenReturn(Optional.of(new ApplicationPublicationState(
                        applicationId, ownerTenantId, projectId, 7, 11,
                        null, 3, null)));
        when(repository.softDelete(
                eq(projectId), eq(applicationId), eq(11L), eq(accountId), any(Instant.class)))
                .thenReturn(result);

        assertBusinessError(() -> service.softDelete(projectId, applicationId, "11"), expectedError);

        verifyNoInteractions(candidateFactory, qualificationService, audits);
    }

    /** 成功分类的原指针、单步revision或删除时刻漂移属于内部故障，不能写审计掩盖。 */
    @ParameterizedTest(name = "drift={0}")
    @CsvSource({"previous", "revision", "timestamp"})
    @DisplayName("应用软删成功结果必须匹配锁内删除事实")
    void inconsistentSoftDeleteSuccessFailsBeforeAudit(String drift) {
        UUID currentVersionId = uuid(31);
        ApplicationVersion current = version(currentVersionId, 3);
        stubWithdrawalCurrent(current);
        when(repository.softDelete(
                eq(projectId), eq(applicationId), eq(11L), eq(accountId), any(Instant.class)))
                .thenAnswer(invocation -> {
                    Instant deletedAt = invocation.getArgument(4, Instant.class);
                    return new ApplicationSoftDeleteResult(
                            ApplicationSoftDeleteResult.Status.DELETED,
                            "previous".equals(drift) ? uuid(32) : currentVersionId,
                            "revision".equals(drift) ? 13L : 12L,
                            "timestamp".equals(drift) ? deletedAt.plusSeconds(1) : deletedAt);
                });

        assertThatThrownBy(() -> service.softDelete(projectId, applicationId, "11"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("应用受控软删入口返回了非预期删除事实");
        verifyNoInteractions(audits);
    }

    /** @return 宿主资格拒绝原因与公开稳定分类。 */
    private static Stream<Arguments> qualificationFailures() {
        return Stream.of(
                Arguments.of(ApplicationPublicationQualificationException.Reason.HOST_UNAVAILABLE,
                        ApplicationErrorCode.APPLICATION_PUBLICATION_DEPENDENCY_UNAVAILABLE),
                Arguments.of(ApplicationPublicationQualificationException.Reason.AGGREGATE_REQUIREMENT_INVALID,
                        ApplicationErrorCode.APPLICATION_PUBLICATION_INVALID));
    }

    /** @return 历史候选与宿主两层资格拒绝及其公开错误。 */
    private static Stream<Arguments> rollbackQualificationFailures() {
        return Stream.of(
                Arguments.of(ApplicationPublicationQualificationException.Reason.DASHBOARD_NOT_RUNNABLE,
                        ApplicationErrorCode.APPLICATION_PUBLICATION_INVALID, true),
                Arguments.of(ApplicationPublicationQualificationException.Reason.HOST_UNAVAILABLE,
                        ApplicationErrorCode.APPLICATION_PUBLICATION_DEPENDENCY_UNAVAILABLE, false));
    }

    /** @return 受控应用回滚失败分类及其稳定公开错误。 */
    private static Stream<Arguments> rollbackFailures() {
        return Stream.of(
                Arguments.of(new ApplicationPublicationRollbackResult(
                                ApplicationPublicationRollbackResult.Status.NOT_FOUND, null),
                        ApplicationErrorCode.APPLICATION_NOT_FOUND),
                Arguments.of(new ApplicationPublicationRollbackResult(
                                ApplicationPublicationRollbackResult.Status.TARGET_NOT_FOUND, null),
                        ApplicationErrorCode.APPLICATION_ROLLBACK_TARGET_NOT_FOUND),
                Arguments.of(new ApplicationPublicationRollbackResult(
                                ApplicationPublicationRollbackResult.Status.PUBLICATION_CONFLICT, 12L),
                        ApplicationErrorCode.APPLICATION_PUBLICATION_CONFLICT),
                Arguments.of(new ApplicationPublicationRollbackResult(
                                ApplicationPublicationRollbackResult.Status.REVISION_EXHAUSTED, 11L),
                        ApplicationErrorCode.APPLICATION_PUBLICATION_CONFLICT),
                Arguments.of(new ApplicationPublicationRollbackResult(
                                ApplicationPublicationRollbackResult.Status.DASHBOARD_REFERENCE_INVALID, 11L),
                        ApplicationErrorCode.APPLICATION_PUBLICATION_INVALID),
                Arguments.of(new ApplicationPublicationRollbackResult(
                                ApplicationPublicationRollbackResult.Status.DASHBOARD_NOT_RUNNABLE, 11L),
                        ApplicationErrorCode.APPLICATION_PUBLICATION_INVALID));
    }

    /** @return 受控应用撤回失败分类及其稳定公开错误。 */
    private static Stream<Arguments> withdrawalFailures() {
        return Stream.of(
                Arguments.of(new ApplicationPublicationWithdrawalResult(
                                ApplicationPublicationWithdrawalResult.Status.NOT_FOUND, null, null),
                        ApplicationErrorCode.APPLICATION_NOT_FOUND),
                Arguments.of(new ApplicationPublicationWithdrawalResult(
                                ApplicationPublicationWithdrawalResult.Status.NOT_PUBLISHED, null, 11L),
                        ApplicationErrorCode.APPLICATION_PUBLICATION_CONFLICT),
                Arguments.of(new ApplicationPublicationWithdrawalResult(
                                ApplicationPublicationWithdrawalResult.Status.PUBLICATION_CONFLICT, null, 12L),
                        ApplicationErrorCode.APPLICATION_PUBLICATION_CONFLICT),
                Arguments.of(new ApplicationPublicationWithdrawalResult(
                                ApplicationPublicationWithdrawalResult.Status.REVISION_EXHAUSTED, null, 11L),
                        ApplicationErrorCode.APPLICATION_PUBLICATION_CONFLICT));
    }

    /** @return 管理角色与应用有无当前指针的四种软删成功组合。 */
    private static Stream<Arguments> applicationSoftDeleteSuccessCases() {
        return Stream.of(
                Arguments.of(ProjectRole.OWNER, true),
                Arguments.of(ProjectRole.OWNER, false),
                Arguments.of(ProjectRole.ADMIN, true),
                Arguments.of(ProjectRole.ADMIN, false));
    }

    /** @return 受控应用软删失败分类及其稳定公开错误。 */
    private static Stream<Arguments> applicationSoftDeleteFailures() {
        return Stream.of(
                Arguments.of(new ApplicationSoftDeleteResult(
                                ApplicationSoftDeleteResult.Status.NOT_FOUND, null, null, null),
                        ApplicationErrorCode.APPLICATION_NOT_FOUND),
                Arguments.of(new ApplicationSoftDeleteResult(
                                ApplicationSoftDeleteResult.Status.PUBLICATION_CONFLICT,
                                null, 12L, null),
                        ApplicationErrorCode.APPLICATION_PUBLICATION_CONFLICT),
                Arguments.of(new ApplicationSoftDeleteResult(
                                ApplicationSoftDeleteResult.Status.REVISION_EXHAUSTED,
                                null, 11L, null),
                        ApplicationErrorCode.APPLICATION_PUBLICATION_CONFLICT));
    }

    /** @return 受控应用追加失败分类与公开稳定分类。 */
    private static Stream<Arguments> appendFailures() {
        return Stream.of(
                Arguments.of(new ApplicationPublicationAppendResult(
                                ApplicationPublicationAppendResult.Status.NOT_FOUND, null, null),
                        ApplicationErrorCode.APPLICATION_NOT_FOUND),
                Arguments.of(new ApplicationPublicationAppendResult(
                                ApplicationPublicationAppendResult.Status.DRAFT_CONFLICT, null, 7L),
                        ApplicationErrorCode.APPLICATION_PUBLICATION_CONFLICT),
                Arguments.of(new ApplicationPublicationAppendResult(
                                ApplicationPublicationAppendResult.Status.PUBLICATION_CONFLICT, null, 12L),
                        ApplicationErrorCode.APPLICATION_PUBLICATION_CONFLICT),
                Arguments.of(new ApplicationPublicationAppendResult(
                                ApplicationPublicationAppendResult.Status.REVISION_EXHAUSTED, null, null),
                        ApplicationErrorCode.APPLICATION_PUBLICATION_CONFLICT),
                Arguments.of(new ApplicationPublicationAppendResult(
                                ApplicationPublicationAppendResult.Status.DASHBOARD_REFERENCE_INVALID, null, null),
                        ApplicationErrorCode.APPLICATION_PUBLICATION_INVALID),
                Arguments.of(new ApplicationPublicationAppendResult(
                                ApplicationPublicationAppendResult.Status.DASHBOARD_NOT_RUNNABLE, null, null),
                        ApplicationErrorCode.APPLICATION_PUBLICATION_INVALID));
    }

    /** 配置应用锁、锁内草稿、看板锁内候选及宿主资格的成功公共路径。 */
    private void stubQualifiedCandidate() {
        when(repository.lockPublicationState(projectId, applicationId))
                .thenReturn(Optional.of(state(7, 11, 3)));
        when(repository.findDraft(projectId, applicationId)).thenReturn(Optional.of(lockedDraft));
        when(candidateFactory.prepareLocked(lockedDraft)).thenReturn(candidate);
        when(qualificationService.qualify(candidate))
                .thenReturn(new QualifiedApplicationPublicationCandidate(candidate));
    }

    /** 配置锁内应用状态、精确历史版本和从该版本重建的同一候选。 */
    private void stubRollbackCandidate(ApplicationVersion target) {
        UUID currentVersionId = uuid(31);
        when(repository.lockPublicationState(projectId, applicationId))
                .thenReturn(Optional.of(new ApplicationPublicationState(
                        applicationId, ownerTenantId, projectId, 7, 11,
                        currentVersionId, 3, null)));
        when(repository.findVersion(projectId, applicationId, target.id()))
                .thenReturn(Optional.of(target));
        when(repository.findVersion(projectId, applicationId, currentVersionId))
                .thenReturn(Optional.of(version(currentVersionId, 3)));
        when(candidateFactory.prepareHistoricalVersionLocked(target)).thenReturn(candidate);
    }

    /** 配置锁内非空当前指针及其同应用不可变版本，供纯撤回路径复用。 */
    private void stubWithdrawalCurrent(ApplicationVersion current) {
        when(repository.lockPublicationState(projectId, applicationId))
                .thenReturn(Optional.of(new ApplicationPublicationState(
                        applicationId, ownerTenantId, projectId, 7, 11,
                        current.id(), current.versionNumber(), null)));
        when(repository.findVersion(projectId, applicationId, current.id()))
                .thenReturn(Optional.of(current));
    }

    /** @return 与当前候选逐字段一致的指定历史版本。 */
    private ApplicationVersion version(UUID versionId, long versionNumber) {
        return new ApplicationVersion(
                versionId, ownerTenantId, projectId, applicationId, versionNumber,
                candidate.sourceDraftRevision(), candidate.snapshot(),
                candidate.snapshotDigestAlgorithm(), candidate.snapshotDigest(), accountId,
                Instant.parse("2026-09-06T12:00:00Z"));
    }

    /** @return 指定revision的合法持久应用草稿。 */
    private ApplicationDraft draft(long revision) {
        Instant timestamp = Instant.parse("2026-09-06T12:00:00Z");
        return new ApplicationDraft(
                applicationId, ownerTenantId, projectId, applicationDocument(), revision,
                accountId, timestamp, timestamp);
    }

    /** @return 从锁内草稿形成且包含两项保序精确关系的最小候选。 */
    private ApplicationPublicationCandidate candidate(ApplicationDraft draft) {
        UUID dashboardB = uuid(12);
        UUID dashboardA = uuid(11);
        UUID versionB = uuid(22);
        UUID versionA = uuid(21);
        ObjectNode snapshot = applicationDocument();
        ArrayNode references = snapshot.putArray("dashboardRefs");
        references.add(reference(dashboardB, versionB, "导航乙"));
        references.add(reference(dashboardA, versionA, "导航甲"));
        snapshot.put("entryDashboardId", dashboardB.toString());
        return new ApplicationPublicationCandidate(
                draft.applicationId(), draft.tenantId(), draft.projectId(), draft.revision(),
                snapshot, snapshot.toString(), DIGEST_ALGORITHM, SNAPSHOT_DIGEST,
                "1.0.0", "2.0.0",
                List.of(
                        new ApplicationVersionDashboardReference(0, dashboardB, versionB),
                        new ApplicationVersionDashboardReference(1, dashboardA, versionA)),
                List.of("tc.dashboard/v1"), List.of(), List.of());
    }

    /** @return 指定三条版本轴且未删除的应用状态。 */
    private ApplicationPublicationState state(
            long draftRevision, long publicationRevision, long latestVersionNumber) {
        return new ApplicationPublicationState(
                applicationId, ownerTenantId, projectId, draftRevision, publicationRevision,
                publicationRevision == 0 ? null : UUID.randomUUID(), latestVersionNumber, null);
    }

    /** 构造合法应用文档。 */
    private static ObjectNode applicationDocument() {
        ObjectNode content = JSON.createObjectNode()
                .put("formatVersion", "tc.application/v1")
                .put("displayName", "测试应用");
        content.putObject("hostCompatibility")
                .put("minInclusive", "1.0.0")
                .put("maxExclusive", "2.0.0");
        content.putArray("dashboardRefs");
        content.putNull("entryDashboardId");
        return content;
    }

    /** 构造一个快照精确看板引用。 */
    private static ObjectNode reference(UUID dashboardId, UUID versionId, String title) {
        ObjectNode value = JSON.createObjectNode()
                .put("dashboardId", dashboardId.toString())
                .put("dashboardVersionId", versionId.toString())
                .put("dashboardVersionNumber", "1")
                .put("title", title)
                .put("schemaVersion", "tc.dashboard/v1")
                .put("schemaDigestAlgorithm", "PG_JSONB_TEXT_V1_SHA256")
                .put("schemaDigest", "b".repeat(64));
        value.putArray("pages").addObject().put("id", "main").put("title", "总览");
        return value;
    }

    /** 断言公开错误码且不携带内部资格明细。 */
    private static void assertBusinessError(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            ApplicationErrorCode expectedError) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, failure -> {
            assertThat(failure.errorCode()).isEqualTo(expectedError);
            assertThat(failure.details()).isEmpty();
        });
    }

    /** 构造可读确定UUID。 */
    private static UUID uuid(int value) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(value));
    }
}
