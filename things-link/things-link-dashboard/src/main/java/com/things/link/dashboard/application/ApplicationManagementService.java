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
 * 应用目录、可变草稿与不可变版本历史的Console管理用例。
 *
 * <p>S12-1a2b组合项目授权、持续写许可、草稿合同与应用仓储；S12-1d2b在同一边界增加
 * 必填幂等键创建及稳定身份恢复；S12-1e2b增加只读版本历史。所有写操作在原事务先检查角色，
 * 再持有ACTIVE项目共享许可并复核角色，避免等待锁期间发生成员降权后仍继续写入。</p>
 */
@Service
public class ApplicationManagementService {

    /** ADR0100固定的随机appKey数据库碰撞最大创建尝试数。 */
    private static final int MAXIMUM_APPLICATION_KEY_ATTEMPTS = 5;

    /** Title合同允许的最大Unicode码点数。 */
    private static final int MAXIMUM_MANAGEMENT_NAME_CODE_POINTS = 80;

    /** HTTP公共幂等键既有长度上限；领域入口同样失败关闭，避免非HTTP调用写入不可恢复身份。 */
    private static final int MAXIMUM_IDEMPOTENCY_KEY_LENGTH = 128;

    /** 创建键摘要域；与请求摘要分离，避免同一原文在两个字段间被误比较。 */
    private static final byte[] CREATION_KEY_DIGEST_DOMAIN =
            "tc.application-create-key/v1\0".getBytes(StandardCharsets.US_ASCII);

    /** 创建请求摘要域；后续增加字段必须升级版本，不能静默改变既有重放身份。 */
    private static final byte[] CREATION_REQUEST_DIGEST_DOMAIN =
            "tc.application-create-request/v1\0".getBytes(StandardCharsets.US_ASCII);

    /** 应用目录、草稿与不可变版本历史持久端口。 */
    private final ApplicationRepository repository;

    /** 项目成员角色与真实owner tenant公开端口。 */
    private final ProjectService projectService;

    /** 在原业务事务内持有ACTIVE项目写许可的公开端口。 */
    private final ProjectLifecycleAccessService lifecycleAccessService;

    /** 对草稿原文字节和封闭结构执行精确校验的端口。 */
    private final ApplicationDraftContractValidator draftValidator;

    /** 生成密码学随机appKey候选的端口。 */
    private final ApplicationKeyGenerator keyGenerator;

    /** 与业务写入共用事务的持久审计端口。 */
    private final AuditLogService auditLogService;

    /**
     * 创建应用管理服务。
     *
     * @param repository 应用持久端口
     * @param projectService 项目授权与归属端口
     * @param lifecycleAccessService 项目持续写许可端口
     * @param draftValidator 草稿原文合同校验端口
     * @param keyGenerator 随机appKey生成端口
     * @param auditLogService 同事务审计端口
     */
    public ApplicationManagementService(
            ApplicationRepository repository,
            ProjectService projectService,
            ProjectLifecycleAccessService lifecycleAccessService,
            ApplicationDraftContractValidator draftValidator,
            ApplicationKeyGenerator keyGenerator,
            AuditLogService auditLogService) {
        this.repository = java.util.Objects.requireNonNull(repository, "repository");
        this.projectService = java.util.Objects.requireNonNull(projectService, "projectService");
        this.lifecycleAccessService = java.util.Objects.requireNonNull(
                lifecycleAccessService, "lifecycleAccessService");
        this.draftValidator = java.util.Objects.requireNonNull(draftValidator, "draftValidator");
        this.keyGenerator = java.util.Objects.requireNonNull(keyGenerator, "keyGenerator");
        this.auditLogService = java.util.Objects.requireNonNull(auditLogService, "auditLogService");
    }

