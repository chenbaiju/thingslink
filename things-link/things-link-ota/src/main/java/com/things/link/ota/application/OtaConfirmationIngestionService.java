package com.things.link.ota.application;

import com.things.link.device.application.OtaDeviceCommitPort;
import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.ota.domain.OtaCampaignRuntime;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaCommitPermit;
import com.things.link.ota.domain.OtaCommitReceipt;
import com.things.link.ota.domain.OtaConfirmationRepository;
import com.things.link.ota.domain.OtaFirmwareRepository;
import com.things.link.ota.domain.OtaHealthReceipt;
import com.things.link.ota.domain.OtaJobProgressRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.error.BusinessException;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 健康连续性、固定提交许可和实际设备提交事实，不把观察或网络回执冒充成功。 */
@Service
@DataPlaneDatabase
public class OtaConfirmationIngestionService {
    /** 已接受停止但未静止仍禁止新许可与模型采用。 */ private final OtaInstallStopObservationGuard stops;
    /** 允许必要安全观察的运行活动。 */ private static final Set<String> ACTIVE = Set.of("RUNNING", "PAUSED", "CANCELLING", "COMPLETED", "CANCELLED");
    /** 独立健康与提交事实。 */ private final OtaConfirmationRepository confirmations;
    /** 当前作业精确范围与修订。 */ private final OtaJobProgressRepository progress;
    /** 完整项目控制锁。 */ private final OtaCampaignRuntimeRepository runtime;
    /** 原执行来源与规范报告。 */ private final OtaExecutionOriginGuard origins;
    /** 只供观察路径共享锁，提交路径由本域写锁端口负责。 */ private final OtaDeviceIdentityPort identities;
    /** 已验证设备提交事实、下限和模型来源CAS。 */ private final OtaDeviceCommitPort commits;
    /** 当前已知下限可阻止新不可逆许可。 */ private final OtaKnownSecurityFloor floors;
    /** 历史方向冲突形成设备级永久安全隔离。 */ private final OtaRollbackDirectionGuard directions;
    /** 不可变固件类型定位。 */ private final OtaFirmwareRepository firmwares;
    /** 不借管理身份检查当前签名发布图。 */ private final OtaRuntimeQualification qualification;
    /** 真实ACTIVE生命周期许可。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 当前物理连接RLS。 */ private final TransactionLocalRlsScope rls;
    /** 与状态事实原子提交的系统审计。 */ private final AuditLogService audit;
    /** 新独立健康协议。 */ private final OtaHealthCodec healthCodec = new OtaHealthCodec();
    /** 新独立提交回执协议。 */ private final OtaCommitReceiptCodec commitCodec = new OtaCommitReceiptCodec();
    /** 固定规范许可。 */ private final OtaCommitPermitCodec permitCodec = new OtaCommitPermitCodec();

    /** 每个依赖共享调用方短事务，网络不进入本服务。 */
    public OtaConfirmationIngestionService(OtaConfirmationRepository confirmations, OtaJobProgressRepository progress,
            OtaCampaignRuntimeRepository runtime, OtaExecutionOriginGuard origins, OtaDeviceIdentityPort identities,
            OtaDeviceCommitPort commits, OtaFirmwareRepository firmwares, OtaRuntimeQualification qualification,
            ProjectLifecycleAccessService lifecycle, TransactionLocalRlsScope rls, AuditLogService audit, OtaKnownSecurityFloor floors, OtaRollbackDirectionGuard directions, OtaInstallStopObservationGuard stops) {
        this.confirmations = confirmations; this.progress = progress; this.runtime = runtime; this.origins = origins;
        this.identities = identities; this.commits = commits; this.firmwares = firmwares; this.qualification = qualification;
        this.lifecycle = lifecycle; this.rls = rls; this.audit = audit; this.floors = floors; this.directions = directions; this.stops = stops;
    }

