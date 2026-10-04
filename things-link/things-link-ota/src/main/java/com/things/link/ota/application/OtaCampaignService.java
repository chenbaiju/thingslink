package com.things.link.ota.application;

import com.things.link.device.application.OtaDeviceTargetSnapshotPort;
import com.things.link.ota.domain.OtaCampaign;
import com.things.link.ota.domain.OtaCampaignErrorCode;
import com.things.link.ota.domain.OtaCampaignRepository;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ADR0125完整未派发活动事务；排程快照不能替代后续派发资格。 */
@Service
public class OtaCampaignService {
    /** 项目当前角色与真实归属。 */ private final ProjectService projects;
    /** 贯穿事务的ACTIVE项目锁。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 本域不可变计划及原子转移。 */ private final OtaCampaignRepository repository;
    /** 设备域一次锁定全部显式目标。 */ private final OtaDeviceTargetSnapshotPort devices;
    /** 加入当前事务的完整签名发布资格。 */ private final OtaReleaseDownloadService releases;
    /** 同事务高危审计。 */ private final AuditLogService audit;
    /** 先于活动及固件的项目控制锁。 */ private final OtaCampaignRuntimeRepository runtime;
    /** 当前设备安全隔离在管理排程前复核，数据库仍有最终守卫。 */ private final OtaRollbackDirectionGuard directions;
    /** 严格有界计划合同。 */ private final OtaCampaignPlanCodec codec = new OtaCampaignPlanCodec();

    /** 显式注入跨域公开端口，禁止在OTA中读取设备表。 */
    public OtaCampaignService(ProjectService projects, ProjectLifecycleAccessService lifecycle,
            OtaCampaignRepository repository, OtaDeviceTargetSnapshotPort devices,
            OtaReleaseDownloadService releases, AuditLogService audit, OtaCampaignRuntimeRepository runtime, OtaRollbackDirectionGuard directions) {
        this.projects = projects; this.lifecycle = lifecycle; this.repository = repository;
        this.devices = devices; this.releases = releases; this.audit = audit; this.runtime = runtime; this.directions = directions;
    }

    /** 精确恢复同账号原草稿；规范目标排序避免同集合产生不同请求身份。 */
    @Transactional(timeout = 10)
    public View create(UUID project, String key, byte[] body) {
        UUID tenant = write(project);
        requireKey(key);
        OtaCampaignPlanCodec.Decoded plan;
        try { plan = codec.decode(body); }
        catch (IllegalArgumentException failure) { throw new BusinessException(CommonErrorCode.MALFORMED_REQUEST); }
        UUID actor = TenantContext.require().accountId();
        String keyHash = sha(("tc-ota-campaign-create-key/v1\0" + key).getBytes(StandardCharsets.UTF_8));
        repository.lockCreation(tenant, project, actor, keyHash);
        var existing = repository.findCreation(project, actor, keyHash).orElse(null);
        if (existing != null) {
            if (!existing.requestDigest().equals(plan.sha256())) { throw conflict(); }
            OtaCampaign restored = findLocked(project, existing.campaignId());
            if (!restored.tenantId().equals(tenant) || !restored.createdBy().equals(actor)
                    || !restored.planSha256().equals(plan.sha256())) {
                throw new IllegalStateException("OTA活动创建恢复身份漂移");
            }
            if (!"DRAFT".equals(restored.status())) {
                throw new BusinessException(CommonErrorCode.IDEMPOTENCY_RESULT_NOT_REPLAYABLE);
            }
            var currentRelease = releases.lockForQualification(project, restored.firmwareId());
            if (!restored.releaseId().equals(currentRelease.release().id())
                    || !Arrays.equals(restored.canonicalManifest(), currentRelease.release().canonicalManifest())) {
                throw conflict();
            }
            return view(restored);
        }
        requireHorizon(plan);
        var release = releases.lockForQualification(project, plan.value().firmwareId());
        Instant now = now();
        OtaCampaign campaign = new OtaCampaign(Uuid7.generate(), tenant, project, plan.value().firmwareId(),
                release.release().id(), actor, plan.canonical(), plan.sha256(), release.release().canonicalManifest(),
                sha(release.release().canonicalManifest()), "DRAFT", 0, 0, 0, now, now, null, null, null);
        repository.create(campaign, keyHash, plan.sha256());
        record(campaign, "ota.campaign.created");
        return view(campaign);
    }

