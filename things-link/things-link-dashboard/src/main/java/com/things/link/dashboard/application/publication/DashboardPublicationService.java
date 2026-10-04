package com.things.link.dashboard.application.publication;

import com.things.link.dashboard.application.schema.DashboardSchemaParseException;
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
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 在一个数据库事务内重新核验看板草稿或历史版本并改变发布状态。
 *
 * <p>S12-1b2c3至1b2f只开放无HTTP的管理用例。调用方不能提交候选或资格票据；本服务在项目写许可和
 * 看板行锁内经受控数据库函数原子追加版本、切换或清空指针，以及软删除目录。</p>
 */
@Service
public class DashboardPublicationService {

    /** Revision只接受零或无前导零的规范十进制Long文本。 */
    private static final Pattern REVISION_PATTERN = Pattern.compile("0|[1-9][0-9]*");
    /** 把类型化组件与资源清单转换为稳定JSON数组的内部映射器。 */
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    /** 看板状态、草稿、版本与受控发布状态写入口。 */
    private final DashboardRepository repository;
    /** 项目角色与真实owner tenant公开端口。 */
    private final ProjectService projectService;
    /** 在原事务内持有ACTIVE项目写许可的公开端口。 */
    private final ProjectLifecycleAccessService lifecycleAccessService;
    /** 从锁内持久草稿重建规范候选的工厂。 */
    private final DashboardPublicationCandidateFactory candidateFactory;
    /** 核验宿主、模型、设备、数据适配和逐页预算的门面。 */
    private final DashboardPublicationQualificationService qualificationService;
    /** 与版本和指针写入共用事务的审计端口。 */
    private final AuditLogService auditLogService;

    /**
     * 创建看板发布服务。
     *
     * @param repository 看板持久端口
     * @param projectService 项目授权和归属端口
     * @param lifecycleAccessService 项目持续写许可端口
     * @param candidateFactory 规范候选工厂
     * @param qualificationService 完整发布资格门面
     * @param auditLogService 同事务审计端口
     */
    public DashboardPublicationService(
            DashboardRepository repository,
            ProjectService projectService,
            ProjectLifecycleAccessService lifecycleAccessService,
            DashboardPublicationCandidateFactory candidateFactory,
            DashboardPublicationQualificationService qualificationService,
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
     * 发布当前草稿为新的不可变版本。
     *
     * @param projectId 已选定项目ID
     * @param dashboardId 看板ID
     * @param expectedDraftRevision 调用方读取的草稿revision规范十进制文本
     * @param expectedPublicationRevision 调用方读取的发布revision规范十进制文本
     * @return 已原子发布且版本号与持久事实一致的新版本
     */
    @Transactional
    public DashboardVersion publish(
            UUID projectId,
            UUID dashboardId,
            String expectedDraftRevision,
            String expectedPublicationRevision) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(dashboardId, "dashboardId");
        long draftRevision = requireRevision(expectedDraftRevision);
        long publicationRevision = requireRevision(expectedPublicationRevision);
        UUID ownerTenantId = requireWrite(projectId);

        DashboardPublicationState state = repository.lockPublicationState(projectId, dashboardId)
                .filter(value -> value.deletedAt() == null)
                .orElseThrow(DashboardPublicationService::notFound);
        if (state.draftRevision() != draftRevision
                || state.publicationRevision() != publicationRevision
                || state.publicationRevision() == Long.MAX_VALUE
                || state.latestVersionNumber() == Long.MAX_VALUE) {
            throw publicationConflict();
        }

        DashboardDraft draft = repository.findDraft(projectId, dashboardId)
                .filter(value -> value.revision() == draftRevision)
                .orElseThrow(DashboardPublicationService::publicationConflict);
        DashboardPublicationCandidate candidate = prepareCandidate(draft);
        QualifiedDashboardPublicationCandidate qualified = qualify(candidate);
        DashboardVersion version = toVersion(
                qualified.candidate(), state.latestVersionNumber() + 1, currentAccountId(), databaseTimestamp());
        DashboardPublicationAppendResult append = repository.appendPublication(version, publicationRevision);
        requirePublished(append);
        long committedPublicationRevision = append.observedPublicationRevision().orElseThrow();
        auditPublication(ownerTenantId, projectId, version, committedPublicationRevision);
        return version;
    }

