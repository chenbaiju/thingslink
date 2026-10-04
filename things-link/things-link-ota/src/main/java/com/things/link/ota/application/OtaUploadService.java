package com.things.link.ota.application;

import com.things.link.ota.domain.OtaFirmwareErrorCode;
import com.things.link.ota.domain.OtaFirmwareRepository;
import com.things.link.ota.domain.OtaUploadErrorCode;
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
import com.things.link.support.storage.VersionedPrivateObjectStorage;
import com.things.link.support.tenant.DataPlaneDatabase;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 上传会话短事务边界；所有网络和本地正文处理在这些事务之外执行。 */
@Service
public class OtaUploadService {
    /** 持久会话及租约CAS。 */ private final OtaUploadRepository repository;
    /** 精确固件事实；不借控制器绕过父资源状态。 */ private final OtaFirmwareRepository firmwares;
    /** 成员角色与真实项目归属。 */ private final ProjectService projects;
    /** ACTIVE持续许可及代次。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 同事务领域审计。 */ private final AuditLogService audit;
    /** 后台可信claim补全事务RLS。 */ private final TransactionLocalRlsScope rls;
    /** 没有配置时不装配假存储。 */ private final ObjectProvider<VersionedPrivateObjectStorage> storage;
    /** 受控OTA私桶配置；不会自动新建或借用export桶。 */ private final String bucket;

    /** 显式绑定生产依赖，存储配置缺失只阻止上传。 */
    public OtaUploadService(OtaUploadRepository repository, OtaFirmwareRepository firmwares,
            ProjectService projects, ProjectLifecycleAccessService lifecycle, AuditLogService audit,
            TransactionLocalRlsScope rls, ObjectProvider<VersionedPrivateObjectStorage> storage,
            @Value("${things-link.ota.storage.bucket:}") String bucket) {
        this.repository = repository;
        this.firmwares = firmwares;
        this.projects = projects;
        this.lifecycle = lifecycle;
        this.audit = audit;
        this.rls = rls;
        this.storage = storage;
        this.bucket = bucket;
    }

