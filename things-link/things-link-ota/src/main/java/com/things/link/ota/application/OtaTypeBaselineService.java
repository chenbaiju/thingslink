package com.things.link.ota.application;

import com.things.link.device.application.OtaDeviceTypeIdentityPort;
import com.things.link.ota.domain.OtaTypeBaselineErrorCode;
import com.things.link.ota.domain.OtaTypeBaselineRepository;
import com.things.link.ota.domain.OtaTypeBaselineState;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 受控类型声明的原子登记，不从登记成功推导设备报告或真实硬件资格。 */
@Service
public class OtaTypeBaselineService {
    /** 当前头与不可变历史同事务持久化。 */
    private final OtaTypeBaselineRepository repository;
    /** 启动时固定的受控声明，不能由HTTP提供能力正文。 */
    private final OtaTypeBaselineSource source;
    /** 设备域拥有的真实类型身份投影。 */
    private final OtaDeviceTypeIdentityPort types;
    /** 当前管理成员及真实项目租户。 */
    private final ProjectService projects;
    /** 在原事务保持项目ACTIVE许可。 */
    private final ProjectLifecycleAccessService lifecycle;
    /** 状态与登记审计同事务提交。 */
    private final AuditLogService audit;
    /** 持久字节仍需严格规范校验，数据库字段不是来源证明。 */
    private final OtaTypeBaselineCodec codec = new OtaTypeBaselineCodec();

    /** 显式装配受控来源和领域端口，不连接外部制造或签名服务。 */
    public OtaTypeBaselineService(OtaTypeBaselineRepository repository, OtaTypeBaselineSource source,
            OtaDeviceTypeIdentityPort types, ProjectService projects,
            ProjectLifecycleAccessService lifecycle, AuditLogService audit) {
        this.repository = repository;
        this.source = source;
        this.types = types;
        this.projects = projects;
        this.lifecycle = lifecycle;
        this.audit = audit;
    }

    /** 元数据读取不代表本进程受控配置或设备当前报告仍有资格。 */
    @Transactional(timeout = 5)
    public Snapshot read(UUID project, UUID type) {
        UUID tenant = requireManage(project);
        return decode(required(project, type, false), tenant, project, type);
    }

    /**
     * 当前成员读取类型基线的不可变版本历史，最新在前；空历史与父类型不存在必须区分。
     *
     * <p>与{@link #read}的授权口径刻意不同：管理读取要求 OWNER/ADMIN 并会解码完整受控声明，
     * 而历史集合走 {@code ota:read}（任意项目成员）并且只投影版本、摘要与登记时刻。
     * 之所以能放宽到成员，正是因为响应不含登记入口只从配置接受的能力配置——
     * 放宽授权与收窄字段面必须同时成立，缺一不可。
     *
     * <p>父资源不是本域事实：类型存在性经设备域公开投影端口
     * {@link OtaDeviceTypeIdentityPort#find(UUID, UUID, UUID)}判定（精确三轴、已发布、未删除、
     * RLS 可见）。设备类型不存在、跨项目或不可见统一是
     * {@link OtaTypeBaselineErrorCode#NOT_FOUND}，与同路径族的管理读取端点对「不可见父类型」
     * 的 404/70031 口径一致；OTA 侧不读 {@code dev_type} 表，也不另造设备域错误码。
     *
     * <p>本事务刻意<b>不是</b> {@code readOnly}：该设备域端口以 {@code FOR SHARE} 把「已发布类型」
     * 资格保持到事务结束，而 PostgreSQL 拒绝在只读事务里执行 {@code SELECT ... FOR SHARE}
     * （SQLSTATE 25006）。读取路径不写任何 OTA 事实，写许可由端口与仓储自身约束保证。
     *
     * @param project 精确项目
     * @param type 精确设备类型身份
     * @param cursor 上一页返回的不透明位置，首页为空
     * @param limit 单页上限，1到100
     * @return 按登记时刻与版本倒序的历史游标页
     */
    @Transactional(timeout = 5)
    public CursorPage<OtaTypeBaselineRepository.Version> history(UUID project, UUID type, String cursor, int limit) {
        projects.requireRoleInProject(project);
        requirePublishedType(project, type);
        return repository.versions(project, type, cursor, limit);
    }