    /** 锁头后读取子作业，避免读已排程头却混入并发取消后的子状态。 */
    @Transactional(timeout = 10)
    public View find(UUID project, UUID campaign) {
        manage(project);
        return view(findLocked(project, campaign));
    }

    /** 管理端只读列表：角色在服务内独立复核，仓储只投影白名单列并让游标绑定项目。
     * @param project 精确项目
     * @param cursor 上一页返回的不透明位置，首页为空
     * @param limit 单页上限，1到100
     * @return 活动摘要游标页
     */
    @Transactional(readOnly = true, timeout = 5)
    public CursorPage<OtaCampaignRepository.Summary> list(UUID project, String cursor, int limit) {
        manage(project);
        return repository.search(project, cursor, limit);
    }

    /** 管理端只读批次：先复核角色，再按项目判定存在性，跨项目身份与未知同样返回70034。
     * @param project 精确项目
     * @param campaign 精确活动身份
     * @return 按批次号升序的冻结批次投影，草稿活动为空列表
     */
    @Transactional(readOnly = true, timeout = 5)
    public List<OtaCampaignRepository.Batch> batches(UUID project, UUID campaign) {
        manage(project);
        repository.find(project, campaign, false)
                .orElseThrow(() -> new BusinessException(OtaCampaignErrorCode.NOT_FOUND));
        return repository.batches(project, campaign);
    }

    /** 管理端作业列表：服务内独立复核角色与活动归属，跨项目游标或活动都返回稳定错误。
     * @param project 精确项目
     * @param campaign 精确活动身份
     * @param cursor 上一页返回的不透明位置，首页为空
     * @param limit 单页上限，1到200
     * @return 按身份倒序的作业摘要游标页
     */
    @Transactional(readOnly = true, timeout = 5)
    public CursorPage<OtaCampaignRepository.JobSummary> listJobs(UUID project, UUID campaign, String cursor,
            int limit) {
        manage(project);
        repository.find(project, campaign, false)
                .orElseThrow(() -> new BusinessException(OtaCampaignErrorCode.NOT_FOUND));
        return repository.jobs(project, campaign, cursor, limit);
    }

    /** 单个作业与不可变转移时间线；未知、跨项目或不属于该活动都是70034。
     * @param project 精确项目
     * @param campaign 精确活动身份
     * @param job 精确作业身份
     * @return 作业详情与按修订升序的真实转移
     */
    @Transactional(readOnly = true, timeout = 5)
    public OtaCampaignRepository.JobDetail findJob(UUID project, UUID campaign, UUID job) {
        manage(project);
        repository.find(project, campaign, false)
                .orElseThrow(() -> new BusinessException(OtaCampaignErrorCode.NOT_FOUND));
        return repository.job(project, campaign, job)
                .orElseThrow(() -> new BusinessException(OtaCampaignErrorCode.NOT_FOUND));
    }

