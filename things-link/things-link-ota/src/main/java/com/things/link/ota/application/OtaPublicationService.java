package com.things.link.ota.application;

import com.things.link.ota.domain.OtaFirmware;
import com.things.link.ota.domain.OtaFirmwareErrorCode;
import com.things.link.ota.domain.OtaFirmwareRepository;
import com.things.link.ota.domain.OtaPublication;
import com.things.link.ota.domain.OtaPublicationErrorCode;
import com.things.link.ota.domain.OtaPublicationRepository;
import com.things.link.ota.domain.OtaRelease;
import com.things.link.ota.domain.OtaUploadRepository;
import com.things.link.ota.domain.OtaUploadSession;
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
import com.things.link.support.tenant.DataPlaneDatabase;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 发布短事务状态边界；外部签名及对象读在处理器执行，不能占用本类事务。 */
@Service
public class OtaPublicationService {
    /** 发布事实与受限领取。 */ private final OtaPublicationRepository repository;
    /** 固件身份及锁。 */ private final OtaFirmwareRepository firmwares;
    /** 固定上传与取消意图。 */ private final OtaUploadRepository uploads;
    /** 当前角色与真实项目租户。 */ private final ProjectService projects;
    /** ACTIVE代次及持续许可。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 可信根与最终域锁。 */ private final OtaTrustService trust;
    /** 完整字段及签名资格。 */ private final OtaPublicationContract contract;
    /** 不能由缺配置回退普通无预算signer。 */ private final ObjectProvider<OtaControlledReleaseSigner> signers;
    /** 同事务审计。 */ private final AuditLogService audit;
    /** 受限领取后显式范围。 */ private final TransactionLocalRlsScope rls;
    /** 显式注入业务与受控适配器，构造不执行网络。 */
    public OtaPublicationService(OtaPublicationRepository repository, OtaFirmwareRepository firmwares,
            OtaUploadRepository uploads, ProjectService projects, ProjectLifecycleAccessService lifecycle,
            OtaTrustService trust, OtaPublicationContract contract, ObjectProvider<OtaControlledReleaseSigner> signers,
            AuditLogService audit, TransactionLocalRlsScope rls) {
        this.repository = repository; this.firmwares = firmwares; this.uploads = uploads;
        this.projects = projects; this.lifecycle = lifecycle; this.trust = trust; this.contract = contract;
        this.signers = signers; this.audit = audit; this.rls = rls;
    }

    /** 创建持久尝试时固件仍为DRAFT；缺适配器不创建假成功事实。 */
    @Transactional
    public OtaPublication create(UUID project, UUID firmware, String key, String expectedRevision,
                                 UUID upload, byte[] manifest) {
        UUID tenant = requireWrite(project);
        requireKey(key);
        long expected = revision(expectedRevision);
        requireSigner();
        OtaFirmware draft = draft(project, firmware);
        if (draft.revision() != expected) throw conflict();
        OtaUploadSession session = uploads.find(project, firmware, upload, true).orElseThrow(OtaPublicationService::conflict);
        if (!"VERIFIED".equals(session.status()) || session.cancelRequestedAt() != null
                || session.versionId() == null || !tenant.equals(session.tenantId())
                || !lifecycle.lockActiveForWrite(tenant, project, session.projectGeneration())) throw conflict();
        String domain = domain(manifest);
        var grant = trust.activeKey(project, draft.deviceTypeId(), domain);
        byte[] canonical = validate(manifest, draft, session, grant);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID id = Uuid7.generate();
        OtaPublication next = new OtaPublication(id, tenant, project, firmware, upload,
                TenantContext.require().accountId(), id, session.projectGeneration(), draft.revision(),
                session.revision(), canonical, contract.snapshot(grant), "PREPARED", 0,
                null, null, null, null, null, null, now, now);
        try { repository.create(next); }
        catch (DataIntegrityViolationException failure) { throw conflict(); }
        record(next, "ota.publication.created");
        return next;
    }

    /** 读取尝试白名单事实不等于重新授予发布资格。 */
    @Transactional(readOnly = true)
    public OtaPublication find(UUID project, UUID firmware, UUID id) {
        projects.requireRoleInProject(project);
        return required(project, firmware, id, false);
    }

    /** 当前成员读取固件内全部发布尝试历史，最新在前；空历史与父固件不存在必须区分。 */
    @Transactional(readOnly = true)
    public CursorPage<OtaPublication> history(UUID project, UUID firmware, String cursor, int limit) {
        projects.requireRoleInProject(project);
        firmwares.find(project, firmware, false)
                .orElseThrow(() -> new BusinessException(OtaFirmwareErrorCode.NOT_FOUND));
        return repository.page(project, firmware, cursor, limit);
    }

    /** 受限数据库领取后，在同一事务恢复可信范围并记录审计。 */
    @DataPlaneDatabase
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public Optional<OtaPublication> claim() {
        Optional<OtaPublication> result = repository.claimPreparedOrSigned();
        result.ifPresent(value -> {
            rls.establish(value.tenantId(), value.projectId());
            record(value, "ota.publication.claimed");
        });
        return result;
    }