    /**
     * 创建未发布应用目录及revision为0的独立草稿。
     *
     * <p>数据库仅把精确appKey冲突翻译成可重试异常；五次尝试共用同一应用ID、草稿、
     * 操作者和服务端时刻，避免一次逻辑创建留下多个候选身份。</p>
     *
     * @param projectId 已选定项目ID
     * @param managementName Console管理名称，按Title合同原样保存
     * @param draftSource content字段未经建树的UTF-8 JSON原文
     * @return 已创建的应用目录
     */
    @Transactional
    public ApplicationCatalogEntry create(UUID projectId, String managementName, byte[] draftSource) {
        UUID ownerTenantId = requireWrite(projectId);
        String validatedManagementName = requireManagementName(managementName);
        ValidatedApplicationDraft validatedDraft = validateDraft("0", draftSource);
        UUID accountId = currentAccountId();
        return createInitialAggregate(
                ownerTenantId, projectId, accountId, validatedManagementName, validatedDraft, null, null);
    }

    /**
     * 以必填幂等键创建应用，或恢复同一账号在同一项目中首次生成的稳定身份。
     *
     * <p>S12-1d2b先取得ACTIVE项目许可，再以tenant/project/account/key摘要的事务级advisory lock
     * 串行不存在的映射行。同键同请求直接返回原目录且不重复审计；异请求返回10009，原结果已软删
     * 返回10014且不创建替代身份。首次创建的目录、草稿、恢复映射与审计共享本事务。</p>
     *
     * @param projectId 已选定项目ID
     * @param idempotencyKey 必填原始Idempotency-Key；只持久化域分离摘要
     * @param managementName Console管理名称，按Title合同原样保存
     * @param draftSource content字段未经建树的UTF-8 JSON原文
     * @return 首次创建或同请求恢复的应用目录
     */
    @Transactional
    public ApplicationCatalogEntry createIdempotent(
            UUID projectId, String idempotencyKey, String managementName, byte[] draftSource) {
        UUID ownerTenantId = requireWrite(projectId);
        String validatedKey = requireIdempotencyKey(idempotencyKey);
        UUID accountId = currentAccountId();
        byte[] draftSnapshot = draftSource == null ? null : Arrays.copyOf(draftSource, draftSource.length);
        String keyDigest = digestCreationKey(validatedKey);
        String requestDigest = digestCreationRequest(managementName, draftSnapshot);

        repository.lockCreationRequest(ownerTenantId, projectId, accountId, keyDigest);
        ApplicationCreationResult existing = repository.findCreationResult(
                ownerTenantId, projectId, accountId, keyDigest).orElse(null);
        if (existing != null) {
            if (!existing.requestDigest().equals(requestDigest)) {
                throw new BusinessException(
                        CommonErrorCode.RESOURCE_STATE_CONFLICT,
                        "相同的Idempotency-Key不能用于内容不同的应用创建请求");
            }
            ApplicationCatalogEntry restored = repository.findCreationApplication(
                            projectId, existing.applicationId())
                    .orElseThrow(() -> new IllegalStateException("应用创建恢复映射缺少目标目录"));
            requireRestoredIdentity(existing, restored);
            if (restored.deletedAt() != null) {
                throw new BusinessException(CommonErrorCode.IDEMPOTENCY_RESULT_NOT_REPLAYABLE);
            }
            return restored;
        }

        String validatedManagementName = requireManagementName(managementName);
        ValidatedApplicationDraft validatedDraft = validateDraft("0", draftSnapshot);
        return createInitialAggregate(
                ownerTenantId, projectId, accountId, validatedManagementName, validatedDraft,
                keyDigest, requestDigest);
    }

