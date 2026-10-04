package com.things.link.dashboard.application;

import com.things.link.dashboard.application.draft.DashboardDraftContractValidator;
import com.things.link.dashboard.application.draft.DashboardDraftContractViolation;
import com.things.link.dashboard.application.draft.ValidatedDashboardDraft;
import com.things.link.dashboard.application.draft.ValidatedDashboardModelReference;
import com.things.link.dashboard.domain.DashboardCatalogEntry;
import com.things.link.dashboard.domain.DashboardCreationResult;
import com.things.link.dashboard.domain.DashboardDraft;
import com.things.link.dashboard.domain.DashboardDraftSaveResult;
import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.dashboard.domain.DashboardRepository;
import com.things.link.dashboard.domain.DashboardVersion;
import com.things.link.dashboard.domain.DashboardVersionLookupResult;
import com.things.link.dashboard.domain.DashboardVersionSummary;
import com.things.link.device.application.ThingModelVersionDescriptor;
import com.things.link.device.application.ThingModelVersionDescriptorPort;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.PlanCapacityService;
import com.things.link.project.application.SubscriptionExpansionGuard;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 看板目录、可变草稿与不可变版本历史的Console管理用例。
 *
 * <p>S12-1b2b组合项目授权、持续写许可、严格Schema合同、device公开模型版本摘要端口和
 * 看板仓储。所有角色可读取；OWNER或ADMIN写入时先后两次核验角色，并在两次核验之间持有
 * ACTIVE项目共享许可。S12-1d4a由严格HTTP信封接入管理操作，S12-1d4b增加创建域恢复映射，
 * S12-1e1a增加轻量版本分页与精确版本读取；版本发布、发布指针与删除仍由独立发布服务负责。</p>
 */
@Service
public class DashboardManagementService {

    /** Title合同允许的最大Unicode码点数。 */
    private static final int MAXIMUM_MANAGEMENT_NAME_CODE_POINTS = 80;

    /** HTTP公共幂等键既有长度上限；领域入口同步失败关闭。 */
    private static final int MAXIMUM_IDEMPOTENCY_KEY_LENGTH = 128;

    /** 创建键摘要域与应用创建隔离，禁止跨资源类型复用摘要身份。 */
    private static final byte[] CREATION_KEY_DIGEST_DOMAIN =
            "tc.dashboard-create-key/v1\0".getBytes(StandardCharsets.US_ASCII);

    /** 创建请求摘要域；后续增加字段必须升级版本，不能改变既有重放身份。 */
    private static final byte[] CREATION_REQUEST_DIGEST_DOMAIN =
            "tc.dashboard-create-request/v1\0".getBytes(StandardCharsets.US_ASCII);

    /** 看板目录、草稿、版本及模型关系持久端口。 */
    private final DashboardRepository repository;

    /** 项目成员角色与真实owner tenant公开端口。 */
    private final ProjectService projectService;

    /** 在原业务事务内持有ACTIVE项目写许可的公开端口。 */
    private final ProjectLifecycleAccessService lifecycleAccessService;

    /** 对草稿原文字节、封闭结构和规范models投影执行校验的公开门面。 */
    private final DashboardDraftContractValidator draftValidator;

    /** 只读查询不可变物模型版本最小摘要的device公开应用端口。 */
    private final ThingModelVersionDescriptorPort modelVersionDescriptorPort;

    /** 与业务写入共用事务的持久审计端口。 */
    private final AuditLogService auditLogService;
    /** 所属租户权威容量与扩大门禁。 */
    private final PlanCapacityService capacityService;
    private final SubscriptionExpansionGuard expansionGuard;