    /** 连续健康观察按原Broker时间积累，不因重放、暂停或排队刷新原期限。 */
    @Transactional(timeout = 5)
    public Outcome acceptHealth(AuthenticatedDeviceIdentity identity, byte[] payload, Instant brokerAt) {
        if (identity == null || brokerAt == null) throw invalid();
        var decoded = healthCodec.decode(payload); var input = decoded.value();
        var locked = lock(identity, input.jobId()); var job = locked.job(); var graph = locked.graph();
        requireAxes(identity, job, input.attemptNo(), input.authorizationId(), input.manifestSha256());
        var device = identities.lockCurrent(identity).orElseThrow(OtaConfirmationIngestionService::invalid);
        var replay = confirmations.findHealth(job.jobId(), job.attemptNo(), input.healthSeq()).orElse(null);
        if (replay != null) {
            requireReplay(identity, replay.payloadHash(), replay.canonical(), decoded.sha256(), decoded.canonical());
            return Outcome.REPLAY;
        }
        Instant now = runtime.currentTime();
        boolean stopObserved = stops.onlyObserve(job.jobId(), job.attemptNo());
        if (!ACTIVE.contains(graph.campaign().status()) || !OtaDeviceReportIngestionService.fresh(brokerAt, now)
                || (!Set.of("HEALTH_CHECKING", "CONFIRMING", "RECOVERY_REQUIRED", "SUCCEEDED", "ROLLBACK_PENDING", "ROLLING_BACK", "ROLLED_BACK", "CANCELLED").contains(job.status())
                    && !(stopObserved && Set.of("DISPATCHED", "DOWNLOADING", "VERIFYING").contains(job.status())))) throw invalid();
        String next = null; String reason = "HEALTH_OBSERVATION"; OtaCommitPermit permit = null;
        var latest = confirmations.latestHealth(job.jobId(), job.attemptNo()).orElse(null);
        if ("CANCELLED".equals(job.status()) || stopObserved) {
            // 停止前可能尚无健康候选，不能凭空要求既有candidateBoot；仍须原启动与全部目标证据。
            var origin = progress.origin(job.jobId(), job.attemptNo()).orElse(null);
            var first = progress.find(job.jobId(), job.attemptNo(), 1).orElse(null);
            reason = origin == null ? "EXECUTION_ORIGIN_MISSING"
                    : OtaJobProgressEvidenceValidator.mismatch(new OtaJobProgressCodec.Progress(
                            "tc-ota-job-progress/v1", job.jobId(), job.attemptNo(), 1, job.authorizationId(),
                            job.manifestSha256(), "HEALTH_CHECKING", input.bootId(), input.evidence().target()),
                            origins.decode(origin), graph.campaign().canonicalManifest(), first == null ? null : first.bootId());
            if (reason == null) reason = "POST_STOP_HEALTH_PROOF";
        } else if (!Set.of("COMPLETED", "CANCELLED").contains(graph.campaign().status()) && "HEALTH_CHECKING".equals(job.status())) {
            var origin = progress.origin(job.jobId(), job.attemptNo()).orElse(null);
            String unsafe = origin == null ? "EXECUTION_ORIGIN_MISSING" : proofMismatch(job, graph, input.bootId(),
                    input.evidence().target(), origins.decode(origin), false);
            if (unsafe == null && (!OtaDeviceReportIngestionService.matchesModel(device, origins.decode(origin))
                    || !floors.accepts(identity.tenantId(), identity.projectId(), identity.deviceId(), origins.decode(origin)))) {
                unsafe = "COMMIT_SOURCE_NO_LONGER_CURRENT";
            }
            if (unsafe == null && !directions.deviceAllowed(job.deviceId())) unsafe = "DEVICE_ATOMIC_DIRECTION_CONFLICT";
            if (unsafe == null && (job.deadlineAt() == null || brokerAt.isAfter(job.deadlineAt()))) unsafe = "HEALTH_AFTER_DEADLINE";
            if (unsafe == null && input.healthSeq() != (latest == null ? 1 : latest.healthSeq() + 1)) unsafe = "HEALTH_SEQUENCE_GAP";
            if (unsafe == null && latest != null) {
                var prior = healthCodec.decode(latest.canonical()).value();
                long gap = Duration.between(latest.brokerReceivedAt(), brokerAt).toMillis();
                long uptimeDelta = input.evidence().uptimeMillis() - prior.evidence().uptimeMillis();
                long healthyDelta = input.evidence().healthyForMillis() - prior.evidence().healthyForMillis();
                if (gap < 0 || gap > 30_000 || uptimeDelta < 0 || healthyDelta != uptimeDelta) unsafe = "HEALTH_CONTINUITY_LOST";
            }
            if (unsafe != null) { next = "RECOVERY_REQUIRED"; reason = unsafe; }
            else if (latest != null && "RUNNING".equals(graph.campaign().status())) {
                var first = confirmations.firstHealth(job.jobId(), job.attemptNo()).orElseThrow();
                var firstInput = healthCodec.decode(first.canonical()).value();
                var policy = new OtaCampaignPlanCodec().decode(graph.campaign().canonicalPlan()).value().executionPolicy();
                long windowMillis = policy.healthWindowSeconds() * 1000L;
                if (Duration.between(first.brokerReceivedAt(), brokerAt).toMillis() >= windowMillis
                        && input.evidence().healthyForMillis() - firstInput.evidence().healthyForMillis() >= windowMillis) {
                    try {
                        var release = qualification.validateCurrent(identity.tenantId(), identity.projectId(), graph.campaign().firmwareId());
                        if (!release.release().id().equals(graph.campaign().releaseId())) throw invalid();
                        now = runtime.currentTime();
                        Instant deadline = now.plusSeconds(policy.stageTimeoutSeconds().confirming());
                        UUID id = Uuid7.generate(); UUID healthId = Uuid7.generate();
                        var proof = input.evidence().target();
                        byte[] canonical = permitCodec.encode(new OtaCommitPermitCodec.Permit("tc-ota-commit-permit/v1", id,
                                job.jobId(), job.attemptNo(), job.authorizationId(), job.manifestSha256(), input.bootId(),
                                proof.securityVersion(), proof.artifactSha256(), proof.targetSlot(), deadline.getEpochSecond()));
                        permit = new OtaCommitPermit(id, identity.tenantId(), identity.projectId(), job.campaignId(), job.jobId(),
                                identity.deviceId(), job.attemptNo(), identity.credentialVersion(), job.authorizationId(),
                                job.manifestSha256(), input.bootId(), healthId, canonical, OtaTrustBundleCodec.sha256(canonical), now, deadline);
                        next = "CONFIRMING"; reason = "HEALTH_WINDOW_CONFIRMED";
                    } catch (BusinessException unavailable) { next = "RECOVERY_REQUIRED"; reason = "COMMIT_RELEASE_UNAVAILABLE"; }
                }
            }
        }
        if (!identities.credentialValid(identity)) throw invalid();
        var receipt = new OtaHealthReceipt(permit == null ? Uuid7.generate() : permit.healthReceiptId(), identity.tenantId(),
                identity.projectId(), job.campaignId(), job.jobId(), identity.deviceId(), job.attemptNo(), identity.credentialVersion(),
                input.healthSeq(), input.authorizationId(), input.manifestSha256(), input.bootId(), decoded.canonical(), decoded.sha256(),
                brokerAt.truncatedTo(ChronoUnit.MICROS), permit == null ? runtime.currentTime() : permit.createdAt());
        if (!confirmations.acceptHealth(job, receipt, next, reason, permit)) throw invalid();
        record(identity, job.jobId(), "ota.job.health", reason);
        return "CONFIRMING".equals(next) ? Outcome.CONFIRMING : "RECOVERY_REQUIRED".equals(next) ? Outcome.RECOVERY_REQUIRED : Outcome.OBSERVED;
    }