    /** 原子冻结全部目标与PENDING占位；不预先要求报告、有效凭据或在线状态。 */
    @Transactional(timeout = 10)
    public View schedule(UUID project, UUID campaignId, String key, String expectedRevision) {
        UUID tenant = write(project);
        requireKey(key);
        long expected = revision(expectedRevision);
        OtaCampaign current = findLocked(project, campaignId);
        if ("SCHEDULED".equals(current.status())) {
            if (expected != current.stateVersion() && expected != current.stateVersion() - 1) { throw conflict(); }
            return view(current);
        }
        if (!"DRAFT".equals(current.status()) || expected != current.stateVersion()) { throw conflict(); }
        var plan = decodeStored(current);
        requireHorizon(plan);
        // 清单在创建时已完整验签并冻结；定位类型不先锁固件，避免与设备资格路径反序。
        UUID type = UUID.fromString((String) new OtaCanonicalJson().parseObject(current.canonicalManifest())
                .get("deviceTypeId"));
        var targets = devices.lockExplicit(tenant, project, type, plan.value().deviceIds())
                .orElseThrow(() -> new BusinessException(OtaCampaignErrorCode.TARGET_INELIGIBLE));
        if(targets.stream().anyMatch(target->!directions.deviceAllowed(target.deviceId())))
            throw new BusinessException(OtaCampaignErrorCode.TARGET_OCCUPIED);
        var release = releases.lockForQualification(project, current.firmwareId());
        if (!type.equals(release.firmware().deviceTypeId()) || !current.releaseId().equals(release.release().id())
                || !Arrays.equals(current.canonicalManifest(), release.release().canonicalManifest())) { throw conflict(); }
        Instant now = maximum(now(), current.updatedAt());
        OtaCampaign scheduled = new OtaCampaign(current.id(), tenant, project, current.firmwareId(), current.releaseId(),
                current.createdBy(), current.canonicalPlan(), current.planSha256(), current.canonicalManifest(),
                current.manifestSha256(), "SCHEDULED", current.stateVersion() + 1, targets.size(),
                plan.value().batches().size(), current.createdAt(), now, now, null, null);
        try {
            boolean changed = repository.schedule(expected, scheduled, targets.stream().map(t ->
                    new OtaCampaignRepository.Target(t.deviceId(), t.deviceTypeId(), t.thingModelVersionId(),
                            t.credentialVersion())).toList(), plan.value().batchSize(),
                    TenantContext.require().accountId(), now);
            if (!changed) { throw conflict(); }
        } catch (OtaCampaignRepository.TargetOccupiedException failure) {
            throw new BusinessException(OtaCampaignErrorCode.TARGET_OCCUPIED);
        }
        record(scheduled, "ota.campaign.scheduled");
        return view(scheduled);
    }

    /** 取消未派发意图；运行活动保留已派发设备的安全责任至可信终态。 */
    @Transactional(timeout = 10)
    public View cancel(UUID project, UUID campaignId, String key, String expectedRevision, String reason) {
        write(project);
        requireKey(key);
        long expected = revision(expectedRevision);
        requireReason(reason);
        OtaCampaign current = findLocked(project, campaignId);
        if ("RUNNING".equals(current.status()) || "PAUSED".equals(current.status())
                || "CANCELLING".equals(current.status()) || "CANCELLED".equals(current.status())) {
            var execution = runtime.lockRuntime(project, campaignId)
                    .orElseThrow(() -> new BusinessException(OtaCampaignErrorCode.NOT_FOUND));
            var cancellation = execution.runtimeCancellation();
            if (cancellation != null) {
                if ((expected != cancellation.requestedRevision() && expected != current.stateVersion())
                        || !reason.equals(cancellation.reason())) { throw conflict(); }
                return view(current);
            }
            if (!"CANCELLED".equals(current.status())) {
                if (expected != current.stateVersion()
                        || !runtime.cancelRuntime(project, campaignId, expected,
                                TenantContext.require().accountId(), reason)) { throw conflict(); }
                OtaCampaign cancelled = findLocked(project, campaignId);
                record(cancelled, "ota.campaign.cancellation_requested");
                return view(cancelled);
            }
        }
        if ("CANCELLED".equals(current.status())) {
            long before = current.stateVersion() - (current.scheduledAt() == null ? 1 : 2);
            if ((expected != current.stateVersion() && expected != before)
                    || !reason.equals(current.cancellationReason())) { throw conflict(); }
            return view(current);
        }
        if ((!"DRAFT".equals(current.status()) && !"SCHEDULED".equals(current.status()))
                || expected != current.stateVersion()) { throw conflict(); }
        if (!repository.cancelUndispatched(expected, current, TenantContext.require().accountId(),
                maximum(now(), current.updatedAt()), reason)) { throw conflict(); }
        OtaCampaign cancelled = findLocked(project, campaignId);
        record(cancelled, "ota.campaign.cancelled");
        return view(cancelled);
    }