    /** 外部动作前重新验证实际租约与全套父身份，禁止领取后暂停再执行过期任务。 */
    @DataPlaneDatabase
    @Transactional(timeout = 5)
    public Context prepare(OtaPublication claimed) {
        Context value = current(claimed, false);
        if (!repository.renew(value.publication(), claimed.leaseToken())) throw conflict();
        return value;
    }

    /** 心跳独立事务，不继承外部调用的等待；失权后不可复活旧令牌。 */
    @DataPlaneDatabase
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public boolean renew(OtaPublication value) {
        requireWrite(value.projectId());
        if (!lifecycle.lockActiveForWrite(value.tenantId(), value.projectId(), value.projectGeneration())) return false;
        return repository.renew(value, value.leaseToken());
    }

    /** 签名只记录一次；持久SIGNED允许崩溃后重新校验对象，不能重新请求签名。 */
    @DataPlaneDatabase
    @Transactional(timeout = 5)
    public OtaPublication signed(OtaPublication claimed, OtaSigningCoordinator.Result signed) {
        Context value = current(claimed, false);
        contract.verifyResult(claimed.requestId(), value.publication().canonicalManifest(), value.grant(), signed);
        if (!repository.recordSigned(value.publication(), claimed.leaseToken(), signed.spki(),
                signed.signature(), signed.receipt())) throw conflict();
        OtaPublication result = required(claimed.projectId(), claimed.firmwareId(), claimed.id(), false);
        record(result, "ota.publication.signed");
        return result;
    }

    /** 固定对象已复验后，最终同事务持信任域锁、采用对象并提交READY。 */
    @DataPlaneDatabase
    @Transactional(timeout = 5)
    public OtaPublication commit(OtaPublication claimed) {
        Context value = current(claimed, true);
        OtaPublication signed = value.publication();
        if (!"SIGNED".equals(signed.status())) throw conflict();
        try { contract.verifySignature(signed.canonicalManifest(), value.grant(), signed.spki(),
                signed.signature(), signed.receipt()); }
        catch (IllegalArgumentException failure) { throw invalid(); }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OtaRelease release = new OtaRelease(signed.id(), signed.tenantId(), signed.projectId(), signed.firmwareId(),
                signed.uploadSessionId(), signed.id(), signed.canonicalManifest(), signed.trustSnapshot(),
                signed.spki(), signed.signature(), signed.receipt(), now);
        if (!repository.commitRelease(signed, claimed.leaseToken(), release)) throw conflict();
        OtaPublication result = required(claimed.projectId(), claimed.firmwareId(), claimed.id(), false);
        record(result, "ota.publication.committed");
        return result;
    }

    /** 失败只写安全原因；不能据提交异常触发对象物理删除。 */
    @DataPlaneDatabase
    @Transactional(timeout = 5)
    public void failed(OtaPublication claimed, boolean unknown, String reason) {
        boolean changed = unknown ? repository.unknown(claimed, claimed.leaseToken(), reason)
                : repository.reject(claimed, claimed.leaseToken(), reason);
        if (changed) record(required(claimed.projectId(), claimed.firmwareId(), claimed.id(), false),
                "ota.publication.failed");
    }

    /** 提交结果未知仅按固定身份回读；恢复不能凭异常推断未提交。 */
    @DataPlaneDatabase
    @Transactional(readOnly = true)
    public OtaPublication reread(OtaPublication claimed) {
        return required(claimed.projectId(), claimed.firmwareId(), claimed.id(), false);
    }

    /** 协调器在工作线程签名前后实际重读精确信任范围，不复用捕获快照。 */
    @DataPlaneDatabase
    @Transactional(timeout = 5)
    public OtaSigningTrustSource.Binding binding(OtaPublication claimed, String domain) {
        var binding = current(claimed, false).grant().binding();
        if (!binding.trustDomain().equals(domain)) throw conflict();
        return binding;
    }

    /** 单一显式受控适配器才可用；零个或多个均拒绝，不选择任意供应商。 */
    public OtaControlledReleaseSigner requireSigner() {
        var configured = signers.orderedStream().limit(2).toList();
        if (configured.size() != 1) throw unavailable();
        return configured.getFirst();
    }

