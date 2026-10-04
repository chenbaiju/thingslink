package com.things.link.ota.application;

import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.ota.domain.OtaCampaignRuntime;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaDownloadAuthorizationRepository;
import com.things.link.ota.domain.OtaDownloadRequestRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.tenant.DataPlaneDatabase;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR0128认证MQTT申请的持久接纳，不返回地址、不授予下载或修改作业阶段。
 *
 * <p><b>D-161同身份同窗口重投（本类新增的唯一语义）：</b>设备重连/重启后会按自己的耐久受理记录与耐久
 * 日志重投一次 <b>同一 (jobId, attemptNo, manifestSha256)</b> 的 {@code up/ota/download/request}。
 * 该请求不再无条件永久拒绝，而是被当作<b>幂等重投申请</b>处理，且必须同时满足：授权已封存并被接受过、
 * 活动仍 {@code RUNNING}、作业仍在同一尝试的 {@code DOWNLOADING}、本次认证身份/凭据/清单与不可变申请
 * 完全一致、设备当前资格与执行来源仍合格、<b>原封冻的响应窗口</b>仍留出可用余量、
 * {@code transport_count<3}，以及数据库侧当前图（项目ACTIVE、当前批次RUNNING、安装前停止未被接纳、
 * 无回滚设备冲突）成立。</p>
 *
 * <p><b>绝不改变或延长任何事实：</b>不新建 {@code ota_download_request}/{@code ota_download_authorization}
 * 行，不改任何身份字段，不重置 {@code transport_count}，也不把响应窗口推到原冻结期限之后；平台只是把
 * 既有授权重新放回可领取状态，让后台按生产顺序再签一次址、再封存一次密文并消耗下一次传输预算。
 * 重复重投申请是幂等的：标记已存在时只返回只读确认，不产生第二次写入。</p>
 */
@Service
@DataPlaneDatabase
public class OtaDownloadRequestIngestionService {
    /** 重投登记要求原冻结响应窗口至少还剩这么多，保证重签址与真实发送有可用物理预算。 */
    private static final Duration REISSUE_MIN_REMAINING = Duration.ofSeconds(16);
    /** 同一尝试已在进行中的交付/重投状态：重投申请只做幂等确认，不产生任何写入。 */
    private static final List<String> REISSUE_IN_PROGRESS = List.of("WAITING", "SIGNING", "SEALED", "IN_FLIGHT");
    /** 不可变请求与当前作用域作业。 */ private final OtaDownloadRequestRepository repository;
    /** D-161同身份重投登记：只读事实与幂等标记，不授予签址或发送能力。 */
    private final OtaDownloadAuthorizationRepository authorizations;
    /** 全项目OTA统一锁序和当前运行图。 */ private final OtaCampaignRuntimeRepository runtime;
    /** 当前已认证DIRECT设备，不重标旧连接。 */ private final OtaDeviceIdentityPort devices;
    /** 同事务完整当前资格。 */ private final OtaRuntimeQualification qualification;
    /** 原执行来源不被新报告覆盖。 */ private final OtaExecutionOriginGuard origins;
    /** 当前项目ACTIVE写许可。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 绑定真实事务连接的双轴范围。 */ private final TransactionLocalRlsScope rls;
    /** 请求和排队同事务SYSTEM审计。 */ private final AuditLogService audit;
    /** 严格五字段规范化。 */ private final OtaDownloadRequestCodec codec = new OtaDownloadRequestCodec();

    /** 只依赖领域与公开应用边界，不以HTTP管理账号替代设备认证。 */
    public OtaDownloadRequestIngestionService(OtaDownloadRequestRepository repository,
            OtaDownloadAuthorizationRepository authorizations, OtaCampaignRuntimeRepository runtime,
            OtaDeviceIdentityPort devices, OtaRuntimeQualification qualification,
            ProjectLifecycleAccessService lifecycle, TransactionLocalRlsScope rls, AuditLogService audit, OtaExecutionOriginGuard origins) {
        this.repository = repository; this.authorizations = authorizations; this.runtime = runtime;
        this.devices = devices; this.qualification = qualification;
        this.lifecycle = lifecycle; this.rls = rls; this.audit = audit; this.origins = origins;
    }

