package com.things.link.ota.application;

import com.things.link.device.application.OtaDeviceCommitPort;
import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.device.application.OtaDeviceReconciliationPort;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaConfirmationRepository;
import com.things.link.ota.domain.OtaFirmwareRepository;
import com.things.link.ota.domain.OtaJobProgressRepository;
import com.things.link.ota.domain.OtaReconciliationQuery;
import com.things.link.ota.domain.OtaReconciliationReceipt;
import com.things.link.ota.domain.OtaReconciliationRepository;
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

/** 新认证查询证明才能采用恢复结果；原许可观察和已知安全下限永不回写。 */
@Service
@DataPlaneDatabase
public class OtaCommitReconciliationIngestionService {
    /** 耐久停止只允许原提交的保护性观察。 */ private final OtaInstallStopObservationGuard stops;
    /** 仅保留设备责任的活动接纳新的安全事实。 */
    private static final Set<String> ACTIVE=Set.of("RUNNING","PAUSED","CANCELLING","COMPLETED","CANCELLED");
    /** 查询和报告事务图。 */ private final OtaReconciliationRepository queries;
    /** 完整运行图及数据库时间。 */ private final OtaCampaignRuntimeRepository runtime;
    /** 原不可变来源和认证进度。 */ private final OtaJobProgressRepository progress;
    /** 原许可的真实发送事实。 */ private final OtaConfirmationRepository confirmations;
    /** 来源规范报告恢复。 */ private final OtaExecutionOriginGuard origins;
    /** 固件所属类型和冻结清单。 */ private final OtaFirmwareRepository firmwares;
    /** 独立设备域模型采用。 */ private final OtaDeviceReconciliationPort devices;
    /** 当前身份与最终凭据复验。 */ private final OtaDeviceIdentityPort identities;
    /** ACTIVE项目真实共享锁。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 实际连接范围。 */ private final TransactionLocalRlsScope rls;
    /** 原子高危系统审计。 */ private final AuditLogService audit;
    /** 未知回退不得借旧查询采用模型。 */ private final OtaRollbackDirectionGuard directions;
    /** 独立确定提交报告。 */ private final OtaCommitReconciliationReportCodec codec=new OtaCommitReconciliationReportCodec();

    /** 所有持久依赖必须加入调用方同一短事务。 */
    public OtaCommitReconciliationIngestionService(OtaReconciliationRepository queries,OtaCampaignRuntimeRepository runtime,
            OtaJobProgressRepository progress,OtaConfirmationRepository confirmations,OtaExecutionOriginGuard origins,
            OtaFirmwareRepository firmwares,OtaDeviceReconciliationPort devices,OtaDeviceIdentityPort identities,
            ProjectLifecycleAccessService lifecycle,TransactionLocalRlsScope rls,AuditLogService audit,OtaRollbackDirectionGuard directions,OtaInstallStopObservationGuard stops) {
        this.queries=queries;this.runtime=runtime;this.progress=progress;this.confirmations=confirmations;this.origins=origins;
        this.firmwares=firmwares;this.devices=devices;this.identities=identities;this.lifecycle=lifecycle;this.rls=rls;this.audit=audit;this.directions=directions;this.stops=stops;
    }