    /** 只登记预配置的精确类型，范围锁序列化首次登记并由修订和版本双重围栏保护。 */
    @Transactional(timeout = 5)
    public Snapshot register(UUID project, UUID type, String key, String expectedRevision) {
        UUID tenant = requireManage(project);
        requireKey(key);
        long expected = revision(expectedRevision);
        var configured = configured(tenant, project, type);
        requireType(configured);
        repository.lockRegistration(tenant, project, type);
        var previous = repository.find(project, type, true, false).orElse(null);
        if (previous == null && expected != 0 || previous != null && previous.revision() != expected
                || expected == Long.MAX_VALUE) throw conflict();
        if (previous != null) {
            var prior = decode(previous, tenant, project, type).decoded().value();
            var next = configured.value();
            if (next.baselineVersion() <= prior.baselineVersion()
                    || !next.productKey().equals(prior.productKey())
                    || !next.trustDomain().equals(prior.trustDomain())
                    || !next.rootFingerprint().equals(prior.rootFingerprint())
                    || !next.hardware().model().equals(prior.hardware().model())) throw conflict();
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (previous != null && now.isBefore(previous.updatedAt())) now = previous.updatedAt();
        var next = new OtaTypeBaselineState(tenant, project, type, expected + 1,
                configured.value().baselineVersion(), configured.sha256(), configured.canonical(),
                previous == null ? now : previous.createdAt(), now);
        try {
            if (previous == null) repository.create(next);
            else if (!repository.replace(expected, next)) throw conflict();
        } catch (DataIntegrityViolationException failure) {
            throw conflict();
        }
        audit.record(new AuditLogEntry(tenant, project, TenantContext.require().accountId(),
                "ota_type_baseline", type, "ota.type.baseline.registered",
                Map.of("revision", Long.toString(next.revision()),
                        "baselineVersion", Long.toString(next.baselineVersion()),
                        "baselineHash", next.baselineHash(), "rootFingerprint", configured.value().rootFingerprint())));
        return new Snapshot(next, configured);
    }

    /**
     * 供已完成调用者授权的域内协作持锁读取当前声明，不能单独作为设备升级授权。
     * 精确范围、项目许可、权威类型及本进程配置都重验，不替调用者补造设备身份。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Snapshot lockCurrent(UUID tenant, UUID project, UUID type) {
        var scope = TenantContext.require();
        if (!scope.tenantId().equals(tenant) || !project.equals(scope.projectId())) throw unavailable();
        lifecycle.requireActiveForWrite(tenant, project);
        return currentFacts(tenant, project, type);
    }

    /** 后台已验真租约事务使用，不能用它设置RLS或伪造管理账号。 */
    Snapshot lockRuntime(OtaRuntimeScope scope, UUID type) {
        scope.assertActive();
        lifecycle.requireActiveForWrite(scope.tenant(), scope.project());
        return currentFacts(scope.tenant(), scope.project(), type);
    }

    /** 共用完整配置/持久快照核验，不包含调用者授权。 */
    private Snapshot currentFacts(UUID tenant, UUID project, UUID type) {
        var configured = configured(tenant, project, type);
        requireType(configured);
        var current = decode(required(project, type, true), tenant, project, type);
        if (current.state().baselineVersion() != configured.value().baselineVersion()
                || !current.state().baselineHash().equals(configured.sha256())
                || !Arrays.equals(current.state().canonical(), configured.canonical())) throw unavailable();
        return current;
    }

    /** 已锁定项目后再次验证当前管理角色，不能相信JWT内缓存角色。 */
    private UUID requireManage(UUID project) {
        manage(project);
        UUID tenant = projects.requireProjectTenant(project);
        lifecycle.requireActiveForWrite(tenant, project);
        manage(project);
        return tenant;
    }

    /** 普通成员不能登记或读取安全基线管理记录。 */
    private void manage(UUID project) {
        var role = projects.requireRoleInProject(project);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN)
            throw new BusinessException(OtaTypeBaselineErrorCode.FORBIDDEN);
    }