    /** 精确设备提交先保留下限事实；模型冲突不能抹去已发生的不可逆计数变化。 */
    @Transactional(timeout = 5)
    public Outcome acceptCommit(AuthenticatedDeviceIdentity identity, byte[] payload, Instant brokerAt) {
        if (identity == null || brokerAt == null) throw invalid();
        var decoded = commitCodec.decode(payload); var input = decoded.value();
        var locked = lock(identity, input.jobId()); var job = locked.job(); var graph = locked.graph();
        requireAxes(identity, job, input.attemptNo(), input.authorizationId(), input.manifestSha256());
        var replay = confirmations.findCommitReceipt(identity.deviceId(), input.receiptId()).orElse(null);
        if (replay != null) {
            identities.lockCurrent(identity).orElseThrow(OtaConfirmationIngestionService::invalid);
            requireReplay(identity, replay.payloadHash(), replay.canonical(), decoded.sha256(), decoded.canonical());
            return Outcome.REPLAY;
        }
        if (!ACTIVE.contains(graph.campaign().status()) || !Set.of("CONFIRMING", "RECOVERY_REQUIRED", "SUCCEEDED", "ROLLBACK_PENDING", "ROLLING_BACK", "ROLLED_BACK", "CANCELLED").contains(job.status())) throw invalid();
        var permit = confirmations.findPermit(job.jobId(), job.attemptNo()).orElseThrow(OtaConfirmationIngestionService::invalid);
        if (!permit.id().equals(input.permitId()) || !confirmations.hasSendReservation(permit.id())) throw invalid();
        var origin = progress.origin(job.jobId(), job.attemptNo()).orElseThrow(OtaConfirmationIngestionService::invalid);
        var source = origins.decode(origin);
        String reason = proofMismatch(job, graph, input.bootId(), input.evidence(), source, true);
        String next = null; UUID deviceReceipt = null;
        if (reason == null) {
            var firmware = firmwares.find(identity.projectId(), graph.campaign().firmwareId(), false).orElseThrow();
            var evidence = input.evidence();
            Instant decisionAt = runtime.currentTime();
            boolean deviceAllowed = directions.deviceAllowed(job.deviceId());
            boolean stopped = stops.onlyObserve(job.jobId(), job.attemptNo());
            boolean adoptModel = !stopped && deviceAllowed && !Set.of("COMPLETED", "CANCELLED").contains(graph.campaign().status())
                    && "CONFIRMING".equals(job.status())
                    && OtaDeviceReportIngestionService.fresh(brokerAt, decisionAt)
                    && !brokerAt.isBefore(permit.createdAt())
                    && !brokerAt.isAfter(Instant.ofEpochSecond(permit.deadlineAt().getEpochSecond()));
            var result = commits.apply(new OtaDeviceCommitPort.Command(identity, firmware.deviceTypeId(),
                    new OtaDeviceCommitPort.ModelIdentity(source.thingModelVersionId(), source.thingModelSchemaDigestAlgorithm(),
                            source.thingModelSchemaDigest(), source.propertyProfile()),
                    new OtaDeviceCommitPort.ModelIdentity(evidence.thingModelVersionId(), evidence.thingModelSchemaDigestAlgorithm(),
                            evidence.thingModelSchemaDigest(), evidence.propertyProfile()), permit.id(), source.committedSecurityVersion(),
                    evidence.committedSecurityVersion(), evidence.artifactSha256(), adoptModel));
            if (result.decision() == OtaDeviceCommitPort.Decision.IDENTITY_REJECTED) throw invalid();
            deviceReceipt = result.receiptId();
            if (result.decision() == OtaDeviceCommitPort.Decision.SECURITY_CONFLICT) reason = "COMMITTED_SECURITY_CONFLICT";
            else if (result.decision() == OtaDeviceCommitPort.Decision.SOURCE_CONFLICT) reason = "COMMITTED_MODEL_SOURCE_CONFLICT";
            else if (!"CONFIRMING".equals(job.status())) reason = "COMMITTED_AFTER_RECOVERY";
            else if (!adoptModel) {
                reason = deviceAllowed ? "COMMITTED_OUTSIDE_PERMIT_WINDOW" : "DEVICE_ATOMIC_DIRECTION_CONFLICT";
            } else if (result.decision() == OtaDeviceCommitPort.Decision.CHANGED
                    || result.decision() == OtaDeviceCommitPort.Decision.SAME_MODEL) {
                next = "SUCCEEDED"; reason = "DEVICE_COMMITTED_AND_BOUND";
            } else { reason = "COMMITTED_WITHOUT_MODEL_ADOPTION"; }
        } else {
            identities.lockCurrent(identity).orElseThrow(OtaConfirmationIngestionService::invalid);
        }
        if (next == null && !stops.onlyObserve(job.jobId(), job.attemptNo())
                && !Set.of("COMPLETED", "CANCELLED").contains(graph.campaign().status())
                && !Set.of("RECOVERY_REQUIRED", "SUCCEEDED", "ROLLBACK_PENDING", "ROLLING_BACK", "ROLLED_BACK", "CANCELLED").contains(job.status())) next = "RECOVERY_REQUIRED";
        if (!identities.credentialValid(identity)) throw invalid();
        var receipt = new OtaCommitReceipt(Uuid7.generate(), identity.tenantId(), identity.projectId(), job.campaignId(), job.jobId(),
                identity.deviceId(), job.attemptNo(), identity.credentialVersion(), input.receiptId(), input.permitId(),
                input.manifestSha256(), input.bootId(), input.evidence().committedSecurityVersion(), decoded.canonical(), decoded.sha256(),
                brokerAt.truncatedTo(ChronoUnit.MICROS), runtime.currentTime());
        if (!confirmations.acceptCommit(job, receipt, next, reason, deviceReceipt)) throw invalid();
        record(identity, job.jobId(), "ota.job.commit", reason);
        return "SUCCEEDED".equals(next) ? Outcome.SUCCEEDED : "RECOVERY_REQUIRED".equals(next) ? Outcome.RECOVERY_REQUIRED : Outcome.OBSERVED;
    }