    /** 构造一次逻辑身份并仅在appKey精确碰撞时更换公开定位符。 */
    private ApplicationCatalogEntry createInitialAggregate(
            UUID ownerTenantId,
            UUID projectId,
            UUID accountId,
            String managementName,
            ValidatedApplicationDraft validatedDraft,
            String idempotencyKeyDigest,
            String requestDigest) {
        UUID applicationId = Uuid7.generate();
        // PostgreSQL timestamptz固定为微秒精度；写入前规范化，保证首次内存回执与数据库恢复回执逐字段相同。
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ApplicationDraft draft = new ApplicationDraft(
                applicationId, ownerTenantId, projectId, validatedDraft.content(), 0, accountId, now, now);

        ApplicationKeyCollisionException lastCollision = null;
        for (int attempt = 0; attempt < MAXIMUM_APPLICATION_KEY_ATTEMPTS; attempt++) {
            ApplicationCatalogEntry application = new ApplicationCatalogEntry(
                    applicationId, ownerTenantId, projectId, keyGenerator.generate(), managementName,
                    0, null, accountId, accountId, now, now, null);
            try {
                if (idempotencyKeyDigest == null) {
                    repository.create(application, draft);
                } else {
                    repository.createIdempotent(application, draft, new ApplicationCreationResult(
                            ownerTenantId, projectId, accountId, idempotencyKeyDigest,
                            Objects.requireNonNull(requestDigest, "requestDigest"), applicationId));
                }
                audit(ownerTenantId, projectId, accountId, applicationId,
                        "application.created", Map.of("draftRevision", "0"));
                return application;
            } catch (ApplicationKeyCollisionException collision) {
                // 只有仓储对(app_key)精确冲突的分类可消耗预算；其他数据库异常必须原样传播。
                lastCollision = collision;
            }
        }
        throw new IllegalStateException("应用公开定位符连续五次发生碰撞", lastCollision);
    }

    /**
     * 分页列出当前项目内可见应用目录。
     *
     * @param projectId 已选定项目ID
     * @param cursor 上一页返回的不透明游标；首页为空
     * @param limit 1至200的页大小
     * @return 未软删除应用目录页
     */
    @Transactional(readOnly = true)
    public CursorPage<ApplicationCatalogEntry> list(UUID projectId, String cursor, int limit) {
        requireRead(projectId);
        return repository.page(projectId, cursor, limit);
    }

    /**
     * 读取当前项目内一个可见应用目录。
     *
     * @param projectId 已选定项目ID
     * @param applicationId 应用内部ID
     * @return 应用目录
     * @throws BusinessException 应用不存在、跨项目或已软删除时为60030
     */
    @Transactional(readOnly = true)
    public ApplicationCatalogEntry find(UUID projectId, UUID applicationId) {
        requireRead(projectId);
        return repository.find(projectId, applicationId).orElseThrow(ApplicationManagementService::notFound);
    }

    /**
     * 读取当前项目内一个可见应用的可变草稿。
     *
     * @param projectId 已选定项目ID
     * @param applicationId 应用内部ID
     * @return 防御复制的草稿事实
     * @throws BusinessException 应用不存在、跨项目或已软删除时为60030
     */
    @Transactional(readOnly = true)
    public ApplicationDraft getDraft(UUID projectId, UUID applicationId) {
        requireRead(projectId);
        return repository.findDraft(projectId, applicationId)
                .orElseThrow(ApplicationManagementService::notFound);
    }

    /**
     * 按版本号倒序分页读取一个可见应用的轻量不可变历史。
     *
     * @param projectId 已选定项目ID
     * @param applicationId 应用ID
     * @param cursor 上一页返回的不透明版本号游标；首页为空
     * @param limit 1至200的页大小
     * @return 不装载完整ApplicationSnapshot的版本页
     * @throws BusinessException 应用不存在、跨项目或已软删除时为60030
     */
    @Transactional(readOnly = true)
    public CursorPage<ApplicationVersionSummary> listVersions(
            UUID projectId, UUID applicationId, String cursor, int limit) {
        requireRead(projectId);
        return repository.pageVersions(projectId, applicationId, cursor, limit)
                .orElseThrow(ApplicationManagementService::notFound);
    }

    /**
     * 读取一个可见应用的精确不可变历史版本。
     *
     * @param projectId 已选定项目ID
     * @param applicationId 应用ID
     * @param versionId 精确版本ID
     * @return 包含完整ApplicationSnapshot的不可变版本
     * @throws BusinessException 应用不可见时为60030；可见应用下版本不存在或归属不符时为60048
     */
    @Transactional(readOnly = true)
    public ApplicationVersion getVersion(UUID projectId, UUID applicationId, UUID versionId) {
        requireRead(projectId);
        ApplicationVersionLookupResult result = repository.findVersionForManagement(
                projectId, applicationId, versionId);
        return switch (result.status()) {
            case FOUND -> result.foundVersion().orElseThrow(
                    () -> new IllegalStateException("应用版本查询成功结果缺少版本事实"));
            case APPLICATION_NOT_FOUND -> throw notFound();
            case VERSION_NOT_FOUND -> throw versionNotFound();
        };
    }