    /** 缺来源时不能回退数据库旧版本或隐含默认能力。 */
    private OtaTypeBaselineCodec.Decoded configured(UUID tenant, UUID project, UUID type) {
        try { return source.require(tenant, project, type); }
        catch (IllegalArgumentException failure) { throw unavailable(); }
    }

    /** 仅设备域公开投影可证明已发布类型的精确产品身份。 */
    private void requireType(OtaTypeBaselineCodec.Decoded configured) {
        var baseline = configured.value();
        var actual = types.find(baseline.tenantId(), baseline.projectId(), baseline.deviceTypeId())
                .orElseThrow(OtaTypeBaselineService::invalid);
        if (!actual.tenantId().equals(baseline.tenantId()) || !actual.projectId().equals(baseline.projectId())
                || !actual.deviceTypeId().equals(baseline.deviceTypeId())
                || !actual.productKey().equals(baseline.productKey())) throw invalid();
    }

    /** 历史读取只要求父类型在本项目可见：缺失、跨项目、非已发布或已删除一律按不可见 404。 */
    private void requirePublishedType(UUID project, UUID type) {
        UUID tenant = projects.requireProjectTenant(project);
        if (types.find(tenant, project, type).isEmpty())
            throw new BusinessException(OtaTypeBaselineErrorCode.NOT_FOUND);
    }

    /** 普通管理读取与持锁资格共享同一精确范围仓储。 */
    private OtaTypeBaselineState required(UUID project, UUID type, boolean shared) {
        return repository.find(project, type, false, shared)
                .orElseThrow(() -> new BusinessException(OtaTypeBaselineErrorCode.NOT_FOUND));
    }

    /** 规范字节、完整摘要和所有身份一致才能用于投影，不从摘要字段推断正文可信。 */
    private Snapshot decode(OtaTypeBaselineState state, UUID tenant, UUID project, UUID type) {
        try {
            var decoded = codec.decode(state.canonical());
            var value = decoded.value();
            if (!tenant.equals(state.tenantId()) || !project.equals(state.projectId()) || !type.equals(state.deviceTypeId())
                    || !tenant.equals(value.tenantId()) || !project.equals(value.projectId()) || !type.equals(value.deviceTypeId())
                    || state.baselineVersion() != value.baselineVersion() || !state.baselineHash().equals(decoded.sha256())
                    || !Arrays.equals(state.canonical(), decoded.canonical())) throw invalid();
            return new Snapshot(state, decoded);
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }

    /** 修订只允许规范非负long，不接收JSON数值或前导零。 */
    private static long revision(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw parameter();
        try { return Long.parseLong(value); } catch (NumberFormatException failure) { throw parameter(); }
    }

    /** 非HTTP调用也必须提供合法幂等键，不把控制字符写入标识。 */
    private static void requireKey(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || value.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff)) throw parameter();
    }

    /** 安全参数分类。 */
    private static BusinessException parameter() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
    /** 当前受控来源不可用。 */
    private static BusinessException unavailable() { return new BusinessException(OtaTypeBaselineErrorCode.UNAVAILABLE); }
    /** 声明或权威类型不一致。 */
    private static BusinessException invalid() { return new BusinessException(OtaTypeBaselineErrorCode.INVALID); }
    /** 版本与不可变身份冲突。 */
    private static BusinessException conflict() { return new BusinessException(OtaTypeBaselineErrorCode.CONFLICT); }

    /**
     * 元数据与解析后的精确声明；是否当前资格由调用入口决定，不代表设备有资格。
     * @param state 已登记持久状态
     * @param decoded 精确规范基线
     */
    public record Snapshot(OtaTypeBaselineState state, OtaTypeBaselineCodec.Decoded decoded) { }
}