    /**
     * 把发布指针切回重新通过当前资格的既有不可变版本。
     *
     * <p>S12-1b2d只消费发布revision，不把草稿内容或revision作为回滚前置，也不分配新版本号。目标版本的冻结Schema、
     * 摘要、关系和派生清单会先重验一致性，再执行宿主、模型、设备、数据适配及预算的当前资格。</p>
     *
     * @param projectId 已选定项目ID
     * @param dashboardId 看板ID
     * @param targetVersionId 目标历史版本ID
     * @param expectedPublicationRevision 调用方读取的发布revision规范十进制文本
     * @return 未改写的目标不可变版本
     */
    @Transactional
    public DashboardVersion rollback(
            UUID projectId,
            UUID dashboardId,
            UUID targetVersionId,
            String expectedPublicationRevision) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(dashboardId, "dashboardId");
        Objects.requireNonNull(targetVersionId, "targetVersionId");
        long publicationRevision = requireRevision(expectedPublicationRevision);
        UUID ownerTenantId = requireWrite(projectId);

        DashboardPublicationState state = repository.lockPublicationState(projectId, dashboardId)
                .filter(value -> value.deletedAt() == null)
                .orElseThrow(DashboardPublicationService::notFound);
        if (!state.tenantId().equals(ownerTenantId)) {
            throw new IllegalStateException("看板持久归属与项目owner tenant不一致");
        }
        if (state.publicationRevision() != publicationRevision
                || state.publicationRevision() == Long.MAX_VALUE
                || targetVersionId.equals(state.currentVersionId())) {
            throw publicationConflict();
        }