    /** 角色与ACTIVE锁先后都要检查，不能复用HTTP缓存的旧身份。 */
    private UUID write(UUID project) {
        manage(project);
        UUID tenant = projects.requireProjectTenant(project);
        lifecycle.requireActiveForWrite(tenant, project);
        manage(project);
        runtime.controlLock(tenant, project);
        return tenant;
    }
    /** Controller也可调用首层守卫；这里仍独立复核。 */
    private void manage(UUID project) {
        ProjectRole role = projects.requireRoleInProject(project);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) {
            throw new BusinessException(OtaCampaignErrorCode.FORBIDDEN);
        }
    }
    /** 精确项目查找且持有头锁至读取或写事务结束。 */
    private OtaCampaign findLocked(UUID project, UUID id) {
        return repository.find(project, id, true)
                .orElseThrow(() -> new BusinessException(OtaCampaignErrorCode.NOT_FOUND));
    }
    /** 只投影已持久事实；损坏不能默认为空计划。 */
    private View view(OtaCampaign campaign) {
        return new View(campaign, decodeStored(campaign), repository.jobs(campaign.projectId(), campaign.id()));
    }
    /** 内部规范摘要不符属于持久完整性错误，不归咎于本次客户端正文。 */
    private OtaCampaignPlanCodec.Decoded decodeStored(OtaCampaign c) {
        try {
            var p = codec.decode(c.canonicalPlan());
            if (!p.sha256().equals(c.planSha256()) || !Arrays.equals(p.canonical(), c.canonicalPlan())
                    || !sha(c.canonicalManifest()).equals(c.manifestSha256())
                    || !p.value().firmwareId().equals(c.firmwareId())) {
                throw new IllegalArgumentException();
            }
            return p;
        } catch (IllegalArgumentException failure) { throw new IllegalStateException("OTA活动持久快照不完整", failure); }
    }
    /** 计划只允许最多提前30天；过去时刻表示立即可执行，不改写快照。 */
    private static void requireHorizon(OtaCampaignPlanCodec.Decoded plan) {
        if (plan.value().notBefore().isAfter(Instant.now().plus(30, ChronoUnit.DAYS))) { throw invalid(); }
    }
    /** 领域写入不能依赖某一个HTTP过滤器替它检查键。 */
    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128 || invalidText(key)) { throw invalid(); }
    }
    /** 取消审计保存原文，拒绝空白及无法稳定表示的Unicode。 */
    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.codePointCount(0, reason.length()) > 256
                || invalidText(reason)) { throw invalid(); }
    }
    /** 拒绝控制字符与孤立代理码元。 */
    private static boolean invalidText(String text) {
        return text.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff);
    }
    /** API修订使用十进制字符串，避免前端整数精度损失。 */
    private static long revision(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) { throw invalid(); }
        try { return Long.parseLong(value); } catch (NumberFormatException failure) { throw invalid(); }
    }
    /** 时间入库前微秒化，确保首次与重放一致。 */
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    /** 系统时钟回拨不得倒退活动时间。 */
    private static Instant maximum(Instant left, Instant right) { return left.isBefore(right) ? right : left; }
    /** 固定SHA256用于规范正文身份，不包含客户端原始键。 */
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("运行时缺少SHA256", failure); }
    }
    /** 审计失败使头、子作业、转移与Outbox一起回滚。 */
    private void record(OtaCampaign c, String action) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", c.status()); details.put("stateVersion", Long.toString(c.stateVersion()));
        details.put("planSha256", c.planSha256());
        if (c.cancellationReason() != null) { details.put("reason", c.cancellationReason()); }
        audit.record(new AuditLogEntry(c.tenantId(), c.projectId(), TenantContext.require().accountId(),
                "ota_campaign", c.id(), action, details));
    }
    /** 非法合同值。 */
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
    /** 非法状态或CAS。 */
    private static BusinessException conflict() { return new BusinessException(OtaCampaignErrorCode.STATE_CONFLICT); }
    /** 应用层一致快照，HTTP必须显式白名单投影。
     * @param campaign 持久活动
     * @param plan 已校验规范计划
     * @param jobs 冻结作业列表
     */
    public record View(OtaCampaign campaign, OtaCampaignPlanCodec.Decoded plan, List<OtaCampaignRepository.Job> jobs) {
        /** 列表采用独立不可变副本。 */
        public View { jobs = List.copyOf(jobs); }
    }
}
