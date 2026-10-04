package com.things.link.ota.application;

import com.things.link.ota.domain.OtaFirmwareErrorCode;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaFirmwareLifecycleErrorCode;
import com.things.link.ota.domain.OtaFirmwareLifecycleRepository;
import com.things.link.ota.domain.OtaFirmwareLifecycleState;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 固件软终态及原因记录的原子边界，不取消上传或删除不可变发布对象。 */
@Service
public class OtaFirmwareLifecycleService {
    /** 状态和转移元数据共用单次查询及CAS。 */
    private final OtaFirmwareLifecycleRepository repository;
    /** 当前角色与权威项目租户。 */
    private final ProjectService projects;
    /** 项目ACTIVE持续许可。 */
    private final ProjectLifecycleAccessService lifecycle;
    /** 原子安全审计，不携带对象地址。 */
    private final AuditLogService audit;
    /** 与活动准入串行的项目OTA安全控制。 */
    private final OtaCampaignRuntimeRepository runtime;
    /** 构造仅注入领域端口。 */
    public OtaFirmwareLifecycleService(OtaFirmwareLifecycleRepository repository, ProjectService projects,
                                      ProjectLifecycleAccessService lifecycle, AuditLogService audit,
                                      OtaCampaignRuntimeRepository runtime) {
        this.repository = repository; this.projects = projects; this.lifecycle = lifecycle; this.audit = audit; this.runtime = runtime;
    }
    /** 管理读取也需ACTIVE及项目锁后角色复核，返回同一时刻完整元数据。 */
    @Transactional(timeout = 5)
    public OtaFirmwareLifecycleState read(UUID project, UUID firmware) {
        UUID tenant = requireWrite(project);
        return required(tenant, project, firmware, false);
    }
    /** READY单向退役，原因与状态和审计同事务。 */
    @Transactional(timeout = 5)
    public OtaFirmwareLifecycleState deprecate(UUID project, UUID firmware, String key,
                                               String expectedRevision, String reason) {
        return change(project, firmware, key, expectedRevision, reason, false);
    }
    /** READY或DEPRECATED单向撤销，不重开终态或改写先前退役记录。 */
    @Transactional(timeout = 5)
    public OtaFirmwareLifecycleState revoke(UUID project, UUID firmware, String key,
                                            String expectedRevision, String reason) {
        return change(project, firmware, key, expectedRevision, reason, true);
    }
    /** 固定项目→固件锁顺序，并在已锁定快照上检查当前修订。 */
    private OtaFirmwareLifecycleState change(UUID project, UUID firmware, String key,
                                             String revision, String reason, boolean revoke) {
        UUID tenant = requireWrite(project);
        runtime.controlLock(tenant, project);
        requireKey(key);
        long expected = revision(revision);
        requireReason(reason);
        OtaFirmwareLifecycleState previous = required(tenant, project, firmware, true);
        String status = previous.firmware().status();
        if (previous.firmware().revision() != expected || expected == Long.MAX_VALUE
                || !("READY".equals(status) || revoke && "DEPRECATED".equals(status))) throw conflict();
        UUID actor = TenantContext.require().accountId();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (now.isBefore(previous.firmware().createdAt())) now = previous.firmware().createdAt();
        if (previous.deprecation() != null && now.isBefore(previous.deprecation().occurredAt()))
            now = previous.deprecation().occurredAt();
        boolean changed = revoke
                ? repository.revoke(tenant, project, firmware, expected, reason, actor, now)
                : repository.deprecate(tenant, project, firmware, expected, reason, actor, now);
        if (!changed) throw conflict();
        runtime.securityPause(project, firmware, actor, revoke ? "FIRMWARE_REVOKED" : "FIRMWARE_DEPRECATED");
        OtaFirmwareLifecycleState result = required(tenant, project, firmware, false);
        audit.record(new AuditLogEntry(tenant, project, actor, "ota_firmware", firmware,
                revoke ? "ota.firmware.revoked" : "ota.firmware.deprecated",
                Map.of("status", result.firmware().status(), "revision", Long.toString(result.firmware().revision()),
                        "reason", reason)));
        return result;
    }
    /** 管理角色必须在项目持续锁前后均成立。 */
    private UUID requireWrite(UUID project) {
        manage(project);
        UUID tenant = projects.requireProjectTenant(project);
        lifecycle.requireActiveForWrite(tenant, project);
        manage(project);
        return tenant;
    }
    /** 普通成员不能读取安全转移或改变固件状态。 */
    private void manage(UUID project) {
        ProjectRole role = projects.requireRoleInProject(project);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN)
            throw new BusinessException(OtaFirmwareLifecycleErrorCode.FORBIDDEN);
    }
    /** 精确项目及真实租户查询，隐藏其他范围存在性。 */
    private OtaFirmwareLifecycleState required(UUID tenant, UUID project, UUID firmware, boolean lock) {
        OtaFirmwareLifecycleState value = repository.find(project, firmware, lock)
                .orElseThrow(() -> new BusinessException(OtaFirmwareErrorCode.NOT_FOUND));
        if (!tenant.equals(value.firmware().tenantId())) throw new BusinessException(OtaFirmwareErrorCode.NOT_FOUND);
        return value;
    }
    /** 正文修订只允许规范非负long文本。 */
    static long revision(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(value); } catch (NumberFormatException failure) { throw invalid(); }
    }
    /** 原因原样保存，禁止首尾Unicode空白、控制码元及孤立代理字符。 */
    static void requireReason(String value) {
        if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > 512
                || whitespace(value.codePointAt(0)) || whitespace(value.codePointBefore(value.length()))
                || value.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff)) throw invalid();
    }
    /** 空白包括Java空白与Unicode空格字符，不擅自trim保存值。 */
    private static boolean whitespace(int point) { return Character.isWhitespace(point) || Character.isSpaceChar(point); }
    /** 公共幂等键在非HTTP直接调用时也不能省略。 */
    private static void requireKey(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || value.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff))
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
    }
    /** 固定参数错误。 */
    private static BusinessException invalid() { return new BusinessException(OtaFirmwareLifecycleErrorCode.INVALID); }
    /** 固定状态/CAS错误。 */
    private static BusinessException conflict() { return new BusinessException(OtaFirmwareLifecycleErrorCode.CONFLICT); }
}