    /**
     * 创建看板管理服务。
     *
     * @param repository 看板持久端口
     * @param projectService 项目授权与归属端口
     * @param lifecycleAccessService 项目持续写许可端口
     * @param draftValidator 看板草稿合同校验门面
     * @param modelVersionDescriptorPort device不可变模型版本摘要端口
     * @param auditLogService 同事务审计端口
     * @param capacityService 所属租户权威容量
     * @param expansionGuard 宽限扩大门禁
     */
    public DashboardManagementService(
            DashboardRepository repository,
            ProjectService projectService,
            ProjectLifecycleAccessService lifecycleAccessService,
            DashboardDraftContractValidator draftValidator,
            ThingModelVersionDescriptorPort modelVersionDescriptorPort,
            AuditLogService auditLogService,
            PlanCapacityService capacityService,
            SubscriptionExpansionGuard expansionGuard) {
        this.capacityService = Objects.requireNonNull(capacityService, "capacityService");
        this.expansionGuard = Objects.requireNonNull(expansionGuard, "expansionGuard");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.projectService = Objects.requireNonNull(projectService, "projectService");
        this.lifecycleAccessService = Objects.requireNonNull(
                lifecycleAccessService, "lifecycleAccessService");
        this.draftValidator = Objects.requireNonNull(draftValidator, "draftValidator");
        this.modelVersionDescriptorPort = Objects.requireNonNull(
                modelVersionDescriptorPort, "modelVersionDescriptorPort");
        this.auditLogService = Objects.requireNonNull(auditLogService, "auditLogService");
    }

    /**
     * 原子创建未发布看板目录、revision为0的草稿及其完整模型关系。
     *
     * @param projectId 已选定项目ID
     * @param managementName Console管理名称，按Title合同原样保存
     * @param draftSource content字段未经建树的UTF-8 JSON原文
     * @return 已创建的看板目录
     */
    @Transactional
    public DashboardCatalogEntry create(UUID projectId, String managementName, byte[] draftSource) {
        UUID ownerTenantId = requireWrite(projectId);
        String validatedManagementName = requireManagementName(managementName);
        ValidatedDashboardDraft validatedDraft = validateDraft("0", draftSource);
        validateModelReferences(projectId, validatedDraft.models());
        UUID accountId = currentAccountId();
        return createInitialAggregate(ownerTenantId, projectId, accountId,
                validatedManagementName, validatedDraft, null, null);
    }

    /**
     * 以必填幂等键创建看板，或恢复同一账号在同一项目中首次生成的稳定身份。
     *
     * <p>S12-1d4b先取得ACTIVE项目许可，再以tenant/project/account/key摘要的事务级锁串行空缺映射。
     * 首次请求仍完整校验Schema及精确物模型版本；既有映射只核对请求摘要和锁内目录，不因外部模型
     * 后续变化改变已完成结果。同键异请求返回10009，结果已软删返回10014且不重建。</p>
     *
     * @param projectId 已选定项目ID
     * @param idempotencyKey 必填原始Idempotency-Key；只持久化域分离摘要
     * @param managementName Console管理名称，按Title合同原样保存
     * @param draftSource content字段未经建树的UTF-8 JSON原文
     * @return 首次创建或同请求恢复的看板目录
     */
    @Transactional
    public DashboardCatalogEntry createIdempotent(
            UUID projectId, String idempotencyKey, String managementName, byte[] draftSource) {
        UUID ownerTenantId = requireWrite(projectId);
        String validatedKey = requireIdempotencyKey(idempotencyKey);
        UUID accountId = currentAccountId();
        byte[] draftSnapshot = draftSource == null ? null : Arrays.copyOf(draftSource, draftSource.length);
        String keyDigest = digestCreationKey(validatedKey);
        String requestDigest = digestCreationRequest(managementName, draftSnapshot);

        repository.lockCreationRequest(ownerTenantId, projectId, accountId, keyDigest);
        DashboardCreationResult existing = repository.findCreationResult(
                ownerTenantId, projectId, accountId, keyDigest).orElse(null);
        if (existing != null) {
            if (!existing.requestDigest().equals(requestDigest)) {
                throw new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT,
                        "相同的Idempotency-Key不能用于内容不同的看板创建请求");
            }
            DashboardCatalogEntry restored = repository.findCreationDashboard(
                            projectId, existing.dashboardId())
                    .orElseThrow(() -> new IllegalStateException("看板创建恢复映射缺少目标目录"));
            requireRestoredIdentity(existing, restored);
            if (restored.deletedAt() != null) {
                throw new BusinessException(CommonErrorCode.IDEMPOTENCY_RESULT_NOT_REPLAYABLE);
            }
            return restored;
        }