    /** 固定候选启动身份及目标证据；设备提交值在独立协议中才允许提高。 */
    private String proofMismatch(OtaJobProgressRepository.Context job, OtaCampaignRuntime graph, UUID boot,
            OtaJobProgressCodec.Evidence proof, OtaDeviceReportCodec.Report source, boolean committed) {
        if (!confirmations.candidateBoot(job.jobId(), job.attemptNo()).filter(boot::equals).isPresent()) return "CANDIDATE_BOOT_CHANGED";
        if (committed && proof.committedSecurityVersion() != proof.securityVersion()) return "COMMITTED_TARGET_MISMATCH";
        var first = progress.find(job.jobId(), job.attemptNo(), 1).orElse(null);
        var normalized = committed ? new OtaJobProgressCodec.Evidence(proof.artifactSha256(), proof.artifactSize(),
                proof.securityVersion(), source.committedSecurityVersion(), proof.thingModelVersionId(), proof.thingModelSchemaDigestAlgorithm(),
                proof.thingModelSchemaDigest(), proof.propertyProfile(), proof.trustDomain(), proof.rootFingerprint(),
                proof.trustBundleVersion(), proof.trustBundleSha256(), proof.sourceSlot(), proof.targetSlot(), proof.activeSlot(),
                proof.verification(), proof.bootVerified(), proof.selfTestPassed(), proof.watchdogHealthy()) : proof;
        return OtaJobProgressEvidenceValidator.mismatch(new OtaJobProgressCodec.Progress("tc-ota-job-progress/v1", job.jobId(),
                job.attemptNo(), 1, job.authorizationId(), job.manifestSha256(), "HEALTH_CHECKING", boot, normalized), source,
                graph.campaign().canonicalManifest(), first == null ? null : first.bootId());
    }

