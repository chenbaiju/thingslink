package com.things.link.ota.application;

import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaConfirmationRepository;
import com.things.link.ota.domain.OtaJobProgressReceipt;
import com.things.link.ota.domain.OtaJobProgressRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.tenant.DataPlaneDatabase;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 经原MQTT身份接纳作业进度，不把设备声明当成硬件资格或最终提交。 */
@Service
@DataPlaneDatabase
public class OtaJobProgressIngestionService {
    /** 原来源、进度和状态CAS。 */ private final OtaJobProgressRepository repository;
    /** 活动完整图锁。 */ private final OtaCampaignRuntimeRepository runtime;
    /** 当前身份及最终凭据检查。 */ private final OtaDeviceIdentityPort devices;
    /** 原来源严格解码。 */ private final OtaExecutionOriginGuard origins;
    /** 实际健康采用所固定的候选启动身份。 */ private final OtaConfirmationRepository confirmations;
    /** 当前项目生命周期门控。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 真实事务连接RLS。 */ private final TransactionLocalRlsScope rls;
    /** 与证据同事务的系统审计。 */ private final AuditLogService audit;
    /** 独立进度合同，不复用设备能力报告。 */ private final OtaJobProgressCodec codec = new OtaJobProgressCodec();
    /** 已受理工作允许安全进度，暂停和取消不是设备停止证据。 */
    private static final Set<String> ACTIVE_CAMPAIGNS = Set.of("RUNNING", "PAUSED", "CANCELLING", "COMPLETED", "CANCELLED");
    /** 确认与成功后只保存观察，不新增可重开终态的入口。 */
    private static final Set<String> OBSERVABLE_JOBS = Set.of("DISPATCHED", "DOWNLOADING", "VERIFYING",
            "INSTALLING", "REBOOTING", "HEALTH_CHECKING", "CONFIRMING", "SUCCEEDED", "RECOVERY_REQUIRED",
            "ROLLBACK_PENDING", "ROLLING_BACK", "ROLLED_BACK", "CANCELLED");
    /** 耐久停止即使尚未静止也禁止安装状态推进。 */ private final OtaInstallStopObservationGuard stops;
    /** 正常状态只能逐步推进，缺失安全阶段进入对账。 */
    private static final Map<String, String> NEXT = Map.of("DOWNLOADING", "VERIFYING", "VERIFYING", "INSTALLING",
            "INSTALLING", "REBOOTING", "REBOOTING", "HEALTH_CHECKING");