    /** 固定父锁顺序；签名前读取与最终持锁资格都必须和最初完整快照一致。 */
    private Context current(OtaPublication claimed, boolean finalLock) {
        UUID tenant = requireWrite(claimed.projectId());
        if (!tenant.equals(claimed.tenantId()) || !TenantContext.require().accountId().equals(claimed.createdBy())
                || !lifecycle.lockActiveForWrite(tenant, claimed.projectId(), claimed.projectGeneration())) throw conflict();
        OtaFirmware draft = draft(claimed.projectId(), claimed.firmwareId());
        OtaUploadSession upload = uploads.find(claimed.projectId(), claimed.firmwareId(), claimed.uploadSessionId(), true)
                .orElseThrow(OtaPublicationService::conflict);
        OtaPublication value = required(claimed.projectId(), claimed.firmwareId(), claimed.id(), true);
        if (!value.tenantId().equals(claimed.tenantId()) || !value.createdBy().equals(claimed.createdBy())
                || !value.requestId().equals(claimed.requestId()) || !value.uploadSessionId().equals(upload.id())
                || value.projectGeneration() != claimed.projectGeneration()
                || value.firmwareRevision() != claimed.firmwareRevision() || value.uploadRevision() != claimed.uploadRevision()
                || !java.util.Arrays.equals(value.canonicalManifest(), claimed.canonicalManifest())
                || !java.util.Arrays.equals(value.trustSnapshot(), claimed.trustSnapshot())
                || claimed.leaseToken() == null || !claimed.leaseToken().equals(value.leaseToken())
                || !java.util.Set.of("SIGNING", "SIGNED").contains(value.status())
                || value.leaseUntil() == null || !value.leaseUntil().isAfter(Instant.now())
                || draft.revision() != value.firmwareRevision() || upload.revision() != value.uploadRevision()
                || !"VERIFIED".equals(upload.status()) || upload.cancelRequestedAt() != null
                || upload.projectGeneration() != value.projectGeneration()) throw conflict();
        var original = contract.restore(value.trustSnapshot());
        var actual = finalLock
                ? trust.lockForPublication(value.projectId(), draft.deviceTypeId(), original.binding().trustDomain())
                : trust.activeKey(value.projectId(), draft.deviceTypeId(), original.binding().trustDomain());
        if (!contract.same(original, actual)) throw conflict();
        validate(value.canonicalManifest(), draft, upload, actual);
        return new Context(draft, upload, value, actual);
    }

    /** 签名包自声明不能绕过严格manifest语法。 */
    private String domain(byte[] manifest) {
        try { return (String) new OtaCanonicalJson().parseObject(new OtaManifestCodec().canonicalize(manifest)).get("trustDomain"); }
        catch (IllegalArgumentException failure) { throw invalid(); }
    }
    /** 固件必须仍是精确DRAFT，锁持续到当前短事务结束。 */
    private OtaFirmware draft(UUID project, UUID firmware) {
        OtaFirmware value = firmwares.find(project, firmware, true).orElseThrow(OtaPublicationService::conflict);
        if (!"DRAFT".equals(value.status())) throw conflict();
        return value;
    }
    /** 权威内容不匹配以稳定业务错误返回。 */
    private byte[] validate(byte[] manifest, OtaFirmware draft, OtaUploadSession upload, OtaTrustService.Grant grant) {
        try { return contract.validate(manifest, draft, upload, grant); }
        catch (IllegalArgumentException failure) { throw invalid(); }
    }
    /** 角色→真实tenant→ACTIVE持续锁→角色复核。 */
    private UUID requireWrite(UUID project) {
        requireManage(project);
        UUID tenant = projects.requireProjectTenant(project);
        lifecycle.requireActiveForWrite(tenant, project);
        requireManage(project);
        return tenant;
    }
    /** 当前成员的管理权限不能由创建时角色代替。 */
    private void requireManage(UUID project) {
        ProjectRole role = projects.requireRoleInProject(project);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) throw new BusinessException(OtaPublicationErrorCode.FORBIDDEN);
    }
    /** 固定项目和父固件查找，不能跨范围恢复。 */
    private OtaPublication required(UUID project, UUID firmware, UUID id, boolean lock) {
        return repository.find(project, firmware, id, lock).orElseThrow(() -> new BusinessException(OtaPublicationErrorCode.NOT_FOUND));
    }
    /** 审计只保留安全状态与标识，不含签名正文、对象路径及供应商秘密。 */
    private void record(OtaPublication value, String action) {
        audit.record(new AuditLogEntry(value.tenantId(), value.projectId(), value.createdBy(), "project", value.projectId(),
                action, Map.of("publicationId", value.id().toString(), "firmwareId", value.firmwareId().toString(),
                "status", value.status())));
    }
    /** 公共幂等键不可空或包含控制字符。 */
    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128 || key.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff)) throw parameter();
    }
    /** 不接受隐式数字转换与溢出修订。 */
    private static long revision(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw parameter();
        try { return Long.parseLong(value); } catch (NumberFormatException failure) { throw parameter(); }
    }
    /** 固定参数错误。 */ private static BusinessException parameter() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
    /** 固定内容错误。 */ private static BusinessException invalid() { return new BusinessException(OtaPublicationErrorCode.INVALID); }
    /** 固定冲突错误。 */ private static BusinessException conflict() { return new BusinessException(OtaPublicationErrorCode.CONFLICT); }
    /** 固定服务不可用。 */ private static BusinessException unavailable() { return new BusinessException(OtaPublicationErrorCode.UNAVAILABLE); }
    /** 当前短事务核对结果；不独立证明后续事务仍持锁。
     * @param firmware 当前草稿
     * @param upload 固定已核验上传
     * @param publication 实际租约与状态
     * @param grant 当前完整资格
     */
    public record Context(OtaFirmware firmware, OtaUploadSession upload, OtaPublication publication, OtaTrustService.Grant grant) { }
}