        String validatedManagementName = requireManagementName(managementName);
        ValidatedDashboardDraft validatedDraft = validateDraft("0", draftSnapshot);
        validateModelReferences(projectId, validatedDraft.models());
        return createInitialAggregate(ownerTenantId, projectId, accountId,
                validatedManagementName, validatedDraft, keyDigest, requestDigest);
    }

    /** 构造一次看板身份，并把目录、草稿、模型关系、恢复映射和审计写入同一事务。 */
    private DashboardCatalogEntry createInitialAggregate(
            UUID ownerTenantId, UUID projectId, UUID accountId, String managementName,
            ValidatedDashboardDraft validatedDraft, String keyDigest, String requestDigest) {
        repository.lockTenantCapacity(ownerTenantId);
        expansionGuard.requireExpansionAllowed(ownerTenantId);
        long limit = capacityService.dashboardsLimit(ownerTenantId, projectId);
        if (repository.countTenantDashboards(ownerTenantId, projectId) >= limit) {
            throw new BusinessException(DashboardErrorCode.DASHBOARD_QUOTA_EXCEEDED);
        }
        UUID dashboardId = Uuid7.generate();
        // PostgreSQL timestamptz固定为微秒精度；写入前规范化，保证首次内存回执与数据库恢复回执逐字段相同。
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        DashboardDraft draft = new DashboardDraft(
                dashboardId, ownerTenantId, projectId, validatedDraft.content(), 0,
                accountId, now, now, validatedDraft.persistenceReferences());
        DashboardCatalogEntry dashboard = new DashboardCatalogEntry(
                dashboardId, ownerTenantId, projectId, managementName, 0, null,
                accountId, accountId, now, now, null);
        if (keyDigest == null) {
            repository.create(dashboard, draft);
        } else {
            repository.createIdempotent(dashboard, draft, new DashboardCreationResult(
                    ownerTenantId, projectId, accountId, keyDigest,
                    Objects.requireNonNull(requestDigest, "requestDigest"), dashboardId));
        }
        audit(ownerTenantId, projectId, accountId, dashboardId,
                "dashboard.created", Map.of("draftRevision", "0"));
        return dashboard;
    }

    /**
     * 分页列出当前项目内可见看板目录。
     *
     * @param projectId 已选定项目ID
     * @param cursor 上一页返回的不透明游标；首页为空
     * @param limit 1至200的页大小
     * @return 未软删除看板目录页
     */
    @Transactional(readOnly = true)
    public CursorPage<DashboardCatalogEntry> list(UUID projectId, String cursor, int limit) {
        requireRead(projectId);
        return repository.page(projectId, cursor, limit);
    }

    /**
     * 读取当前项目内一个可见看板目录。
     *
     * @param projectId 已选定项目ID
     * @param dashboardId 看板ID
     * @return 看板目录
     * @throws BusinessException 看板不存在、跨项目或已软删除时为60034
     */
    @Transactional(readOnly = true)
    public DashboardCatalogEntry find(UUID projectId, UUID dashboardId) {
        requireRead(projectId);
        return repository.find(projectId, dashboardId).orElseThrow(DashboardManagementService::notFound);
    }

    /**
     * 读取当前项目内一个可见看板的可变草稿及模型关系。
     *
     * @param projectId 已选定项目ID
     * @param dashboardId 看板ID
     * @return 防御复制的草稿事实
     * @throws BusinessException 看板不存在、跨项目或已软删除时为60034
     */
    @Transactional(readOnly = true)
    public DashboardDraft getDraft(UUID projectId, UUID dashboardId) {
        requireRead(projectId);
        return repository.findDraft(projectId, dashboardId)
                .orElseThrow(DashboardManagementService::notFound);
    }

    /**
     * 按版本号倒序分页读取一个可见看板的轻量不可变历史。
     *
     * @param projectId 已选定项目ID
     * @param dashboardId 看板ID
     * @param cursor 上一页返回的不透明版本号游标；首页为空
     * @param limit 1至200的页大小
     * @return 不装载完整Schema、派生清单和模型关系的版本页
     * @throws BusinessException 看板不存在、跨项目或已软删除时为60034
     */
    @Transactional(readOnly = true)
    public CursorPage<DashboardVersionSummary> listVersions(
            UUID projectId, UUID dashboardId, String cursor, int limit) {
        requireRead(projectId);
        return repository.pageVersions(projectId, dashboardId, cursor, limit)
                .orElseThrow(DashboardManagementService::notFound);
    }

    /**
     * 读取一个可见看板的精确不可变历史版本。
     *
     * @param projectId 已选定项目ID
     * @param dashboardId 看板ID
     * @param versionId 精确版本ID
     * @return 包含完整Schema和派生清单的不可变版本
     * @throws BusinessException 版本不存在、归属不符或随目录软删除而不可见时为60047
     */
    @Transactional(readOnly = true)
    public DashboardVersion getVersion(UUID projectId, UUID dashboardId, UUID versionId) {
        requireRead(projectId);
        DashboardVersionLookupResult result = repository.findVersionForManagement(
                projectId, dashboardId, versionId);
        return switch (result.status()) {
            case FOUND -> result.foundVersion().orElseThrow(
                    () -> new IllegalStateException("看板版本查询成功结果缺少版本事实"));
            case DASHBOARD_NOT_FOUND -> throw notFound();
            case VERSION_NOT_FOUND -> throw versionNotFound();
        };
    }

    /**
     * 修改当前项目内一个可见看板的Console管理名称。
     *
     * @param projectId 已选定项目ID
     * @param dashboardId 看板ID
     * @param managementName 新管理名称，按Title合同原样保存
     * @return 更新后的看板目录
     */
    @Transactional
    public DashboardCatalogEntry rename(UUID projectId, UUID dashboardId, String managementName) {
        UUID ownerTenantId = requireWrite(projectId);
        String validatedName = requireManagementName(managementName);
        UUID accountId = currentAccountId();
        if (!repository.rename(projectId, dashboardId, validatedName, accountId, Instant.now())) {
            throw notFound();
        }
        DashboardCatalogEntry renamed = repository.find(projectId, dashboardId)
                .orElseThrow(DashboardManagementService::notFound);
        audit(ownerTenantId, projectId, accountId, dashboardId, "dashboard.renamed", Map.of());
        return renamed;
    }

    /**
     * 以调用方读取的revision完整替换看板草稿和模型关系。
     *
     * <p>严格Schema门面先从同一规范内容投影models；device端口随后逐项精确确认同项目不可变版本、
     * 摘要算法、摘要和Profile。仓储在目录行锁内原子分类CAS，并在同一事务整组替换关系。</p>
     *
     * @param projectId 已选定项目ID
     * @param dashboardId 看板ID
     * @param expectedRevision 调用方读取的规范十进制revision
     * @param draftSource content字段未经建树的UTF-8 JSON原文
     * @return 保存后revision恰好加一的完整草稿事实
     */
    @Transactional
    public DashboardDraft saveDraft(
            UUID projectId, UUID dashboardId, String expectedRevision, byte[] draftSource) {
        UUID ownerTenantId = requireWrite(projectId);
        ValidatedDashboardDraft validated = validateDraft(expectedRevision, draftSource);
        validateModelReferences(projectId, validated.models());
        UUID accountId = currentAccountId();
        DashboardDraftSaveResult result = repository.saveDraft(
                projectId, dashboardId, validated.expectedRevision(), validated.content(),
                validated.persistenceReferences(), accountId, Instant.now());
        DashboardDraft saved = switch (result.status()) {
            case SAVED -> result.savedDraft().orElseThrow(
                    () -> new IllegalStateException("看板草稿保存结果缺少成功事实"));
            case NOT_FOUND -> throw notFound();
            case REVISION_CONFLICT, REVISION_EXHAUSTED -> throw revisionConflict();
        };
        audit(ownerTenantId, projectId, accountId, dashboardId,
                "dashboard.draft_saved", Map.of("revision", Long.toString(saved.revision())));
        return saved;
    }

    /** 读取入口只确认当前账号是项目成员；四种项目角色均可读取管理事实。 */
    private void requireRead(UUID projectId) {
        projectService.requireRoleInProject(projectId);
    }

    /**
     * 在原事务中依次执行锁前角色检查、真实归属读取、ACTIVE许可和锁后角色复核。
     *
     * @param projectId 已选定项目ID
     * @return 项目持久事实中的owner tenant
     */
    private UUID requireWrite(UUID projectId) {
        requireManagementRole(projectId);
        UUID ownerTenantId = projectService.requireProjectTenant(projectId);
        lifecycleAccessService.requireActiveForWrite(ownerTenantId, projectId);
        requireManagementRole(projectId);
        return ownerTenantId;
    }

    /** 非OWNER/ADMIN项目成员使用看板领域60035；非成员仍由项目端口统一返回50001。 */
    private void requireManagementRole(UUID projectId) {
        ProjectRole role = projectService.requireRoleInProject(projectId);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) {
            throw new BusinessException(DashboardErrorCode.DASHBOARD_MANAGEMENT_FORBIDDEN);
        }
    }

    /** 草稿合同异常只输出稳定reason和安全路径，不回显解析器消息或未知字段原文。 */
    private ValidatedDashboardDraft validateDraft(String expectedRevision, byte[] source) {
        try {
            return draftValidator.validate(expectedRevision, source);
        } catch (DashboardDraftContractViolation violation) {
            throw new BusinessException(
                    DashboardErrorCode.DASHBOARD_DRAFT_INVALID,
                    DashboardErrorCode.DASHBOARD_DRAFT_INVALID.defaultMessage(),
                    List.of(violation.reasonCode() + "@" + violation.path()));
        }
    }

    /**
     * 经device公开端口逐项核验同项目模型版本及不可变摘要，不查询或泄露模型正文。
     *
     * <p>空结果、错项目和任一摘要字段不匹配都返回同一稳定错误，避免通过看板草稿接口枚举其他
     * 项目中的模型版本或观察实际摘要。</p>
     */
    private void validateModelReferences(
            UUID projectId, List<ValidatedDashboardModelReference> expectedReferences) {
        for (ValidatedDashboardModelReference expected : expectedReferences) {
            ThingModelVersionDescriptor actual = modelVersionDescriptorPort
                    .find(projectId, expected.thingModelVersionId())
                    .orElseThrow(DashboardManagementService::invalidModelReference);
            if (!expected.thingModelVersionId().equals(actual.versionId())
                    || !projectId.equals(actual.projectId())
                    || !expected.digestAlgorithm().equals(actual.digestAlgorithm())
                    || !expected.digest().equals(actual.digest())
                    || !expected.profile().equals(actual.profile())) {
                throw invalidModelReference();
            }
        }
    }

    /** Title不隐式trim或规范化；格式错误属于普通参数错误，不冒充草稿结构错误。 */
    private static String requireManagementName(String managementName) {
        if (managementName == null) {
            throw invalidManagementName();
        }
        int length = managementName.codePointCount(0, managementName.length());
        if (length < 1 || length > MAXIMUM_MANAGEMENT_NAME_CODE_POINTS
                || managementName.codePoints().allMatch(
                        codePoint -> Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint))
                || managementName.codePoints().anyMatch(DashboardManagementService::isControlCharacter)) {
            throw invalidManagementName();
        }
        return managementName;
    }

    /** 缺失、空白或超过公共上限的键不能退化为非幂等创建。 */
    private static String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > MAXIMUM_IDEMPOTENCY_KEY_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "看板创建必须提供有效Idempotency-Key");
        }
        return idempotencyKey;
    }

    /** 原始键只进入域分离且长度分帧的SHA-256，不持久化可关联客户端文本。 */
    private static String digestCreationKey(String idempotencyKey) {
        MessageDigest digest = sha256();
        digest.update(CREATION_KEY_DIGEST_DOMAIN);
        updateFramed(digest, idempotencyKey.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 管理名称UTF-8与content原始字节分别加四字节大端长度帧，避免字段拼接歧义。 */
    private static String digestCreationRequest(String managementName, byte[] draftSource) {
        MessageDigest digest = sha256();
        digest.update(CREATION_REQUEST_DIGEST_DOMAIN);
        updateFramed(digest, managementName == null ? null : managementName.getBytes(StandardCharsets.UTF_8));
        updateFramed(digest, draftSource);
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 写入可空字节串的四字节大端长度与内容，null以-1参与稳定摘要。 */
    private static void updateFramed(MessageDigest digest, byte[] value) {
        int length = value == null ? -1 : value.length;
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(length).array());
        if (value != null) {
            digest.update(value);
        }
    }

    /** Java 21必须提供SHA-256；运行时缺失时失败关闭。 */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行时缺少SHA-256", exception);
        }
    }

    /** 恢复目录必须与映射全部身份轴一致；完整性漂移按内部错误回滚。 */
    private static void requireRestoredIdentity(
            DashboardCreationResult result, DashboardCatalogEntry dashboard) {
        if (!result.tenantId().equals(dashboard.tenantId())
                || !result.projectId().equals(dashboard.projectId())
                || !result.dashboardId().equals(dashboard.id())
                || !result.accountId().equals(dashboard.createdBy())) {
            throw new IllegalStateException("看板创建恢复目录身份与映射不一致");
        }
    }

    /** C0与C1均是Title合同禁止的控制字符，不能只检查Java ISO控制别名。 */
    private static boolean isControlCharacter(int codePoint) {
        return codePoint <= 0x1F || codePoint == 0x7F || (codePoint >= 0x80 && codePoint <= 0x9F);
    }

    /** @return 不回显非法原文的管理名称参数错误 */
    private static BusinessException invalidManagementName() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER, "看板管理名称必须是1至80码点的非空白纯文本");
    }

    /** @return 当前已认证Console账号；缺失上下文属于调用链配置错误 */
    private static UUID currentAccountId() {
        return TenantContext.require().accountId();
    }

    /** @return 不区分不存在、跨项目和软删除的看板不可见错误 */
    private static BusinessException notFound() {
        return new BusinessException(DashboardErrorCode.DASHBOARD_NOT_FOUND);
    }

    /** @return 不区分不存在、跨项目、跨看板和软删除的版本不可见错误 */
    private static BusinessException versionNotFound() {
        return new BusinessException(DashboardErrorCode.DASHBOARD_VERSION_NOT_FOUND);
    }

    /** @return 统一旧revision与Long耗尽的草稿CAS冲突 */
    private static BusinessException revisionConflict() {
        return new BusinessException(DashboardErrorCode.DASHBOARD_DRAFT_CONFLICT);
    }

    /** @return 不携带模型ID、实际归属或实际摘要的统一模型引用错误 */
    private static BusinessException invalidModelReference() {
        return new BusinessException(DashboardErrorCode.DASHBOARD_MODEL_REFERENCE_INVALID);
    }

    /** 业务成功后记录最小审计详情；审计失败由同一事务回滚业务写入。 */
    private void audit(UUID tenantId, UUID projectId, UUID accountId, UUID dashboardId,
                       String action, Map<String, ?> details) {
        auditLogService.record(new AuditLogEntry(
                tenantId, projectId, accountId, "dashboard", dashboardId, action, details));
    }
}