    /** 真实认证消息对账，一次裁决贯穿设备采用与作业终态；最终围栏失败必须整笔回滚。 */
    @Transactional(timeout=5)
    public Outcome accept(AuthenticatedDeviceIdentity identity,byte[] payload,Instant brokerAt) {
        if(identity==null||brokerAt==null) throw invalid();
        if(TenantContext.current().isPresent()) throw new IllegalStateException("OTA对账不能继承管理账号");
        var decoded=codec.decode(payload);var input=decoded.value();
        rls.establish(identity.tenantId(),identity.projectId());
        if(!lifecycle.lockActiveForWrite(identity.tenantId(),identity.projectId())) throw invalid();
        runtime.controlLock(identity.tenantId(),identity.projectId());
        var located=progress.locate(input.jobId()).orElseThrow(OtaCommitReconciliationIngestionService::invalid);
        if(!located.deviceId().equals(identity.deviceId())) throw invalid();
        var graph=runtime.lockRuntime(identity.projectId(),located.campaignId()).orElseThrow(OtaCommitReconciliationIngestionService::invalid);
        var job=progress.locate(input.jobId()).orElseThrow(OtaCommitReconciliationIngestionService::invalid);
        var query=queries.findQuery(input.queryId()).orElseThrow(OtaCommitReconciliationIngestionService::invalid);
        requireAxes(identity,job,query,input);
        var replay=queries.findReport(identity.deviceId(),input.reportId()).orElse(null);
        if(replay!=null) {
            identities.lockCurrent(identity).orElseThrow(OtaCommitReconciliationIngestionService::invalid);
            if(!replay.payloadHash().equals(decoded.sha256())||!Arrays.equals(replay.canonical(),decoded.canonical())
                    ||!identities.credentialValid(identity)) throw invalid();
            return Outcome.REPLAY;
        }
        if(queries.findReportForQuery(query.id()).isPresent()) throw invalid();
        if(!ACTIVE.contains(graph.campaign().status())||!Set.of("RECOVERY_REQUIRED","ROLLBACK_PENDING","ROLLING_BACK","ROLLED_BACK","SUCCEEDED","CANCELLED").contains(job.status())
                ||!queries.hasSendReservation(query.id())||!confirmations.hasSendReservation(query.permitId())) throw invalid();
        var permit=confirmations.findPermit(job.jobId(),job.attemptNo()).orElseThrow(OtaCommitReconciliationIngestionService::invalid);
        if(!permit.id().equals(query.permitId())||!permit.bootId().equals(query.commitBootId())) throw invalid();
        var origin=progress.origin(job.jobId(),job.attemptNo()).orElseThrow(OtaCommitReconciliationIngestionService::invalid);
        var source=origins.decode(origin);var proof=input.evidence();
        var first=progress.find(job.jobId(),job.attemptNo(),1).orElse(null);
        var normalized=new OtaJobProgressCodec.Evidence(proof.artifactSha256(),proof.artifactSize(),proof.securityVersion(),
                source.committedSecurityVersion(),proof.thingModelVersionId(),proof.thingModelSchemaDigestAlgorithm(),
                proof.thingModelSchemaDigest(),proof.propertyProfile(),proof.trustDomain(),proof.rootFingerprint(),
                proof.trustBundleVersion(),proof.trustBundleSha256(),proof.sourceSlot(),proof.targetSlot(),proof.activeSlot(),
                proof.verification(),proof.bootVerified(),proof.selfTestPassed(),proof.watchdogHealthy());
        String reason=proof.committedSecurityVersion()!=proof.securityVersion()?"COMMITTED_TARGET_MISMATCH":
                OtaJobProgressEvidenceValidator.mismatch(new OtaJobProgressCodec.Progress("tc-ota-job-progress/v1",job.jobId(),
                        job.attemptNo(),1,job.authorizationId(),job.manifestSha256(),"HEALTH_CHECKING",input.bootId(),normalized),
                        source,graph.campaign().canonicalManifest(),first==null?null:first.bootId());
        boolean succeeded=false;java.util.UUID deviceReceipt=null;
        if(reason==null) {
            Instant decisionAt=runtime.currentTime();
            boolean adopt=!stops.onlyObserve(job.jobId(),job.attemptNo())&&"RECOVERY_REQUIRED".equals(job.status())
                    &&!Set.of("COMPLETED","CANCELLED").contains(graph.campaign().status())
                    &&OtaDeviceReportIngestionService.fresh(brokerAt,decisionAt)
                    &&directions.commitAllowed(job.jobId(),job.attemptNo(),job.deviceId())
                    &&queries.adoptionAllowed(job,query.id(),brokerAt);
            var firmware=firmwares.find(identity.projectId(),graph.campaign().firmwareId(),false).orElseThrow();
            var result=devices.apply(new OtaDeviceReconciliationPort.Command(identity,firmware.deviceTypeId(),
                    new OtaDeviceCommitPort.ModelIdentity(source.thingModelVersionId(),source.thingModelSchemaDigestAlgorithm(),
                            source.thingModelSchemaDigest(),source.propertyProfile()),
                    new OtaDeviceCommitPort.ModelIdentity(proof.thingModelVersionId(),proof.thingModelSchemaDigestAlgorithm(),
                            proof.thingModelSchemaDigest(),proof.propertyProfile()),query.permitId(),query.id(),source.committedSecurityVersion(),
                    proof.committedSecurityVersion(),proof.artifactSha256(),adopt));
            if(result.decision()==OtaDeviceReconciliationPort.Decision.IDENTITY_REJECTED) throw invalid();
            deviceReceipt=result.receiptId();
            succeeded=adopt&&(result.decision()==OtaDeviceReconciliationPort.Decision.CHANGED||result.decision()==OtaDeviceReconciliationPort.Decision.SAME_MODEL);
            reason=succeeded?"COMMITTED_TARGET_RECONCILED":result.decision()==OtaDeviceReconciliationPort.Decision.SOURCE_CONFLICT
                    ?"RECONCILIATION_MODEL_SOURCE_CONFLICT":result.decision()==OtaDeviceReconciliationPort.Decision.SECURITY_CONFLICT
                    ?"RECONCILIATION_SECURITY_CONFLICT":"COMMITTED_RECONCILIATION_OBSERVATION";
        } else identities.lockCurrent(identity).orElseThrow(OtaCommitReconciliationIngestionService::invalid);
        if(!identities.credentialValid(identity)) throw invalid();
        var receipt=new OtaReconciliationReceipt(Uuid7.generate(),identity.tenantId(),identity.projectId(),job.campaignId(),job.jobId(),
                identity.deviceId(),job.attemptNo(),identity.credentialVersion(),query.id(),input.reportId(),input.bootId(),
                decoded.canonical(),decoded.sha256(),brokerAt.truncatedTo(ChronoUnit.MICROS),runtime.currentTime());
        if(!queries.acceptReport(job,receipt,succeeded,reason,deviceReceipt)) throw new IllegalStateException("OTA对账最终围栏变化，整事务重试");
        audit.record(new AuditLogEntry(identity.tenantId(),identity.projectId(),null,"ota_device_job",job.jobId(),
                "ota.reconciliation.result",Map.of("actorKind","SYSTEM","queryId",query.id().toString(),"reason",reason)));
        return succeeded?Outcome.SUCCEEDED:Outcome.OBSERVED;
    }

