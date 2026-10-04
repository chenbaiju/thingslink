package com.things.link.ota.application;

import com.things.link.ota.domain.OtaCampaignErrorCode;
import com.things.link.ota.domain.OtaCampaignRepository;
import com.things.link.ota.domain.OtaJobProgressRepository;
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
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ADR0126活动管理控制平面，当前安全资格不能由旧排程快照替代。 */
@Service
public class OtaCampaignRuntimeService {
    /** 当前项目成员身份。 */ private final ProjectService projects;
    /** ACTIVE事务许可。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 项目控制锁及原子运行转移。 */ private final OtaCampaignRuntimeRepository repository;
    /** 安全触发作业的不可变设备身份。 */ private final OtaCampaignRepository campaigns;
    /** 不依赖虚构账号的内部当前资格。 */ private final OtaRuntimeQualification qualification;
    /** 已准入尝试的执行上下文。 */ private final OtaJobProgressRepository progress;
    /** 原执行来源不可由新报告替换。 */ private final OtaExecutionOriginGuard origins;
    /** 与业务转移同事务的审计。 */ private final AuditLogService audit;

    /** 显式注入管理授权与内部资格，两者不能互相代替。 */
    public OtaCampaignRuntimeService(ProjectService projects, ProjectLifecycleAccessService lifecycle,
            OtaCampaignRuntimeRepository repository, OtaCampaignRepository campaigns,
            OtaRuntimeQualification qualification, OtaJobProgressRepository progress,
            OtaExecutionOriginGuard origins, AuditLogService audit) {
        this.projects = projects; this.lifecycle = lifecycle; this.repository = repository;
        this.campaigns = campaigns; this.qualification = qualification; this.audit = audit;
        this.progress = progress; this.origins = origins;
    }

    /** 在控制锁内投影头与子作业的一致快照。 */
    @Transactional(timeout = 5)
    public OtaCampaignRuntime find(UUID project, UUID campaign) {
        write(project, false);
        return required(project, campaign);
    }

    /** 启动只激活首批，不代表已发送任何网络通知。 */
    @Transactional(timeout = 5)
    public OtaCampaignRuntime start(UUID project, UUID campaign, String key, String expectedRevision) {
        UUID tenant = write(project);
        requireKey(key);
        long expected = revision(expectedRevision);
        var current = required(project, campaign);
        requireRevision(current, expected);
        if (!"SCHEDULED".equals(current.campaign().status())) throw conflict();
        var plan = new OtaCampaignPlanCodec().decode(current.campaign().canonicalPlan());
        if (plan.value().notBefore().isAfter(repository.currentTime()))
            throw new BusinessException(OtaCampaignRuntimeErrorCode.NOT_DUE);
        validateRelease(tenant, project, current);
        if (!repository.start(project, campaign, expected, actor())) throw conflict();
        return record(project, campaign, "ota.campaign.started", null);
    }

    /** 手动暂停撤销待准入租约，但不回退已持久通知和原始预算。 */
    @Transactional(timeout = 5)
    public OtaCampaignRuntime pause(UUID project, UUID campaign, String key, String expectedRevision, String reason) {
        write(project);
        requireKey(key); requireReason(reason);
        long expected = revision(expectedRevision);
        requireRevision(required(project, campaign), expected);
        if (!repository.pause(project, campaign, expected, actor(), reason)) throw conflict();
        return record(project, campaign, "ota.campaign.paused", reason);
    }

    /** 恢复必须证明安全触发条件已解除；文字原因不能覆盖未决安全事实。 */
    @Transactional(timeout = 5)
    public OtaCampaignRuntime resume(UUID project, UUID campaign, String key, String expectedRevision, String reason) {
        UUID tenant = write(project);
        requireKey(key); requireReason(reason);
        long expected = revision(expectedRevision);
        var current = required(project, campaign);
        requireRevision(current, expected);
        if (!"PAUSED".equals(current.campaign().status())) throw conflict();
        var jobs = campaigns.jobs(project, campaign);
        var candidates = repository.resumeFailureCandidates(project, campaign);
        var requiredJobs = new java.util.LinkedHashSet<>(candidates);
        if (current.pauseJobId() != null) requiredJobs.add(current.pauseJobId());
        for (UUID jobId : requiredJobs) {
            var job = jobs.stream().filter(value -> value.id().equals(jobId)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("恢复关联作业丢失"));
            // 已封闭终态由SQL核验原证明；其余每个失败候选必须复核当前资格和原来源。
            if (!java.util.Set.of("SUCCEEDED", "ROLLED_BACK", "TIMED_OUT").contains(job.status())) {
                var outcome = qualification.check(tenant, project, job.deviceId(), current.campaign().firmwareId());
                if (outcome.disposition() != OtaRuntimeQualification.Disposition.ELIGIBLE) throw conflict();
                var execution = progress.locate(job.id()).orElseThrow(
                        () -> new IllegalStateException("恢复执行上下文丢失"));
                if (execution.attemptNo() > 0 && origins.rejection(project, job.id(), execution.attemptNo(),
                        job.deviceId(), outcome.credentialVersion(), outcome.reportRevision(),
                        outcome.reportHash()) != null) throw conflict();
            }
        }
        validateRelease(tenant, project, current);
        if (!repository.resume(project, campaign, expected, actor(), reason)) throw conflict();
        for (var job : campaigns.jobs(project, campaign)) {
            if (!candidates.contains(job.id())) continue;
            var details = new LinkedHashMap<String, Object>();
            details.put("actorKind", "SYSTEM");
            details.put("requestedBy", actor().toString());
            details.put("status", job.status());
            details.put("reason", "AUTHORIZED_RESUME_RECLASSIFICATION");
            audit.record(new AuditLogEntry(tenant, project, null, "ota_device_job", job.id(),
                    "ota.retry.authorizedResume", details));
        }
        return record(project, campaign, "ota.campaign.resumed", reason);
    }