    /** 创建映射在父锁下恢复，封闭会话不重建，且每固件最多一个未收束会话。 */
    @Transactional
    public OtaUploadSession create(UUID project, UUID firmware, String key, long length, String sha256) {
        UUID tenant = requireWrite(project);
        requireDraft(project, firmware);
        requireKey(key);
        if (length < 1 || length > 67_108_864 || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw invalid();
        }
        requireStorage();
        UUID account = TenantContext.require().accountId();
        String keyHash = digest("tc.ota-upload-key/v1", key);
        String requestHash = digest("tc.ota-upload-request/v1", Long.toString(length), sha256);
        repository.lockCreation(tenant, project, firmware);
        var existing = repository.findCreation(tenant, project, firmware, account, keyHash).orElse(null);
        if (existing != null) {
            if (!existing.requestDigest().equals(requestHash)) {
                throw new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT);
            }
            if ("CLEANED".equals(existing.status())) {
                throw new BusinessException(CommonErrorCode.IDEMPOTENCY_RESULT_NOT_REPLAYABLE);
            }
            return existing;
        }
        if (repository.findActive(tenant, project, firmware).isPresent()) { throw conflict(); }
        UUID id = Uuid7.generate();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        long generation = lifecycle.snapshot(tenant, project).lifecycleGeneration();
        OtaUploadSession session = new OtaUploadSession(id, tenant, project, firmware, account, UUID.randomUUID(),
                generation, length, 0, sha256, bucket,
                "ota/" + tenant + "/" + project + "/" + firmware + "/" + id + "/artifact.bin",
                "WAITING", null, null, keyHash, requestHash, now, now.plusSeconds(3600),
                null, null, null, null, null, now, null);
        repository.create(session);
        record(session, "ota.upload.created");
        return session;
    }

    /** 当前成员读取明确会话；返回投影由API另行脱敏。 */
    @Transactional(readOnly = true)
    public OtaUploadSession find(UUID project, UUID firmware, UUID id) {
        projects.requireRoleInProject(project);
        return required(project, firmware, id, false);
    }

    /** 当前成员读取固件内全部上传会话历史，最新在前；空历史与父固件不存在必须区分。 */
    @Transactional(readOnly = true)
    public CursorPage<OtaUploadSession> history(UUID project, UUID firmware, String cursor, int limit) {
        projects.requireRoleInProject(project);
        requireFirmwareExists(project, firmware);
        return repository.page(project, firmware, cursor, limit);
    }

    /** 只有创建人仍持有管理角色时可消费一次；网络前提交接收租约。 */
    @Transactional
    public OtaUploadSession prepare(UUID project, UUID firmware, UUID id) {
        UUID tenant = requireWrite(project);
        requireDraft(project, firmware);
        requireStorage();
        OtaUploadSession session = required(project, firmware, id, true);
        if (!tenant.equals(session.tenantId()) || !TenantContext.require().accountId().equals(session.createdBy())
                || !lifecycle.lockActiveForWrite(tenant, project, session.projectGeneration())) { throw conflict(); }
        UUID token = UUID.randomUUID();
        if (!repository.claimReceive(session, token)) { throw conflict(); }
        OtaUploadSession result = required(project, firmware, id, false);
        record(result, "ota.upload.receiving");
        return result;
    }

    /** 写入前重新核对身份/代次并持久登记可能写入，再释放事务才允许S3调用。 */
    @Transactional
    public void markWriting(OtaUploadSession session) {
        requireCurrentWrite(session);
        if (!repository.markWriting(session, session.leaseToken())) { throw conflict(); }
        record(required(session.projectId(), session.firmwareId(), session.id(), false), "ota.upload.writing");
    }

    /** 已收到固定版本时即使项目正被删除也应保留写入终结证据，采用另行检查。 */
    @Transactional
    public boolean recordVersion(OtaUploadSession session, String version) {
        boolean changed = repository.recordVersion(session, session.leaseToken(), version);
        if (changed) record(required(session.projectId(), session.firmwareId(), session.id(), false), "ota.upload.receipt");
        return changed;
    }

    /** 只有新鲜授权、DRAFT、代次、未取消和当前租约都成立才采用复验对象。 */
    @Transactional
    public OtaUploadSession adopt(OtaUploadSession session) {
        requireCurrentWrite(session);
        if (!repository.finishVerified(session, session.leaseToken())) { throw conflict(); }
        OtaUploadSession result = required(session.projectId(), session.firmwareId(), session.id(), false);
        record(result, "ota.upload.verified");
        return result;
    }

    /** 网络或接收失败由持久字段决定是否UNKNOWN，不能按客户端异常猜测未写。 */
    @Transactional
    public void fail(OtaUploadSession session, String reason, boolean mayHaveWritten) {
        if (repository.fail(session, session.leaseToken(), reason, mayHaveWritten)) {
            record(required(session.projectId(), session.firmwareId(), session.id(), false), "ota.upload.failed");
        }
    }

    /** 取消只登记意图，已写对象由有界恢复worker收束。 */
    @Transactional
    public OtaUploadSession cancel(UUID project, UUID firmware, UUID id, String key, String revision) {
        requireWrite(project);
        requireKey(key);
        long expected = revision(revision);
        OtaUploadSession session = required(project, firmware, id, true);
        if (!repository.requestCancel(session, expected)) { throw conflict(); }
        OtaUploadSession result = required(project, firmware, id, false);
        record(result, "ota.upload.cancelled");
        return result;
    }

    /** 心跳只返回租约结果；网络线程用false立即取消，不持业务长事务。 */
    public boolean renew(OtaUploadSession session) {
        return repository.renew(session, session.leaseToken());
    }

    /** 受限全局领取返回可信身份，在同一事务补全RLS并写入领取审计。 */
    @DataPlaneDatabase
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<OtaUploadSession> claimRecovery() {
        Optional<OtaUploadSession> result = repository.claimRecovery();
        result.ifPresent(session -> {
            rls.establish(session.tenantId(), session.projectId());
            record(session, "ota.upload.recovery.claimed");
        });
        return result;
    }

    /** 后台恢复失败保留未决状态及退避，不能据空页删除身份。 */
    @Transactional
    public void postpone(OtaUploadSession session, String reason) {
        repository.postpone(session, session.leaseToken(), reason);
    }

    /** 取消或无法再采用的完整对象进入回收，不丢失writeSettledAt。 */
    @Transactional
    public void markCleanup(OtaUploadSession session, String reason) {
        if (repository.markCleanup(session, session.leaseToken(), reason)) {
            record(required(session.projectId(), session.firmwareId(), session.id(), false), "ota.upload.cleanup.requested");
        }
    }

    /** 外部已完成重新盘点后收束，数据库仍要求确定未写或写入已终结。 */
    @Transactional
    public boolean finishCleanup(OtaUploadSession session) {
        boolean completed = repository.finishCleanup(session, session.leaseToken());
        if (completed) record(required(session.projectId(), session.firmwareId(), session.id(), false), "ota.upload.cleaned");
        return completed;
    }

    /** 异常提交后仅权威回读当前身份，不据异常触发物理删除。 */
    @Transactional(readOnly = true)
    public OtaUploadSession reread(OtaUploadSession session) {
        return required(session.projectId(), session.firmwareId(), session.id(), false);
    }

    /** 仅供服务器编排调用，不向客户端输出桶或适配器地址。 */
    public VersionedPrivateObjectStorage requireStorage() {
        VersionedPrivateObjectStorage value = storage.getIfAvailable();
        if (value == null || bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
            throw new BusinessException(OtaUploadErrorCode.UNAVAILABLE);
        }
        return value;
    }

    /** 父锁顺序固定为项目持续许可、固件、会话，避免取消与采用互锁。 */
    private void requireCurrentWrite(OtaUploadSession session) {
        UUID tenant = requireWrite(session.projectId());
        requireDraft(session.projectId(), session.firmwareId());
        if (!tenant.equals(session.tenantId()) || !TenantContext.require().accountId().equals(session.createdBy())
                || !lifecycle.lockActiveForWrite(tenant, session.projectId(), session.projectGeneration())) { throw conflict(); }
    }

    /** 角色、真实租户、ACTIVE持续锁与角色复核不可省略。 */
    private UUID requireWrite(UUID project) {
        requireManage(project);
        UUID tenant = projects.requireProjectTenant(project);
        lifecycle.requireActiveForWrite(tenant, project);
        requireManage(project);
        return tenant;
    }

    /** 非管理成员不得操纵上传。 */
    private void requireManage(UUID project) {
        ProjectRole role = projects.requireRoleInProject(project);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) {
            throw new BusinessException(OtaFirmwareErrorCode.FORBIDDEN);
        }
    }

    /** 父资源不存在按固件错误码隐藏，与真实存在的空历史页区分。 */
    private void requireFirmwareExists(UUID project, UUID firmware) {
        firmwares.find(project, firmware, false)
                .orElseThrow(() -> new BusinessException(OtaFirmwareErrorCode.NOT_FOUND));
    }

    /** 签名发布或取消后的固件不能继续接收/采用。 */
    private void requireDraft(UUID project, UUID firmware) {
        var value = firmwares.find(project, firmware, true)
                .orElseThrow(() -> new BusinessException(OtaFirmwareErrorCode.NOT_FOUND));
        if (!"DRAFT".equals(value.status())) { throw conflict(); }
    }

    /** 精确scope查询隐藏跨项目存在性。 */
    private OtaUploadSession required(UUID project, UUID firmware, UUID id, boolean lock) {
        return repository.find(project, firmware, id, lock)
                .orElseThrow(() -> new BusinessException(OtaUploadErrorCode.NOT_FOUND));
    }

    /** 审计只含状态及修订，不带对象键、正文或供应商内容。 */
    private void record(OtaUploadSession session, String action) {
        audit.record(new AuditLogEntry(session.tenantId(), session.projectId(),
                TenantContext.current().map(scope -> scope.accountId()).orElse(null),
                "ota_upload", session.id(), action,
                Map.of("status", session.status(), "revision", Long.toString(session.revision()))));
    }

    /** 域分离并逐字段长度分帧，拒绝键中的不可编码代理码元。 */
    private static String digest(String domain, String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((domain + "\0").getBytes(StandardCharsets.US_ASCII));
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("缺少SHA256", exception); }
    }

    /** 领域入口也明确校验幂等键，不依赖公共层替代。 */
    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128
                || key.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff)) throw invalid();
    }

    /** 修订只允许无前导零的非负十进制long字符串。 */
    private static long revision(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(value); } catch (NumberFormatException exception) { throw invalid(); }
    }
    /** 固定参数错误。 */ private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
    /** 固定上传状态错误。 */ private static BusinessException conflict() { return new BusinessException(OtaUploadErrorCode.CONFLICT); }
}
