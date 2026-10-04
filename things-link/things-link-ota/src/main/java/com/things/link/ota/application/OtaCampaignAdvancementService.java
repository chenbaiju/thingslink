package com.things.link.ota.application;

import com.things.link.ota.domain.OtaCampaignAdvancementRepository;
import com.things.link.ota.domain.OtaCampaignErrorCode;
import com.things.link.ota.domain.OtaCampaignRepository;
import com.things.link.ota.domain.OtaCampaignRuntime;
import com.things.link.ota.domain.OtaCampaignRuntimeErrorCode;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.tenant.DataPlaneDatabase;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 成功批次依冻结策略推进，最后批由系统封闭活动统计。 */
@Service
@DataPlaneDatabase
public class OtaCampaignAdvancementService {
    /** 固定批次与完成事实。 */ private final OtaCampaignAdvancementRepository repository;
    /** 当前锁定运行图。 */ private final OtaCampaignRuntimeRepository runtime;
    /** 原冻结目标，不能由新查询替换。 */ private final OtaCampaignRepository campaigns;
    /** 真实项目成员身份。 */ private final ProjectService projects;
    /** 当前写许可。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 后台权威范围。 */ private final TransactionLocalRlsScope rls;
    /** 当前发布资格。 */ private final OtaRuntimeQualification qualification;
    /** 设备不可逆冲突隔离。 */ private final OtaRollbackDirectionGuard directions;
    /** 同事务审计。 */ private final AuditLogService audit;

    /** 注入独立管理授权与后台范围，不构造虚假账号。 */
    public OtaCampaignAdvancementService(OtaCampaignAdvancementRepository repository,
            OtaCampaignRuntimeRepository runtime, OtaCampaignRepository campaigns, ProjectService projects,
            ProjectLifecycleAccessService lifecycle, TransactionLocalRlsScope rls,
            OtaRuntimeQualification qualification, OtaRollbackDirectionGuard directions, AuditLogService audit) {
        this.repository = repository; this.runtime = runtime; this.campaigns = campaigns;
        this.projects = projects; this.lifecycle = lifecycle; this.rls = rls;
        this.qualification = qualification; this.directions = directions; this.audit = audit;
    }

    /** 人工只开启成功中间批的唯一后继，公共幂等不替代当前角色。 */
    @Transactional(timeout = 5)
    public OtaCampaignRuntime advance(UUID project, UUID campaign, String key, String expectedRevision,
            String expectedBatchNumber, String reason) {
        manage(project);
        UUID tenant = projects.requireProjectTenant(project);
        lifecycle.requireActiveForWrite(tenant, project);
        manage(project);
        runtime.controlLock(tenant, project);
        long revision = number(expectedRevision, Long.MAX_VALUE - 1);
        int batch = (int) number(expectedBatchNumber, 1000);
        if (batch < 1 || key == null || key.isBlank() || key.length() > 128 || invalidText(key)
                || reason == null || reason.isBlank() || reason.codePointCount(0, reason.length()) > 256
                || invalidText(reason)) throw invalid();
        var current = required(project, campaign);
        if (!ready(current) || current.campaign().stateVersion() != revision
                || !Integer.valueOf(batch).equals(current.currentBatch())
                || batch >= current.campaign().batchCount() || !manual(current)) throw conflict();
        try { validate(tenant, project, current); }
        catch (BusinessException | IllegalArgumentException failure) { throw conflict(); }
        UUID actor = TenantContext.require().accountId();
        if (actor == null || !repository.advance(project, campaign, revision, batch, actor, reason)) throw conflict();
        record(tenant, project, campaign, actor, "ota.campaign.batch.advanced", "MANUAL");
        return required(project, campaign);
    }