    /** 在真实范围内按项目控制和整图顺序加锁；不在提交路径提前升级设备共享锁。 */
    private Locked lock(AuthenticatedDeviceIdentity identity, UUID jobId) {
        if (TenantContext.current().isPresent()) throw new IllegalStateException("OTA确认不能继承管理账号");
        rls.establish(identity.tenantId(), identity.projectId());
        if (!lifecycle.lockActiveForWrite(identity.tenantId(), identity.projectId())) throw invalid();
        runtime.controlLock(identity.tenantId(), identity.projectId());
        var located = progress.locate(jobId).orElseThrow(OtaConfirmationIngestionService::invalid);
        if (!located.deviceId().equals(identity.deviceId())) throw invalid();
        var graph = runtime.lockRuntime(identity.projectId(), located.campaignId()).orElseThrow(OtaConfirmationIngestionService::invalid);
        return new Locked(progress.locate(jobId).orElseThrow(OtaConfirmationIngestionService::invalid), graph);
    }
    /** 原认证代际、尝试和已签下载身份不被重连消息替换。 */
    private static void requireAxes(AuthenticatedDeviceIdentity identity, OtaJobProgressRepository.Context job,
            int attempt, UUID authorization, String manifest) {
        if (job.credentialVersion() != identity.credentialVersion() || job.attemptNo() != attempt
                || !authorization.equals(job.authorizationId()) || !manifest.equals(job.manifestSha256())) throw invalid();
    }
    /** 同身份同字节只读确认，旧Broker时间不刷新首次接纳。 */
    private void requireReplay(AuthenticatedDeviceIdentity identity, String oldHash, byte[] oldCanonical, String hash, byte[] canonical) {
        if (!oldHash.equals(hash) || !Arrays.equals(oldCanonical, canonical) || !identities.credentialValid(identity)) throw invalid();
    }
    /** 系统审计只记录稳定原因，不暴露载荷或秘密。 */
    private void record(AuthenticatedDeviceIdentity identity, UUID jobId, String action, String reason) {
        audit.record(new AuditLogEntry(identity.tenantId(), identity.projectId(), null, "ota_device_job", jobId,
                action, Map.of("actorKind", "SYSTEM", "reason", reason)));
    }
    /** 永久拒绝不泄露设备安全元组。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("OTA健康或提交不满足认证合同"); }
    /** 同一事务保护的作业和完整活动快照。 */
    private record Locked(OtaJobProgressRepository.Context job, OtaCampaignRuntime graph) { }
    /** 本机采用结果不是硬件执行证明。 */
    public enum Outcome {
        /** 原证据只读回放。 */ REPLAY,
        /** 仅保存不可变观察。 */ OBSERVED,
        /** 健康窗口与固定许可已持久。 */ CONFIRMING,
        /** 设备提交、模型和平台成功图已原子一致。 */ SUCCEEDED,
        /** 已知安全责任留待专门对账。 */ RECOVERY_REQUIRED
    }
}