    /** 原raw身份与Broker时刻不得由请求正文补充；永久拒绝与基础设施异常分开传播。 */
    @Transactional(timeout = 5)
    public Outcome accept(AuthenticatedDeviceIdentity identity, byte[] payload, Instant brokerReceivedAt) {
        if (identity == null || brokerReceivedAt == null) throw invalid();
        if (TenantContext.current().isPresent()) throw new IllegalStateException("OTA设备申请不能继承管理账号");
        var decoded = codec.decode(payload);
        var input = decoded.value();
        rls.establish(identity.tenantId(), identity.projectId());
        if (!lifecycle.lockActiveForWrite(identity.tenantId(), identity.projectId())) throw invalid();
        runtime.controlLock(identity.tenantId(), identity.projectId());
        var located = repository.locate(input.jobId()).orElseThrow(OtaDownloadRequestIngestionService::invalid);
        if (!located.tenantId().equals(identity.tenantId()) || !located.projectId().equals(identity.projectId())
                || !located.deviceId().equals(identity.deviceId())) throw invalid();
        var graph = runtime.lockRuntime(identity.projectId(), located.campaignId())
                .orElseThrow(OtaDownloadRequestIngestionService::invalid);
        var job = repository.locate(input.jobId()).orElseThrow(OtaDownloadRequestIngestionService::invalid);
        devices.lockCurrent(identity).orElseThrow(OtaDownloadRequestIngestionService::invalid);
        var previous = repository.find(identity.deviceId(), input.requestId()).orElse(null);
        if (previous != null) {
            if (previous.credentialVersion() != identity.credentialVersion()
                    || !previous.canonicalSha256().equals(decoded.sha256())
                    || !Arrays.equals(previous.canonical(), decoded.canonical()) || !devices.credentialValid(identity)) {
                throw invalid();
            }
            return Outcome.REPLAY;
        }
        Instant now = runtime.currentTime();
        // D-161：同一作业尝试已有申请时不再无条件永久拒绝，而是走「同身份同窗口重投」判定。
        var existing = repository.findByJobAttempt(input.jobId(), input.attemptNo()).orElse(null);
        if (existing != null) return reissue(identity, input, existing, graph, job, brokerReceivedAt, now);
        if (!"RUNNING".equals(graph.campaign().status()) || !"DISPATCHED".equals(job.jobStatus())
                || job.attemptNo() != input.attemptNo() || job.credentialVersion() != identity.credentialVersion()
                || !job.manifestSha256().equals(input.manifestSha256()) || !now.isBefore(job.originalDeadline())
                || !OtaDeviceReportIngestionService.fresh(brokerReceivedAt, now)) throw invalid();
        var outcome = qualification.check(identity.tenantId(), identity.projectId(), identity.deviceId(), job.firmwareId());
        if (!devices.credentialValid(identity)) throw invalid();
        if (outcome.disposition() == OtaRuntimeQualification.Disposition.SECURITY) {
            if (!repository.safetyPause(job, graph.campaign().stateVersion(), outcome.reason())) throw invalid();
            audit.record(new AuditLogEntry(identity.tenantId(), identity.projectId(), null, "ota_device_job", job.jobId(),
                    "ota.download_request.security_pause", Map.of("actorKind", "SYSTEM", "reason", outcome.reason())));
            return Outcome.SECURITY_PAUSED;
        }
        if (outcome.disposition() != OtaRuntimeQualification.Disposition.ELIGIBLE
                || outcome.credentialVersion() == null || outcome.credentialVersion() != identity.credentialVersion()) throw invalid();
        String sourceReason = origins.rejection(identity.projectId(), job.jobId(), input.attemptNo(), identity.deviceId(),
                identity.credentialVersion(), outcome.reportRevision(), outcome.reportHash());
        if (!devices.credentialValid(identity)) throw invalid();
        if (sourceReason != null) {
            if (!repository.safetyPause(job, graph.campaign().stateVersion(), sourceReason)) throw invalid();
            audit.record(new AuditLogEntry(identity.tenantId(), identity.projectId(), null, "ota_device_job", job.jobId(),
                    "ota.download_request.security_pause", Map.of("actorKind", "SYSTEM", "reason", sourceReason)));
            return Outcome.SECURITY_PAUSED;
        }
        var request = new OtaDownloadRequestRepository.Request(Uuid7.generate(), identity.tenantId(), identity.projectId(),
                identity.deviceId(), identity.credentialVersion(), input.requestId(), job.jobId(), job.campaignId(),
                job.firmwareId(), input.attemptNo(), input.manifestSha256(), decoded.canonical(), decoded.sha256(),
                job.originalDeadline(), brokerReceivedAt.truncatedTo(ChronoUnit.MICROS), runtime.currentTime(),
                outcome.reportRevision(), outcome.reportHash());
        if (!repository.create(request, job.jobRevision())) throw invalid();
        audit.record(new AuditLogEntry(identity.tenantId(), identity.projectId(), null, "ota_download_request", request.id(),
                "ota.download_request.accepted", Map.of("actorKind", "SYSTEM", "requestId", input.requestId().toString(),
                        "jobId", job.jobId().toString())));
        return Outcome.ACCEPTED;
    }
    /**
     * D-161同身份同窗口重投判定：既有申请仍是最权威的身份来源，重投绝不新建或重置任何事实。
     *
     * <p>只有原申请的全部身份字段、当前作业的尝试/凭据/清单、设备当前资格与执行来源、原冻结响应窗口、
     * 传输预算和数据库当前图全部成立时才登记重投标记；否则一律沿用今天的永久拒绝分类。</p>
     */
    private Outcome reissue(AuthenticatedDeviceIdentity identity, OtaDownloadRequestCodec.Request input,
            OtaDownloadRequestRepository.Request existing, OtaCampaignRuntime graph,
            OtaDownloadRequestRepository.JobContext job, Instant brokerReceivedAt, Instant now) {
        if (!existing.tenantId().equals(identity.tenantId()) || !existing.projectId().equals(identity.projectId())
                || !existing.deviceId().equals(identity.deviceId())
                || !existing.campaignId().equals(job.campaignId()) || !existing.firmwareId().equals(job.firmwareId())
                || existing.attemptNo() != input.attemptNo() || !existing.manifestSha256().equals(input.manifestSha256())
                || existing.credentialVersion() != identity.credentialVersion()) throw invalid();
        if (!"RUNNING".equals(graph.campaign().status()) || !"DOWNLOADING".equals(job.jobStatus())
                || job.attemptNo() != input.attemptNo() || job.credentialVersion() != identity.credentialVersion()
                || !job.manifestSha256().equals(input.manifestSha256())
                || !OtaDeviceReportIngestionService.fresh(brokerReceivedAt, now)) throw invalid();
        var found = authorizations.findReissue(existing.id()).orElseThrow(OtaDownloadRequestIngestionService::invalid);
        if (found.transportCount() >= 3) throw invalid();
        if (!"BROKER_ACCEPTED".equals(found.status())) {
            // 同一尝试的首次交付或重投周期已在进行中：只做幂等确认，不创建、不重置、不延长。
            if (REISSUE_IN_PROGRESS.contains(found.status())) return Outcome.REISSUE_CONFIRMED;
            throw invalid();
        }
        // 重投复用原冻结窗口：既不延长，也不接受已经不足以完成一次重签址与发送的残余窗口。
        if (found.responseExpiresAt() == null || !now.plus(REISSUE_MIN_REMAINING).isBefore(found.responseExpiresAt())
                || !now.isBefore(job.originalDeadline())) throw invalid();
        var outcome = qualification.check(identity.tenantId(), identity.projectId(), identity.deviceId(), job.firmwareId());
        if (!devices.credentialValid(identity)) throw invalid();
        if (outcome.disposition() == OtaRuntimeQualification.Disposition.SECURITY) {
            if (!repository.safetyPause(job, graph.campaign().stateVersion(), outcome.reason())) throw invalid();
            audit.record(new AuditLogEntry(identity.tenantId(), identity.projectId(), null, "ota_device_job", job.jobId(),
                    "ota.download_request.security_pause", Map.of("actorKind", "SYSTEM", "reason", outcome.reason())));
            return Outcome.SECURITY_PAUSED;
        }
        if (outcome.disposition() != OtaRuntimeQualification.Disposition.ELIGIBLE
                || outcome.credentialVersion() == null || outcome.credentialVersion() != identity.credentialVersion()) throw invalid();
        String sourceReason = origins.rejection(identity.projectId(), job.jobId(), input.attemptNo(), identity.deviceId(),
                identity.credentialVersion(), outcome.reportRevision(), outcome.reportHash());
        if (!devices.credentialValid(identity)) throw invalid();
        if (sourceReason != null) {
            if (!repository.safetyPause(job, graph.campaign().stateVersion(), sourceReason)) throw invalid();
            audit.record(new AuditLogEntry(identity.tenantId(), identity.projectId(), null, "ota_device_job", job.jobId(),
                    "ota.download_request.security_pause", Map.of("actorKind", "SYSTEM", "reason", sourceReason)));
            return Outcome.SECURITY_PAUSED;
        }
        if (found.requested()) return Outcome.REISSUE_CONFIRMED;
        if (!authorizations.requestReissue(existing.id(), found.revision(), job.jobRevision())) throw invalid();
        audit.record(new AuditLogEntry(identity.tenantId(), identity.projectId(), null, "ota_download_authorization",
                existing.id(), "ota.download_request.reissue_requested",
                Map.of("actorKind", "SYSTEM", "jobId", job.jobId().toString(), "attemptNo", input.attemptNo())));
        return Outcome.REISSUE_REQUESTED;
    }

    /** 固定错误不泄露设备、请求或供应商内容。 */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("OTA下载申请身份、作业或当前资格不满足接纳合同");
    }
    /** 接纳只表示持久排队，重放和安全暂停都不是新的下载授权。 */
    public enum Outcome {
        /** 首次接纳并持久排队。 */ ACCEPTED,
        /** 相同不可变事实的只读确认。 */ REPLAY,
        /** 真实当前安全异常已同事务暂停。 */ SECURITY_PAUSED,
        /** D-161：同一身份同一冻结窗口的重投已被幂等登记，等待后台重新签址与发送。 */ REISSUE_REQUESTED,
        /** D-161：同一尝试的交付/重投周期已在进行中，本次只做幂等确认。 */ REISSUE_CONFIRMED
    }
}