    /** 一次短事务只处理一个可信定位候选，异常不掩盖数据库首因。 */
    @Transactional(timeout = 5)
    public boolean advanceOne() {
        if (TenantContext.current().isPresent()) throw new IllegalStateException("OTA扩批后台不能继承管理账号");
        var found = repository.candidate();
        if (found.isEmpty()) return false;
        var candidate = found.orElseThrow();
        UUID tenant = candidate.tenantId();
        UUID project = candidate.projectId();
        UUID campaign = candidate.campaignId();
        rls.establish(tenant, project);
        lifecycle.requireActiveForWrite(tenant, project);
        runtime.controlLock(tenant, project);
        var current = runtime.lockRuntime(project, campaign).orElse(null);
        if (current == null || !ready(current) || current.campaign().stateVersion() != candidate.stateVersion()) return false;
        boolean last = current.currentBatch() == current.campaign().batchCount();
        if (!last && manual(current)) return false;
        try {
            validateDevices(project, current);
            if (!last) validateRelease(tenant, project, current);
        } catch (BusinessException | IllegalArgumentException failure) {
            int paused = runtime.securityPause(project, current.campaign().firmwareId(), null,
                    "BATCH_ADVANCEMENT_SECURITY_INVALID");
            if (paused > 0) record(tenant, project, campaign, null, "ota.campaign.batch.security-paused", "SECURITY");
            return paused > 0;
        }
        boolean changed = last ? repository.complete(project, campaign, candidate.stateVersion())
                : repository.advance(project, campaign, candidate.stateVersion(), current.currentBatch(), null, null);
        if (changed) record(tenant, project, campaign, null,
                last ? "ota.campaign.completed" : "ota.campaign.batch.advanced", "SYSTEM");
        return changed;
    }

    /** 先检查设备隔离，再读取当前发布；不修改原目标或取得设备反向锁。 */
    private void validate(UUID tenant, UUID project, OtaCampaignRuntime current) {
        validateDevices(project, current);
        validateRelease(tenant, project, current);
    }
    /** 原责任完成也不清除设备隔离。 */
    private void validateDevices(UUID project, OtaCampaignRuntime current) {
        if (campaigns.targets(project, current.campaign().id()).stream()
                .anyMatch(target -> !directions.deviceAllowed(target.deviceId()))) throw conflict();
    }
    /** 开启下一批才要求当前发布仍可执行。 */
    private void validateRelease(UUID tenant, UUID project, OtaCampaignRuntime current) {
        var release = qualification.validateCurrent(tenant, project, current.campaign().firmwareId());
        if (!release.release().id().equals(current.campaign().releaseId())
                || !Arrays.equals(release.release().canonicalManifest(), current.campaign().canonicalManifest())) throw conflict();
    }
    /** 只能读取成功当前批，失败和暂停不能变成扩批捷径。 */
    private static boolean ready(OtaCampaignRuntime value) {
        return "RUNNING".equals(value.campaign().status()) && value.currentBatch() != null
                && value.batchProgress() != null && "SUCCEEDED".equals(value.batchProgress().currentBatchStatus());
    }
    /** 审批策略来自不可变计划。 */
    private static boolean manual(OtaCampaignRuntime value) {
        return new OtaCampaignPlanCodec().decode(value.campaign().canonicalPlan()).value()
                .executionPolicy().requireManualBatchApproval();
    }
    /** 当前管理角色必须属于本项目。 */
    private void manage(UUID project) {
        var role = projects.requireRoleInProject(project);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN)
            throw new BusinessException(OtaCampaignRuntimeErrorCode.FORBIDDEN);
    }
    /** 头、批次和作业在同一锁定图读取。 */
    private OtaCampaignRuntime required(UUID project, UUID campaign) {
        return runtime.lockRuntime(project, campaign)
                .orElseThrow(() -> new BusinessException(OtaCampaignErrorCode.NOT_FOUND));
    }
    /** 审计失败传播并回滚不可变事实及CAS。 */
    private void record(UUID tenant, UUID project, UUID campaign, UUID actor, String action, String mode) {
        audit.record(new AuditLogEntry(tenant, project, actor, "ota_campaign", campaign, action,
                Map.of("actorKind", actor == null ? "SYSTEM" : "MANAGEMENT", "mode", mode)));
    }
    /** 规范十进制不接受符号、前导零或溢出。 */
    private static long number(String value, long maximum) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw invalid();
        try { long number = Long.parseLong(value); if (number > maximum) throw invalid(); return number; }
        catch (NumberFormatException failure) { throw invalid(); }
    }
    /** 禁止控制字符与孤立代理码元。 */
    private static boolean invalidText(String text) {
        return text.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff);
    }
    /** 输入语义不合法。 */
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
    /** 当前图或资格不满足放行条件。 */
    private static BusinessException conflict() { return new BusinessException(OtaCampaignRuntimeErrorCode.STATE_CONFLICT); }
}