    /**
     * 修改当前项目内一个可见应用的Console管理名称。
     *
     * @param projectId 已选定项目ID
     * @param applicationId 应用内部ID
     * @param managementName 新管理名称，按Title合同原样保存
     * @return 更新后的应用目录
     */
    @Transactional
    public ApplicationCatalogEntry rename(UUID projectId, UUID applicationId, String managementName) {
        UUID ownerTenantId = requireWrite(projectId);
        String validatedName = requireManagementName(managementName);
        UUID accountId = currentAccountId();
        if (!repository.rename(projectId, applicationId, validatedName, accountId, Instant.now())) {
            throw notFound();
        }
        ApplicationCatalogEntry renamed = repository.find(projectId, applicationId)
                .orElseThrow(ApplicationManagementService::notFound);
        audit(ownerTenantId, projectId, accountId, applicationId, "application.renamed", Map.of());
        return renamed;
    }

    /**
     * 以调用方读取的revision完整替换应用草稿。
     *
     * <p>仓储在目录行锁保护下原子返回成功、不存在、revision冲突或递增耗尽；服务不在失败后
     * 补查并猜测原因，因此并发保存和删除不能改变同一次CAS的稳定分类。</p>
     *
     * @param projectId 已选定项目ID
     * @param applicationId 应用内部ID
     * @param expectedRevision 调用方读取的规范十进制revision
     * @param draftSource content字段未经建树的UTF-8 JSON原文
     * @return 保存后revision恰好加一的完整草稿事实
     */
    @Transactional
    public ApplicationDraft saveDraft(
            UUID projectId, UUID applicationId, String expectedRevision, byte[] draftSource) {
        UUID ownerTenantId = requireWrite(projectId);
        ValidatedApplicationDraft validated = validateDraft(expectedRevision, draftSource);
        UUID accountId = currentAccountId();
        ApplicationDraftSaveResult result = repository.saveDraft(
                projectId, applicationId, validated.expectedRevision(), validated.content(),
                accountId, Instant.now());
        ApplicationDraft saved = switch (result.status()) {
            case SAVED -> result.savedDraft().orElseThrow(
                    () -> new IllegalStateException("草稿保存结果缺少成功事实"));
            case NOT_FOUND -> throw notFound();
            case REVISION_CONFLICT, REVISION_EXHAUSTED -> throw revisionConflict();
        };
        audit(ownerTenantId, projectId, accountId, applicationId,
                "application.draft_saved", Map.of("revision", Long.toString(saved.revision())));
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

    /** 非OWNER/ADMIN项目成员使用应用领域60031；非成员仍由项目端口统一返回50001。 */
    private void requireManagementRole(UUID projectId) {
        ProjectRole role = projectService.requireRoleInProject(projectId);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) {
            throw new BusinessException(ApplicationErrorCode.APPLICATION_MANAGEMENT_FORBIDDEN);
        }
    }

    /** 草稿合同异常只输出稳定reason和安全路径，不回显解析器消息或未知字段原文。 */
    private ValidatedApplicationDraft validateDraft(String expectedRevision, byte[] source) {
        try {
            return draftValidator.validate(expectedRevision, source);
        } catch (ApplicationDraftContractViolation violation) {
            throw new BusinessException(
                    ApplicationErrorCode.APPLICATION_DRAFT_INVALID,
                    ApplicationErrorCode.APPLICATION_DRAFT_INVALID.defaultMessage(),
                    List.of(violation.reason().name() + "@" + violation.path()));
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
                || managementName.codePoints().anyMatch(ApplicationManagementService::isControlCharacter)) {
            throw invalidManagementName();
        }
        return managementName;
    }

