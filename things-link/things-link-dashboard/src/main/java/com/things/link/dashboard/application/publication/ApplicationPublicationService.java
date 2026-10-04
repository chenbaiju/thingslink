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
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 在应用锁内受控发布、回滚、撤回或软删不可变应用版本。
 *
 * <p>S12-1c2至S12-1c5只开放无HTTP管理用例。发布与回滚还会由候选工厂按稳定看板ID顺序持锁重建资格，
 * 撤回与软删不生成候选；所有状态命令都在同一事务推进publicationRevision并写审计。</p>
 */
@Service
public class ApplicationPublicationService {

    /** Revision只接受零或无前导零的规范十进制Long文本。 */
    private static final Pattern REVISION_PATTERN = Pattern.compile("0|[1-9][0-9]*");

    /** 应用状态、草稿、版本与受控发布状态写入口。 */
    private final ApplicationRepository repository;
    /** 项目角色与真实owner tenant公开端口。 */
    private final ProjectService projectService;
    /** 在原事务内持有ACTIVE项目写许可的公开端口。 */
    private final ProjectLifecycleAccessService lifecycleAccessService;
    /** 在应用和看板锁内重建完整候选的工厂。 */
    private final ApplicationPublicationCandidateFactory candidateFactory;
    /** 对同一受管宿主快照执行完整应用资格的服务。 */
    private final ApplicationPublicationQualificationService qualificationService;
    /** 与版本、关系或指针变化共用事务的持久审计端口。 */
    private final AuditLogService auditLogService;