        DashboardVersion target = repository.findVersion(projectId, dashboardId, targetVersionId)
                .orElseThrow(DashboardPublicationService::rollbackTargetNotFound);
        requireVersionIdentity(state, target);
        DashboardPublicationCandidate candidate = prepareHistoricalCandidate(target);
        qualify(candidate);
        UUID accountId = currentAccountId();
        DashboardPublicationRollbackResult result = repository.rollbackPublication(
                projectId, dashboardId, targetVersionId, publicationRevision, accountId, databaseTimestamp());
        requireRolledBack(result);
        long committedPublicationRevision = result.observedPublicationRevision().orElseThrow();
        auditRollback(ownerTenantId, projectId, accountId, target, committedPublicationRevision);
        return target;
    }

    /**
     * 撤回当前发布版本并保留全部不可变历史。
     *
     * <p>S12-1b2e只消费发布revision。当前指针为空、revision变化或Long计数耗尽都使用60040拒绝，
     * 从而不生成无意义审计；成功时指针清空且publicationRevision严格推进一次。</p>
     *
     * @param projectId 已选定项目ID
     * @param dashboardId 看板ID
     * @param expectedPublicationRevision 调用方读取的发布revision规范十进制文本
     * @return 撤回前的不可变当前版本
     */
    @Transactional
    public DashboardVersion withdraw(
            UUID projectId, UUID dashboardId, String expectedPublicationRevision) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(dashboardId, "dashboardId");
        long publicationRevision = requireRevision(expectedPublicationRevision);
        UUID ownerTenantId = requireWrite(projectId);

        DashboardPublicationState state = repository.lockPublicationState(projectId, dashboardId)
                .filter(value -> value.deletedAt() == null)
                .orElseThrow(DashboardPublicationService::notFound);
        if (!state.tenantId().equals(ownerTenantId)) {
            throw new IllegalStateException("看板持久归属与项目owner tenant不一致");
        }
        if (state.publicationRevision() != publicationRevision
                || state.publicationRevision() == Long.MAX_VALUE
                || state.currentVersionId() == null) {
            throw publicationConflict();
        }

        DashboardVersion previousVersion = repository.findVersion(
                        projectId, dashboardId, state.currentVersionId())
                .orElseThrow(() -> new IllegalStateException("看板当前指针未命中同域不可变版本"));
        requireVersionIdentity(state, previousVersion);
        UUID accountId = currentAccountId();
        DashboardPublicationWithdrawalResult result = repository.withdrawPublication(
                projectId, dashboardId, publicationRevision, accountId, databaseTimestamp());
        requireWithdrawn(result, previousVersion.id());
        long committedPublicationRevision = result.observedPublicationRevision().orElseThrow();
        auditWithdrawal(ownerTenantId, projectId, accountId, previousVersion, committedPublicationRevision);
        return previousVersion;
    }

    /**
     * 一次性软删看板并永久保留其草稿与不可变历史。
     *
     * <p>S12-1b2f只消费发布revision；已发布、已撤回和从未发布的看板都可软删一次。成功同时清空发布指针、
     * 推进publicationRevision并写审计，已删除目录后续统一不可见。</p>
     *
     * @param projectId 已选定项目ID
     * @param dashboardId 看板ID
     * @param expectedPublicationRevision 调用方读取的发布revision规范十进制文本
     */
    @Transactional
    public void softDelete(
            UUID projectId, UUID dashboardId, String expectedPublicationRevision) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(dashboardId, "dashboardId");
        long publicationRevision = requireRevision(expectedPublicationRevision);
        UUID ownerTenantId = requireWrite(projectId);

        DashboardPublicationState state = repository.lockPublicationState(projectId, dashboardId)
                .filter(value -> value.deletedAt() == null)
                .orElseThrow(DashboardPublicationService::notFound);
        if (!state.tenantId().equals(ownerTenantId)) {
            throw new IllegalStateException("看板持久归属与项目owner tenant不一致");
        }
        if (state.publicationRevision() != publicationRevision
                || state.publicationRevision() == Long.MAX_VALUE) {
            throw publicationConflict();
        }

        DashboardVersion previousVersion = findCurrentVersion(state);
        UUID accountId = currentAccountId();
        Instant deletedAt = databaseTimestamp();
        DashboardSoftDeleteResult result = repository.softDelete(
                projectId, dashboardId, publicationRevision, accountId, deletedAt);
        requireDeleted(result, state.currentVersionId(), deletedAt);
        long committedPublicationRevision = result.observedPublicationRevision().orElseThrow();
        auditDeletion(ownerTenantId, projectId, accountId, dashboardId,
                previousVersion, committedPublicationRevision, result.observedDeletedAt().orElseThrow());
    }

    /** 在同一锁内草稿上重跑完整内部Schema；持久旧事实失配收敛为安全发布错误。 */
    private DashboardPublicationCandidate prepareCandidate(DashboardDraft draft) {
        try {
            return candidateFactory.prepare(draft);
        } catch (DashboardSchemaParseException | DashboardSchemaValidationException exception) {
            throw publicationInvalid();
        }
    }

    /** 历史Schema按当前合同不再兼容属于可恢复资格拒绝；封存元数据漂移和基础设施异常继续暴露。 */
    private DashboardPublicationCandidate prepareHistoricalCandidate(DashboardVersion version) {
        try {
            return candidateFactory.prepareHistoricalVersion(version);
        } catch (DashboardSchemaParseException | DashboardSchemaValidationException exception) {
            throw publicationInvalid();
        }
    }

    /** 把资格内部原因映射为稳定业务边界，不回显外部ID、摘要或平台能力细节。 */
    private QualifiedDashboardPublicationCandidate qualify(DashboardPublicationCandidate candidate) {
        try {
            return qualificationService.qualify(candidate);
        } catch (DashboardPublicationQualificationException exception) {
            throw switch (exception.reason()) {
                case MODEL_REFERENCE_INVALID -> new BusinessException(
                        DashboardErrorCode.DASHBOARD_MODEL_REFERENCE_INVALID);
                case HOST_COMPONENT_UNAVAILABLE, DATA_ADAPTER_UNAVAILABLE -> new BusinessException(
                        DashboardErrorCode.DASHBOARD_PUBLICATION_DEPENDENCY_UNAVAILABLE);
                default -> publicationInvalid();
            };
        }
    }

    /** 把同一已资格候选封装为待受控入口写入的不可变版本值。 */
    private static DashboardVersion toVersion(
            DashboardPublicationCandidate candidate,
            long versionNumber,
            UUID accountId,
            Instant publishedAt) {
        JsonNode components = JSON_MAPPER.valueToTree(candidate.requiredComponents());
        JsonNode resources = JSON_MAPPER.valueToTree(candidate.requiredResources());
        return new DashboardVersion(
                Uuid7.generate(), candidate.tenantId(), candidate.projectId(), candidate.dashboardId(),
                versionNumber, candidate.sourceDraftRevision(), candidate.normalizedSchema(),
                DashboardDraft.SCHEMA_VERSION, candidate.schemaDigestAlgorithm(), candidate.schemaDigest(),
                components, resources, accountId, publishedAt, candidate.modelReferences());
    }

    /**
     * 生成与PostgreSQL timestamptz精度一致的服务端时刻。
     *
     * <p>数据库只保留微秒；若返回纳秒值再持久化，Linux纳秒时钟会使同一版本的即时返回值与回读事实不相等。</p>
     */
    private static Instant databaseTimestamp() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** 受控函数的失败分类必须全部显式处理，未知组合不能被误当成发布成功。 */
    private static void requirePublished(DashboardPublicationAppendResult result) {
        switch (result.status()) {
            case PUBLISHED -> {
                return;
            }
            case NOT_FOUND -> throw notFound();
            case DRAFT_CONFLICT, PUBLICATION_CONFLICT, REVISION_EXHAUSTED -> throw publicationConflict();
        }
    }

    /** 历史版本必须与锁内目录保持完整身份一致；持久事实破损不能降格成普通业务拒绝。 */
    private static void requireVersionIdentity(
            DashboardPublicationState state, DashboardVersion target) {
        if (!target.dashboardId().equals(state.dashboardId())
                || !target.tenantId().equals(state.tenantId())
                || !target.projectId().equals(state.projectId())) {
            throw new IllegalStateException("看板不可变版本与锁内目录身份不一致");
        }
    }

    /** 受控回滚入口的失败分类必须全部显式处理，未知组合不能被误当成成功。 */
    private static void requireRolledBack(DashboardPublicationRollbackResult result) {
        switch (result.status()) {
            case ROLLED_BACK -> {
                return;
            }
            case DASHBOARD_NOT_FOUND -> throw notFound();
            case TARGET_NOT_FOUND -> throw rollbackTargetNotFound();
            case CURRENT_VERSION, PUBLICATION_CONFLICT, REVISION_EXHAUSTED -> throw publicationConflict();
        }
    }

    /** 受控撤回入口必须返回锁内原版本；未知或漂移组合不能被误当成成功。 */
    private static void requireWithdrawn(
            DashboardPublicationWithdrawalResult result, UUID expectedPreviousVersionId) {
        switch (result.status()) {
            case WITHDRAWN -> {
                if (!result.withdrawnVersionId().orElseThrow().equals(expectedPreviousVersionId)) {
                    throw new IllegalStateException("看板受控撤回入口返回了非预期原版本");
                }
            }
            case DASHBOARD_NOT_FOUND -> throw notFound();
            case NOT_PUBLISHED, PUBLICATION_CONFLICT, REVISION_EXHAUSTED -> throw publicationConflict();
        }
    }

    /** 读取锁内当前版本；撤回后的空指针合法，非空指针缺少版本属于持久不变量破损。 */
    private DashboardVersion findCurrentVersion(DashboardPublicationState state) {
        if (state.currentVersionId() == null) {
            return null;
        }
        DashboardVersion version = repository.findVersion(
                        state.projectId(), state.dashboardId(), state.currentVersionId())
                .orElseThrow(() -> new IllegalStateException("看板当前指针未命中同域不可变版本"));
        requireVersionIdentity(state, version);
        return version;
    }

    /** 受控软删入口必须返回锁内原指针和精确删除时刻；任何漂移都作为内部故障回滚。 */
    private static void requireDeleted(
            DashboardSoftDeleteResult result,
            UUID expectedPreviousVersionId,
            Instant expectedDeletedAt) {
        switch (result.status()) {
            case DELETED -> {
                if (!Objects.equals(
                                result.deletedPreviousVersionId().orElse(null),
                                expectedPreviousVersionId)
                        || !result.observedDeletedAt().orElseThrow().equals(expectedDeletedAt)) {
                    throw new IllegalStateException("看板受控软删入口返回了非预期删除事实");
                }
            }
            case DASHBOARD_NOT_FOUND -> throw notFound();
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

    /** 非OWNER/ADMIN成员使用看板领域拒绝；项目不可见仍由project端口统一隐藏。 */
    private void requireManagementRole(UUID projectId) {
        ProjectRole role = projectService.requireRoleInProject(projectId);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) {
            throw new BusinessException(DashboardErrorCode.DASHBOARD_MANAGEMENT_FORBIDDEN);
        }
    }

    /** 解析规范Revision文本，避免前导零或Long溢出形成多个命令表示。 */
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

    /** 写入最小发布审计；审计失败由外层事务回滚版本、关系和指针。 */
    private void auditPublication(
            UUID tenantId,
            UUID projectId,
            DashboardVersion version,
            long publicationRevision) {
        auditLogService.record(new AuditLogEntry(
                tenantId, projectId, version.publishedByAccountId(), "dashboard", version.dashboardId(),
                "dashboard.published", Map.of(
                        "versionId", version.id().toString(),
                        "versionNumber", Long.toString(version.versionNumber()),
                        "sourceDraftRevision", Long.toString(version.sourceDraftRevision()),
                        "publicationRevision", Long.toString(publicationRevision))));
    }

    /** 写入最小回滚审计；审计失败由外层事务回滚指针和publicationRevision。 */
    private void auditRollback(
            UUID tenantId,
            UUID projectId,
            UUID accountId,
            DashboardVersion target,
            long publicationRevision) {
        auditLogService.record(new AuditLogEntry(
                tenantId, projectId, accountId, "dashboard", target.dashboardId(),
                "dashboard.rolled_back", Map.of(
                        "versionId", target.id().toString(),
                        "versionNumber", Long.toString(target.versionNumber()),
                        "publicationRevision", Long.toString(publicationRevision))));
    }

    /** 写入最小撤回审计；审计失败由外层事务回滚指针和publicationRevision。 */
    private void auditWithdrawal(
            UUID tenantId,
            UUID projectId,
            UUID accountId,
            DashboardVersion previousVersion,
            long publicationRevision) {
        auditLogService.record(new AuditLogEntry(
                tenantId, projectId, accountId, "dashboard", previousVersion.dashboardId(),
                "dashboard.withdrawn", Map.of(
                        "previousVersionId", previousVersion.id().toString(),
                        "previousVersionNumber", Long.toString(previousVersion.versionNumber()),
                        "publicationRevision", Long.toString(publicationRevision))));
    }

    /** 写入允许空旧版本的最小软删审计；审计失败由外层事务回滚目录状态。 */
    private void auditDeletion(
            UUID tenantId,
            UUID projectId,
            UUID accountId,
            UUID dashboardId,
            DashboardVersion previousVersion,
            long publicationRevision,
            Instant deletedAt) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("previousVersionId", previousVersion == null ? null : previousVersion.id().toString());
        details.put("previousVersionNumber",
                previousVersion == null ? null : Long.toString(previousVersion.versionNumber()));
        details.put("publicationRevision", Long.toString(publicationRevision));
        details.put("deletedAt", deletedAt.toString());
        auditLogService.record(new AuditLogEntry(
                tenantId, projectId, accountId, "dashboard", dashboardId,
                "dashboard.deleted", details));
    }

    /** @return 不区分不存在、跨项目和软删除的看板不可见错误 */
    private static BusinessException notFound() {
        return new BusinessException(DashboardErrorCode.DASHBOARD_NOT_FOUND);
    }

    /** @return 草稿/发布轴变化或计数耗尽的统一可恢复冲突 */
    private static BusinessException publicationConflict() {
        return new BusinessException(DashboardErrorCode.DASHBOARD_PUBLICATION_CONFLICT);
    }

    /** @return 不泄漏内部Schema或外部资格原因的发布条件错误 */
    private static BusinessException publicationInvalid() {
        return new BusinessException(DashboardErrorCode.DASHBOARD_PUBLICATION_INVALID);
    }

    /** @return 不区分不存在、跨项目和跨看板的回滚目标不可见错误 */
    private static BusinessException rollbackTargetNotFound() {
        return new BusinessException(DashboardErrorCode.DASHBOARD_ROLLBACK_TARGET_NOT_FOUND);
    }
}