    /** Idempotency-Key是创建恢复身份的一部分，缺失、空白或超过公共上限都不能退化为非幂等创建。 */
    private static String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > MAXIMUM_IDEMPOTENCY_KEY_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "应用创建必须提供有效Idempotency-Key");
        }
        return idempotencyKey;
    }

    /** 原始键只进入带域标签和长度帧的SHA-256，不把可关联客户端文本写入业务表。 */
    private static String digestCreationKey(String idempotencyKey) {
        MessageDigest digest = sha256();
        digest.update(CREATION_KEY_DIGEST_DOMAIN);
        updateFramed(digest, idempotencyKey.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 创建请求按管理名称UTF-8和content原始字节分别加四字节大端长度帧。
     *
     * <p>分帧避免字段拼接歧义；null使用-1长度，仅用于让非法新请求进入既有校验分类或与历史请求稳定不等。</p>
     */
    private static String digestCreationRequest(String managementName, byte[] draftSource) {
        MessageDigest digest = sha256();
        digest.update(CREATION_REQUEST_DIGEST_DOMAIN);
        updateFramed(digest, managementName == null ? null : managementName.getBytes(StandardCharsets.UTF_8));
        updateFramed(digest, draftSource);
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 写入一个可空字节串的四字节大端长度与内容，确保摘要输入可唯一反解字段边界。 */
    private static void updateFramed(MessageDigest digest, byte[] value) {
        int length = value == null ? -1 : value.length;
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(length).array());
        if (value != null) {
            digest.update(value);
        }
    }

    /** Java 21必须提供SHA-256；缺失表示运行时基线破坏，不能降级为其他算法。 */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行时缺少SHA-256", exception);
        }
    }

    /** 恢复目录必须仍与映射三轴及稳定ID一致；数据库完整性漂移按内部错误回滚。 */
    private static void requireRestoredIdentity(
            ApplicationCreationResult result, ApplicationCatalogEntry application) {
        if (!result.tenantId().equals(application.tenantId())
                || !result.projectId().equals(application.projectId())
                || !result.applicationId().equals(application.id())
                || !result.accountId().equals(application.createdBy())) {
            throw new IllegalStateException("应用创建恢复目录身份与映射不一致");
        }
    }

    /** C0与C1均是Title合同禁止的控制字符，不能只检查Java ISO控制别名。 */
    private static boolean isControlCharacter(int codePoint) {
        return codePoint <= 0x1F || codePoint == 0x7F || (codePoint >= 0x80 && codePoint <= 0x9F);
    }

    /** @return 不回显非法原文的管理名称参数错误 */
    private static BusinessException invalidManagementName() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER, "应用管理名称必须是1至80码点的非空白纯文本");
    }

    /** @return 当前已认证Console账号；缺失上下文属于调用链配置错误 */
    private static UUID currentAccountId() {
        return TenantContext.require().accountId();
    }

    /** @return 不区分不存在、跨项目和软删除的应用不可见错误 */
    private static BusinessException notFound() {
        return new BusinessException(ApplicationErrorCode.APPLICATION_NOT_FOUND);
    }

    /** @return 当前应用可见但精确普通历史版本不可见的独立错误 */
    private static BusinessException versionNotFound() {
        return new BusinessException(ApplicationErrorCode.APPLICATION_VERSION_NOT_FOUND);
    }

    /** @return 统一旧revision与Long耗尽的草稿CAS冲突 */
    private static BusinessException revisionConflict() {
        return new BusinessException(ApplicationErrorCode.APPLICATION_DRAFT_CONFLICT);
    }

    /** 业务成功后记录最小审计详情；审计失败由同一事务回滚业务写入。 */
    private void audit(UUID tenantId, UUID projectId, UUID accountId, UUID applicationId,
                       String action, Map<String, ?> details) {
        auditLogService.record(new AuditLogEntry(
                tenantId, projectId, accountId, "application", applicationId, action, details));
    }
}