    /**
     * 创建应用发布服务。
     *
     * @param repository 应用聚合持久端口
     * @param projectService 项目授权和归属端口
     * @param lifecycleAccessService 项目持续写许可端口
     * @param candidateFactory 锁内应用候选工厂
     * @param qualificationService 当前宿主资格服务
     * @param auditLogService 同事务审计端口
     */
    public ApplicationPublicationService(
            ApplicationRepository repository,
            ProjectService projectService,
            ProjectLifecycleAccessService lifecycleAccessService,
            ApplicationPublicationCandidateFactory candidateFactory,
            ApplicationPublicationQualificationService qualificationService,
            AuditLogService auditLogService) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.projectService = Objects.requireNonNull(projectService, "projectService");
        this.lifecycleAccessService = Objects.requireNonNull(
                lifecycleAccessService, "lifecycleAccessService");
        this.candidateFactory = Objects.requireNonNull(candidateFactory, "candidateFactory");
        this.qualificationService = Objects.requireNonNull(qualificationService, "qualificationService");
        this.auditLogService = Objects.requireNonNull(auditLogService, "auditLogService");
    }

    /**
     * 把指定应用草稿发布为新的不可变版本。
     *
     * @param projectId 已选定项目ID
     * @param applicationId 应用内部ID
     * @param expectedDraftRevision 调用方读取的草稿revision规范十进制文本
     * @param expectedPublicationRevision 调用方读取的发布revision规范十进制文本
     * @return 已原子发布且版本号与数据库事实一致的新应用版本
     */
    @Transactional
    public ApplicationVersion publish(
            UUID projectId,
            UUID applicationId,
            String expectedDraftRevision,
            String expectedPublicationRevision) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(applicationId, "applicationId");
        long draftRevision = requireRevision(expectedDraftRevision);
        long publicationRevision = requireRevision(expectedPublicationRevision);
        UUID ownerTenantId = requireWrite(projectId);

        // 应用目录和草稿先于任何看板锁取得；所有应用发布均沿同一方向，避免多应用交叉引用形成锁环。
        ApplicationPublicationState state = repository.lockPublicationState(projectId, applicationId)
                .filter(value -> value.deletedAt() == null)
                .orElseThrow(ApplicationPublicationService::notFound);
        requireStateIdentity(state, ownerTenantId, projectId, applicationId);
        if (state.draftRevision() != draftRevision
                || state.publicationRevision() != publicationRevision
                || state.publicationRevision() == Long.MAX_VALUE
                || state.latestVersionNumber() == Long.MAX_VALUE) {
            throw publicationConflict();
        }

        ApplicationDraft draft = repository.findDraft(projectId, applicationId)
                .orElseThrow(() -> new IllegalStateException("应用锁状态存在但锁后草稿缺失"));
        requireDraftIdentity(state, draft);
        ApplicationPublicationCandidate candidate = prepareCandidate(draft);
        requireCandidateIdentity(draft, candidate);
        QualifiedApplicationPublicationCandidate qualified = qualify(candidate);
        if (qualified.candidate() != candidate) {
            throw new IllegalStateException("应用宿主资格未绑定锁内原候选实例");
        }
        requireCandidateIdentity(draft, qualified.candidate());
        ApplicationVersion version = toVersion(
                qualified.candidate(), state.latestVersionNumber() + 1,
                currentAccountId(), databaseTimestamp());
        ApplicationPublicationAppendResult append = repository.appendPublication(version, publicationRevision);
        requirePublished(append, version, publicationRevision);
        long committedPublicationRevision = append.observedPublicationRevision().orElseThrow();
        auditPublication(ownerTenantId, projectId, version, committedPublicationRevision);
        return version;
    }

    /**
     * 把应用发布指针切回重新通过当前看板与宿主资格的既有不可变版本。
     *
     * <p>S12-1c3只消费publicationRevision，不读取或改写当前草稿，也不创建新版本。目标ApplicationSnapshot
     * 中的精确看板版本按稳定ID锁序重新派生并核对，事务外历史资格不能直接复用。</p>
     *
     * @param projectId 已选定项目ID
     * @param applicationId 应用内部ID
     * @param targetVersionId 目标不可变应用版本ID
     * @param expectedPublicationRevision 调用方读取的发布revision规范十进制文本
     * @return 内容未改写且已成为当前指针的目标历史版本
     */
    @Transactional
    public ApplicationVersion rollback(
            UUID projectId,
            UUID applicationId,
            UUID targetVersionId,
            String expectedPublicationRevision) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(targetVersionId, "targetVersionId");
        long publicationRevision = requireRevision(expectedPublicationRevision);
        UUID ownerTenantId = requireWrite(projectId);

        // 与新版本发布共享项目→应用→稳定看板ID锁序，禁止回滚建立反向等待边。
        ApplicationPublicationState state = repository.lockPublicationState(projectId, applicationId)
                .filter(value -> value.deletedAt() == null)
                .orElseThrow(ApplicationPublicationService::notFound);
        requireStateIdentity(state, ownerTenantId, projectId, applicationId);
        if (state.publicationRevision() != publicationRevision
                || state.publicationRevision() == Long.MAX_VALUE
                || targetVersionId.equals(state.currentVersionId())) {
            throw publicationConflict();
        }

        ApplicationVersion target = repository.findVersion(projectId, applicationId, targetVersionId)
                .orElseThrow(ApplicationPublicationService::rollbackTargetNotFound);
        requireVersionIdentity(state, target, targetVersionId);
        ApplicationVersion previousVersion = findCurrentVersion(state);
        ApplicationPublicationCandidate candidate = prepareHistoricalCandidate(target);
        requireHistoricalCandidateIdentity(target, candidate);
        QualifiedApplicationPublicationCandidate qualified = qualify(candidate);
        if (qualified.candidate() != candidate) {
            throw new IllegalStateException("应用宿主资格未绑定锁内原候选实例");
        }
        requireHistoricalCandidateIdentity(target, qualified.candidate());

        UUID accountId = currentAccountId();
        ApplicationPublicationRollbackResult result = repository.rollbackPublication(
                projectId, applicationId, targetVersionId, publicationRevision,
                accountId, databaseTimestamp());
        requireRolledBack(result, publicationRevision);
        long committedPublicationRevision = result.observedPublicationRevision().orElseThrow();
        auditRollback(ownerTenantId, projectId, accountId, target, previousVersion, committedPublicationRevision);
        return target;
    }

    /**
     * 撤回当前发布应用并保留全部不可变版本及精确看板关系。
     *
     * <p>S12-1c4只消费publicationRevision，不读取草稿或重新执行发布资格。当前指针为空、
     * publicationRevision变化或Long计数耗尽都使用统一发布冲突拒绝，避免生成无意义状态变化与审计。</p>
     *
     * @param projectId 已选定项目ID
     * @param applicationId 应用内部ID
     * @param expectedPublicationRevision 调用方读取的发布revision规范十进制文本
     * @return 撤回前的不可变当前应用版本
     */
    @Transactional
    public ApplicationVersion withdraw(
            UUID projectId, UUID applicationId, String expectedPublicationRevision) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(applicationId, "applicationId");
        long publicationRevision = requireRevision(expectedPublicationRevision);
        UUID ownerTenantId = requireWrite(projectId);

        ApplicationPublicationState state = repository.lockPublicationState(projectId, applicationId)
                .filter(value -> value.deletedAt() == null)
                .orElseThrow(ApplicationPublicationService::notFound);
        requireStateIdentity(state, ownerTenantId, projectId, applicationId);
        if (state.publicationRevision() != publicationRevision
                || state.publicationRevision() == Long.MAX_VALUE
                || state.currentVersionId() == null) {
            throw publicationConflict();
        }

        ApplicationVersion previousVersion = findCurrentVersion(state);
        UUID accountId = currentAccountId();
        ApplicationPublicationWithdrawalResult result = repository.withdrawPublication(
                projectId, applicationId, publicationRevision, accountId, databaseTimestamp());
        requireWithdrawn(result, previousVersion.id(), publicationRevision);
        long committedPublicationRevision = result.observedPublicationRevision().orElseThrow();
        auditWithdrawal(ownerTenantId, projectId, accountId, previousVersion, committedPublicationRevision);
        return previousVersion;
    }

    /**
     * 一次性软删应用并永久保留其草稿、不可变版本和精确看板关系。
     *
     * <p>S12-1c5只消费publicationRevision；已发布和已撤回应用都可软删一次。成功同时清空发布指针、
     * 推进publicationRevision并写审计，已删除目录后续统一不可见且V1不提供恢复。</p>
     *
     * @param projectId 已选定项目ID
     * @param applicationId 应用内部ID
     * @param expectedPublicationRevision 调用方读取的发布revision规范十进制文本
     */
    @Transactional
    public void softDelete(
            UUID projectId, UUID applicationId, String expectedPublicationRevision) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(applicationId, "applicationId");
        long publicationRevision = requireRevision(expectedPublicationRevision);
        UUID ownerTenantId = requireWrite(projectId);

        ApplicationPublicationState state = repository.lockPublicationState(projectId, applicationId)
                .filter(value -> value.deletedAt() == null)
                .orElseThrow(ApplicationPublicationService::notFound);
        requireStateIdentity(state, ownerTenantId, projectId, applicationId);
        if (state.publicationRevision() != publicationRevision
                || state.publicationRevision() == Long.MAX_VALUE) {
            throw publicationConflict();
        }

        ApplicationVersion previousVersion = findCurrentVersion(state);
        UUID accountId = currentAccountId();
        Instant deletedAt = databaseTimestamp();
        ApplicationSoftDeleteResult result = repository.softDelete(
                projectId, applicationId, publicationRevision, accountId, deletedAt);
        requireDeleted(result, state.currentVersionId(), publicationRevision, deletedAt);
        long committedPublicationRevision = result.observedPublicationRevision().orElseThrow();
        auditDeletion(ownerTenantId, projectId, accountId, applicationId,
                previousVersion, committedPublicationRevision, result.observedDeletedAt().orElseThrow());
    }

    /** 在全部引用看板锁内重建候选，并把已知合同拒绝映射为稳定应用发布条件错误。 */
    private ApplicationPublicationCandidate prepareCandidate(ApplicationDraft draft) {
        try {
            return candidateFactory.prepareLocked(draft);
        } catch (ApplicationPublicationQualificationException exception) {
            throw publicationInvalid();
        }
    }

    /** 历史应用快照在全部引用看板锁内重建；已知内容或引用拒绝统一映射为安全发布条件错误。 */
    private ApplicationPublicationCandidate prepareHistoricalCandidate(ApplicationVersion version) {
        try {
            return candidateFactory.prepareHistoricalVersionLocked(version);
        } catch (ApplicationPublicationQualificationException exception) {
            throw publicationInvalid();
        }
    }

    /** 当前宿主缺失或不兼容使用503；其他资格拒绝仍是应用发布条件不满足。 */
    private QualifiedApplicationPublicationCandidate qualify(ApplicationPublicationCandidate candidate) {
        try {
            return qualificationService.qualify(candidate);
        } catch (ApplicationPublicationQualificationException exception) {
            if (exception.reason() == ApplicationPublicationQualificationException.Reason.HOST_UNAVAILABLE) {
                throw new BusinessException(ApplicationErrorCode.APPLICATION_PUBLICATION_DEPENDENCY_UNAVAILABLE);
            }
            throw publicationInvalid();
        }
    }

    /** 把同一锁内资格候选封装为待受控入口写入的不可变应用版本值。 */
    private static ApplicationVersion toVersion(
            ApplicationPublicationCandidate candidate,
            long versionNumber,
            UUID accountId,
            Instant publishedAt) {
        return new ApplicationVersion(
                Uuid7.generate(), candidate.tenantId(), candidate.projectId(), candidate.applicationId(),
                versionNumber, candidate.sourceDraftRevision(), candidate.snapshot(),
                candidate.snapshotDigestAlgorithm(), candidate.snapshotDigest(), accountId, publishedAt);
    }

    /** 数据库timestamptz只保留微秒，服务即时返回值必须与后续回读精确相等。 */
    private static Instant databaseTimestamp() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** 锁状态必须精确属于请求应用和项目owner tenant，仓储实现漂移属于内部完整性故障。 */
    private static void requireStateIdentity(
            ApplicationPublicationState state,
            UUID ownerTenantId,
            UUID projectId,
            UUID applicationId) {
        if (!state.tenantId().equals(ownerTenantId)
                || !state.projectId().equals(projectId)
                || !state.applicationId().equals(applicationId)) {
            throw new IllegalStateException("应用锁状态身份与请求不一致");
        }
    }

    /** 锁后草稿必须与已锁状态保持三轴身份及revision一致，不能把第二次读取当作另一聚合。 */
    private static void requireDraftIdentity(ApplicationPublicationState state, ApplicationDraft draft) {
        if (!draft.tenantId().equals(state.tenantId())
                || !draft.projectId().equals(state.projectId())
                || !draft.applicationId().equals(state.applicationId())
                || draft.revision() != state.draftRevision()) {
            throw new IllegalStateException("应用锁后草稿身份或revision与锁状态不一致");
        }
    }

    /** 候选必须继续绑定锁后草稿的完整身份和revision，内部工厂不得把请求重定向到另一应用。 */
    private static void requireCandidateIdentity(
            ApplicationDraft draft, ApplicationPublicationCandidate candidate) {
        if (!candidate.tenantId().equals(draft.tenantId())
                || !candidate.projectId().equals(draft.projectId())
                || !candidate.applicationId().equals(draft.applicationId())
                || candidate.sourceDraftRevision() != draft.revision()) {
            throw new IllegalStateException("应用发布候选身份或revision与锁后草稿不一致");
        }
    }

    /** 回滚目标必须精确属于锁内应用；跨聚合返回属于持久适配内部完整性故障。 */
    private static void requireVersionIdentity(
            ApplicationPublicationState state, ApplicationVersion target, UUID targetVersionId) {
        if (!target.id().equals(targetVersionId)
                || !target.applicationId().equals(state.applicationId())
                || !target.tenantId().equals(state.tenantId())
                || !target.projectId().equals(state.projectId())) {
            throw new IllegalStateException("应用不可变版本与锁状态身份不一致");
        }
    }

    /** 锁内历史候选必须保持目标版本全身份、来源revision、快照和摘要，不能替换回滚内容。 */
    private static void requireHistoricalCandidateIdentity(
            ApplicationVersion target, ApplicationPublicationCandidate candidate) {
        if (!candidate.applicationId().equals(target.applicationId())
                || !candidate.tenantId().equals(target.tenantId())
                || !candidate.projectId().equals(target.projectId())
                || candidate.sourceDraftRevision() != target.sourceDraftRevision()
                || !candidate.snapshot().equals(target.snapshot())
                || !candidate.snapshotDigestAlgorithm().equals(target.snapshotDigestAlgorithm())
                || !candidate.snapshotDigest().equals(target.snapshotDigest())) {
            throw new IllegalStateException("应用历史候选与回滚目标内容不一致");
        }
    }

    /** 当前指针为空时允许从撤回状态回滚；非空指针缺少同应用版本属于持久完整性故障。 */
    private ApplicationVersion findCurrentVersion(ApplicationPublicationState state) {
        if (state.currentVersionId() == null) {
            return null;
        }
        ApplicationVersion version = repository.findVersion(
                        state.projectId(), state.applicationId(), state.currentVersionId())
                .orElseThrow(() -> new IllegalStateException("应用当前指针未命中同应用不可变版本"));
        requireVersionIdentity(state, version, state.currentVersionId());
        return version;
    }

    /** 受控函数结果必须与锁内预分配版本号及单步publicationRevision完全一致。 */
    private static void requirePublished(
            ApplicationPublicationAppendResult result,
            ApplicationVersion version,
            long expectedPublicationRevision) {
        switch (result.status()) {
            case PUBLISHED -> {
                if (result.publishedVersionNumber().orElseThrow() != version.versionNumber()
                        || result.observedPublicationRevision().orElseThrow()
                        != expectedPublicationRevision + 1) {
                    throw new IllegalStateException("应用受控发布入口返回了非预期版本或发布revision");
                }
            }
            case NOT_FOUND -> throw notFound();
            case DRAFT_CONFLICT, PUBLICATION_CONFLICT, REVISION_EXHAUSTED -> throw publicationConflict();
            case DASHBOARD_REFERENCE_INVALID, DASHBOARD_NOT_RUNNABLE -> throw publicationInvalid();
        }
    }

    /** 受控回滚必须把publicationRevision恰好推进一步；失败分类全部映射且不能误记成功审计。 */
    private static void requireRolledBack(
            ApplicationPublicationRollbackResult result, long expectedPublicationRevision) {
        switch (result.status()) {
            case ROLLED_BACK -> {
                if (result.observedPublicationRevision().orElseThrow()
                        != expectedPublicationRevision + 1) {
                    throw new IllegalStateException("应用受控回滚入口返回了非预期发布revision");
                }
            }
            case NOT_FOUND -> throw notFound();
            case TARGET_NOT_FOUND -> throw rollbackTargetNotFound();
            case PUBLICATION_CONFLICT, REVISION_EXHAUSTED -> throw publicationConflict();
            case DASHBOARD_REFERENCE_INVALID, DASHBOARD_NOT_RUNNABLE -> throw publicationInvalid();
        }
    }

    /** 受控撤回必须返回锁内原版本并把publicationRevision恰好推进一步。 */
    private static void requireWithdrawn(
            ApplicationPublicationWithdrawalResult result,
            UUID expectedPreviousVersionId,
            long expectedPublicationRevision) {
        switch (result.status()) {
            case WITHDRAWN -> {
                if (!result.withdrawnVersionId().orElseThrow().equals(expectedPreviousVersionId)
                        || result.observedPublicationRevision().orElseThrow()
                        != expectedPublicationRevision + 1) {
                    throw new IllegalStateException("应用受控撤回入口返回了非预期原版本或发布revision");
                }
            }
            case NOT_FOUND -> throw notFound();
            case NOT_PUBLISHED, PUBLICATION_CONFLICT, REVISION_EXHAUSTED -> throw publicationConflict();
        }
    }

    /** 受控软删必须返回锁内原指针、单步发布轴与精确删除时刻。 */
    private static void requireDeleted(
            ApplicationSoftDeleteResult result,
            UUID expectedPreviousVersionId,
            long expectedPublicationRevision,
            Instant expectedDeletedAt) {
        switch (result.status()) {
            case DELETED -> {
                if (!Objects.equals(
                                result.deletedPreviousVersionId().orElse(null),
                                expectedPreviousVersionId)
                        || result.observedPublicationRevision().orElseThrow()
                        != expectedPublicationRevision + 1
                        || !result.observedDeletedAt().orElseThrow().equals(expectedDeletedAt)) {
                    throw new IllegalStateException("应用受控软删入口返回了非预期删除事实");
                }
            }
            case NOT_FOUND -> throw notFound();
            case PUBLICATION_CONFLICT, REVISION_EXHAUSTED -> throw publicationConflict();
        }
    }

    /** 在原事务内依次取得管理角色、真实归属、ACTIVE共享锁和锁后角色复核。 */
    private UUID requireWrite(UUID projectId) {
        requireManagementRole(projectId);
        UUID ownerTenantId = projectService.requireProjectTenant(projectId);
        lifecycleAccessService.requireActiveForWrite(ownerTenantId, projectId);
        requireManagementRole(projectId);
        return ownerTenantId;
    }

    /** 非OWNER/ADMIN成员使用应用领域拒绝；项目不可见仍由project端口统一隐藏。 */
    private void requireManagementRole(UUID projectId) {
        ProjectRole role = projectService.requireRoleInProject(projectId);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) {
            throw new BusinessException(ApplicationErrorCode.APPLICATION_MANAGEMENT_FORBIDDEN);
        }
    }

    /** 解析规范Revision文本；非法格式属于发布条件错误，不进入项目锁或仓储。 */
    private static long requireRevision(String value) {
        if (value == null || !REVISION_PATTERN.matcher(value).matches()) {
            throw publicationInvalid();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw publicationInvalid();
        }
    }

    /** @return 当前已认证Console账号；缺失上下文属于调用链配置错误 */
    private static UUID currentAccountId() {
        return TenantContext.require().accountId();
    }

    /** 发布成功后写最小审计；失败由外层事务回滚版本、关系和指针。 */
    private void auditPublication(
            UUID tenantId,
            UUID projectId,
            ApplicationVersion version,
            long publicationRevision) {
        auditLogService.record(new AuditLogEntry(
                tenantId, projectId, version.publishedByAccountId(), "application", version.applicationId(),
                "application.published", Map.of(
                        "versionId", version.id().toString(),
                        "versionNumber", Long.toString(version.versionNumber()),
                        "sourceDraftRevision", Long.toString(version.sourceDraftRevision()),
                        "publicationRevision", Long.toString(publicationRevision))));
    }

    /** 回滚只记录目标版本和提交后发布轴；审计失败与指针切换在同一事务整体回滚。 */
    private void auditRollback(
            UUID tenantId,
            UUID projectId,
            UUID accountId,
            ApplicationVersion target,
            ApplicationVersion previousVersion,
            long publicationRevision) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("targetVersionId", target.id().toString());
        details.put("targetVersionNumber", Long.toString(target.versionNumber()));
        details.put("previousVersionId", previousVersion == null ? null : previousVersion.id().toString());
        details.put("previousVersionNumber",
                previousVersion == null ? null : Long.toString(previousVersion.versionNumber()));
        details.put("publicationRevision", Long.toString(publicationRevision));
        auditLogService.record(new AuditLogEntry(
                tenantId, projectId, accountId, "application", target.applicationId(),
                "application.rolled_back", details));
    }

    /** 撤回只记录原版本和提交后发布轴；审计失败与指针清空在同一事务整体回滚。 */
    private void auditWithdrawal(
            UUID tenantId,
            UUID projectId,
            UUID accountId,
            ApplicationVersion previousVersion,
            long publicationRevision) {
        auditLogService.record(new AuditLogEntry(
                tenantId, projectId, accountId, "application", previousVersion.applicationId(),
                "application.withdrawn", Map.of(
                        "previousVersionId", previousVersion.id().toString(),
                        "previousVersionNumber", Long.toString(previousVersion.versionNumber()),
                        "publicationRevision", Long.toString(publicationRevision))));
    }

    /** 软删审计允许原指针为空；审计失败与删除标记、指针及publicationRevision整体回滚。 */
    private void auditDeletion(
            UUID tenantId,
            UUID projectId,
            UUID accountId,
            UUID applicationId,
            ApplicationVersion previousVersion,
            long publicationRevision,
            Instant deletedAt) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("previousVersionId", previousVersion == null ? null : previousVersion.id().toString());
        details.put("previousVersionNumber",
                previousVersion == null ? null : Long.toString(previousVersion.versionNumber()));
        details.put("publicationRevision", Long.toString(publicationRevision));
        details.put("deletedAt", deletedAt.toString());
        auditLogService.record(new AuditLogEntry(
                tenantId, projectId, accountId, "application", applicationId,
                "application.deleted", details));
    }

    /** @return 不区分不存在、跨项目和软删除的应用不可见错误 */
    private static BusinessException notFound() {
        return new BusinessException(ApplicationErrorCode.APPLICATION_NOT_FOUND);
    }

    /** @return 草稿/引用/聚合快照当前不满足应用发布条件 */
    private static BusinessException publicationInvalid() {
        return new BusinessException(ApplicationErrorCode.APPLICATION_PUBLICATION_INVALID);
    }

    /** @return 双revision变化或Long计数耗尽的统一可恢复冲突 */
    private static BusinessException publicationConflict() {
        return new BusinessException(ApplicationErrorCode.APPLICATION_PUBLICATION_CONFLICT);
    }

    /** @return 不区分不存在、跨项目和跨应用的回滚目标不可见错误 */
    private static BusinessException rollbackTargetNotFound() {
        return new BusinessException(ApplicationErrorCode.APPLICATION_ROLLBACK_TARGET_NOT_FOUND);
    }
}