    /** 显式注入真正的领域事务与身份端口。 */
    public OtaJobProgressIngestionService(OtaJobProgressRepository repository, OtaCampaignRuntimeRepository runtime,
            OtaDeviceIdentityPort devices, OtaExecutionOriginGuard origins, ProjectLifecycleAccessService lifecycle,
            TransactionLocalRlsScope rls, AuditLogService audit, OtaConfirmationRepository confirmations, OtaInstallStopObservationGuard stops) {
        this.repository = repository; this.runtime = runtime; this.devices = devices; this.origins = origins;
        this.lifecycle = lifecycle; this.rls = rls; this.audit = audit; this.confirmations = confirmations; this.stops = stops;
    }
    /** 保留原身份和Broker时间；重放不写，暂时故障不伪装成永久坏报文。 */
    @Transactional(timeout = 5)
    public Outcome accept(AuthenticatedDeviceIdentity identity, byte[] payload, Instant brokerReceivedAt) {
        if (TenantContext.current().isPresent()) throw new IllegalStateException("OTA作业进度不能继承管理账号");
        if (identity == null || brokerReceivedAt == null) throw invalid();
        var decoded = codec.decode(payload);
        var input = decoded.value();
        rls.establish(identity.tenantId(), identity.projectId());
        if (!lifecycle.lockActiveForWrite(identity.tenantId(), identity.projectId())) throw invalid();
        runtime.controlLock(identity.tenantId(), identity.projectId());
        var located = repository.locate(input.jobId()).orElseThrow(OtaJobProgressIngestionService::invalid);
        if (!located.tenantId().equals(identity.tenantId()) || !located.projectId().equals(identity.projectId())
                || !located.deviceId().equals(identity.deviceId())) throw invalid();
        var graph = runtime.lockRuntime(identity.projectId(), located.campaignId())
                .orElseThrow(OtaJobProgressIngestionService::invalid);
        var current = repository.locate(input.jobId()).orElseThrow(OtaJobProgressIngestionService::invalid);
        devices.lockCurrent(identity).orElseThrow(OtaJobProgressIngestionService::invalid);
        if (current.credentialVersion() != identity.credentialVersion() || current.attemptNo() != input.attemptNo()
                || !input.manifestSha256().equals(current.manifestSha256())
                || !input.authorizationId().equals(current.authorizationId())) throw invalid();
        var previous = repository.find(input.jobId(), input.attemptNo(), input.progressSeq()).orElse(null);
        if (previous != null) {
            if (previous.credentialVersion() != identity.credentialVersion()
                    || !previous.payloadHash().equals(decoded.sha256())
                    || !Arrays.equals(previous.canonical(), decoded.canonical()) || !devices.credentialValid(identity)) throw invalid();
            return Outcome.REPLAY;
        }
        Instant now = runtime.currentTime();
        if (!ACTIVE_CAMPAIGNS.contains(graph.campaign().status()) || !OBSERVABLE_JOBS.contains(current.status())
                || !OtaDeviceReportIngestionService.fresh(brokerReceivedAt, now)) throw invalid();
        var latest = repository.latest(input.jobId(), input.attemptNo()).orElse(null);
        String next = null;
        String reason;
        if ("CANCELLED".equals(current.status()) || "CANCELLED".equals(graph.campaign().status())
                || stops.onlyObserve(current.jobId(), current.attemptNo())) {
            // 取消后的声明仍须校验原目标和阶段，不凭stage字符串产生安装矛盾。
            var origin = repository.origin(input.jobId(), input.attemptNo()).orElse(null);
            var first = repository.find(input.jobId(), input.attemptNo(), 1).orElse(null);
            reason = origin == null ? "EXECUTION_ORIGIN_MISSING"
                    : OtaJobProgressEvidenceValidator.mismatch(input, origins.decode(origin),
                            graph.campaign().canonicalManifest(), first == null ? null : first.bootId());
            if (reason == null) reason = "POST_STOP_PROGRESS_PROOF";
        } else if ("COMPLETED".equals(graph.campaign().status())
                || Set.of("CONFIRMING", "SUCCEEDED", "ROLLBACK_PENDING", "ROLLING_BACK", "ROLLED_BACK").contains(current.status())) {
            reason = "POST_HEALTH_PROGRESS_OBSERVATION";
        } else if ("RECOVERY_REQUIRED".equals(current.status())) {
            reason = "RECOVERY_ALREADY_REQUIRED";
        } else if (latest != null && input.progressSeq() < latest.progressSeq()) {
            reason = "STALE_PROGRESS";
        } else {
            var origin = repository.origin(input.jobId(), input.attemptNo()).orElse(null);
            reason = origin == null ? "EXECUTION_ORIGIN_MISSING" : null;
            long expectedSequence = latest == null ? 1 : latest.progressSeq() + 1;
            if (reason == null && input.progressSeq() != expectedSequence) reason = "PROGRESS_SEQUENCE_GAP";
            if (reason == null && latest != null && brokerReceivedAt.isBefore(latest.brokerReceivedAt())) {
                reason = "PROGRESS_TIME_REGRESSION";
            }
            if (reason == null && (current.deadlineAt() == null || brokerReceivedAt.isAfter(current.deadlineAt()))) {
                reason = "PROGRESS_AFTER_DEADLINE";
            }
            if (reason == null) {
                var first = repository.find(input.jobId(), input.attemptNo(), 1).orElse(null);
                reason = OtaJobProgressEvidenceValidator.mismatch(input, origins.decode(origin),
                        graph.campaign().canonicalManifest(), first == null ? null : first.bootId());
            }
            if (reason == null && "HEALTH_CHECKING".equals(current.status()) && "HEALTH_CHECKING".equals(input.stage())
                    && confirmations.candidateBoot(current.jobId(), current.attemptNo()).filter(input.bootId()::equals).isEmpty()) {
                reason = "CANDIDATE_BOOT_CHANGED";
            }
            if (reason != null) {
                next = "RECOVERY_REQUIRED";
            } else if (input.stage().equals(NEXT.get(current.status()))) {
                next = input.stage(); reason = "AUTHENTICATED_PROGRESS";
            } else if (input.stage().equals(current.status()) || priorStage(current.status(), input.stage())) {
                reason = "NON_ADVANCING_PROGRESS";
            } else {
                next = "RECOVERY_REQUIRED"; reason = "PROGRESS_STAGE_GAP";
            }
        }
        if (!devices.credentialValid(identity)) throw invalid();
        var receipt = new OtaJobProgressReceipt(Uuid7.generate(), identity.tenantId(), identity.projectId(),
                current.campaignId(), current.jobId(), identity.deviceId(), input.attemptNo(), identity.credentialVersion(),
                input.progressSeq(), input.authorizationId(), input.manifestSha256(), input.stage(), input.bootId(),
                decoded.canonical(), decoded.sha256(), brokerReceivedAt.truncatedTo(ChronoUnit.MICROS), runtime.currentTime());
        if (!repository.accept(current, receipt, next, reason)) throw invalid();
        audit.record(new AuditLogEntry(identity.tenantId(), identity.projectId(), null, "ota_device_job", current.jobId(),
                "ota.job.progress", Map.of("actorKind", "SYSTEM", "progressSeq", input.progressSeq(), "stage", input.stage(),
                        "decision", reason)));
        return "RECOVERY_REQUIRED".equals(next) ? Outcome.RECOVERY_REQUIRED : next == null ? Outcome.OBSERVED : Outcome.ADVANCED;
    }
    /** 新序号中的旧阶段只能观察，不能倒退状态。 */
    private static boolean priorStage(String current, String reported) {
        return stageOrder(reported) < stageOrder(current);
    }
    /** 固定阶段序，不用枚举声明顺序推断协议。 */
    private static int stageOrder(String stage) {
        return switch (stage) {
            case "DOWNLOADING" -> 0;
            case "VERIFYING" -> 1;
            case "INSTALLING" -> 2;
            case "REBOOTING" -> 3;
            case "HEALTH_CHECKING" -> 4;
            default -> -1;
        };
    }
    /** 固定错误不返回设备报告、地址或供应商内容。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("OTA作业进度未满足认证执行合同"); }
    /** 软件接纳结果不等于硬件完成或活动放行。 */
    public enum Outcome {
        /** 原证据只读确认。 */ REPLAY,
        /** 只保留不推进状态的观察。 */ OBSERVED,
        /** 合法阶段及一次期限已持久。 */ ADVANCED,
        /** 未决安全责任等待专门对账。 */ RECOVERY_REQUIRED
    }
}