    /** 固定查询、原身份和载荷身份全量相等，不允许旧尝试或nonce跨借。 */
    private static void requireAxes(AuthenticatedDeviceIdentity identity,OtaJobProgressRepository.Context job,
            OtaReconciliationQuery query,OtaCommitReconciliationReportCodec.Report input) {
        if(job.credentialVersion()!=identity.credentialVersion()||job.attemptNo()!=input.attemptNo()
                ||!job.manifestSha256().equals(input.manifestSha256())||!job.authorizationId().equals(input.authorizationId())
                ||!query.tenantId().equals(identity.tenantId())||!query.projectId().equals(identity.projectId())
                ||!query.deviceId().equals(identity.deviceId())||query.credentialVersion()!=identity.credentialVersion()
                ||!query.jobId().equals(job.jobId())||query.attemptNo()!=input.attemptNo()||query.recoveryRevision()!=input.recoveryRevision()
                ||!query.queryNonce().equals(input.queryNonce())||!query.authorizationId().equals(input.authorizationId())
                ||!query.manifestSha256().equals(input.manifestSha256())||!query.permitId().equals(input.permitId())
                ||!query.commitBootId().equals(input.commitBootId())) throw invalid();
    }
    /** 永久合同拒绝不泄露设备证明。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("OTA恢复对账不满足原认证查询合同"); }
    /** 软件事务采用结果，不声明物理硬件资格。 */
    public enum Outcome {
        /** 同字节原报告只读回放。 */ REPLAY,
        /** 保留已知事实，原恢复责任不解除。 */ OBSERVED,
        /** 新认证对账及设备模型采用已原子收束。 */ SUCCEEDED
    }
}