    /** 配置或安全合同不再允许运行时统一状态冲突，基础设施首因继续抛出。 */
    private void validateRelease(UUID tenant, UUID project, OtaCampaignRuntime current) {
        try {
            var release = qualification.validateCurrent(tenant, project, current.campaign().firmwareId());
            if (!release.release().id().equals(current.campaign().releaseId())
                    || !java.util.Arrays.equals(release.release().canonicalManifest(),
                            current.campaign().canonicalManifest())) throw conflict();
        } catch (BusinessException | IllegalArgumentException failure) { throw conflict(); }
    }
    /** 管理角色在项目锁前后都复核，随后先拿项目OTA控制锁。 */
    private UUID write(UUID project) { return write(project, true); }
    /** 读取保留相同权限与锁顺序，但不启用完成来源。 */
    private UUID write(UUID project, boolean capture) {
        manage(project);
        UUID tenant = projects.requireProjectTenant(project);
        lifecycle.requireActiveForWrite(tenant, project);
        manage(project);
        if (capture) repository.controlLock(tenant, project);
        else repository.readControlLock(tenant, project);
        return tenant;
    }
    /** 普通成员不能读写运行控制。 */
    private void manage(UUID project) {
        ProjectRole role = projects.requireRoleInProject(project);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN)
            throw new BusinessException(OtaCampaignRuntimeErrorCode.FORBIDDEN);
    }
    /** 精确项目内行锁避免撕裂快照。 */
    private OtaCampaignRuntime required(UUID project, UUID campaign) {
        return repository.lockRuntime(project, campaign)
                .orElseThrow(() -> new BusinessException(OtaCampaignErrorCode.NOT_FOUND));
    }
    /** 审计失败回滚头、批次、租约、转移及outbox。 */
    private OtaCampaignRuntime record(UUID project, UUID campaign, String action, String reason) {
        var result = required(project, campaign);
        var details = new LinkedHashMap<String, Object>();
        details.put("status", result.campaign().status());
        details.put("stateVersion", Long.toString(result.campaign().stateVersion()));
        if (reason != null) details.put("reason", reason);
        audit.record(new AuditLogEntry(result.campaign().tenantId(), project, actor(),
                "ota_campaign", campaign, action, details));
        return result;
    }
    /** 每次管理写入使用真实当前账号。 */
    private static UUID actor() { return TenantContext.require().accountId(); }
    /** 不接受越界或非规范修订。 */
    private static long revision(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(value); } catch (NumberFormatException failure) { throw invalid(); }
    }
    /** 乐观修订必须完全一致，不将另一次暂停解释为重放。 */
    private static void requireRevision(OtaCampaignRuntime current, long expected) {
        if (current.campaign().stateVersion() != expected || expected == Long.MAX_VALUE) throw conflict();
    }
    /** 直接应用调用也必须提供幂等键。 */
    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128 || invalidText(key)) throw invalid();
    }
    /** 原因保留原文且有界。 */
    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.codePointCount(0, reason.length()) > 256
                || invalidText(reason)) throw invalid();
    }
    /** 控制字符及孤立代理码元不能进入稳定审计合同。 */
    private static boolean invalidText(String text) {
        return text.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff);
    }
    /** 正文语义错误。 */
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
    /** 当前状态或安全资格不再允许所请求的转移。 */
    private static BusinessException conflict() { return new BusinessException(OtaCampaignRuntimeErrorCode.STATE_CONFLICT); }
}
