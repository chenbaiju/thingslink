package com.things.link.ota.application;

import com.things.link.device.application.OtaDeviceCommitPort;
import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaJobProgressRepository;
import com.things.link.ota.domain.OtaRollbackOperation;
import com.things.link.ota.domain.OtaRollbackPreflightErrorCode;
import com.things.link.ota.domain.OtaRollbackPreflightRepository;
import com.things.link.ota.domain.OtaRollbackReport;
import com.things.link.ota.domain.OtaRollbackRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.error.BusinessException;
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
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 固定原子回退报告和只读未知对账；设备声明不能绕原来源、耐久方向或平台下限。 */
@Service
@DataPlaneDatabase
public class OtaRollbackIngestionService {
    /** 保留原责任或已终态的活动允许不可变观察。 */
    private static final Set<String> OBSERVABLE=Set.of("RUNNING","PAUSED","CANCELLING","CANCELLED","COMPLETED");
    /** 新方向与报告事实。 */ private final OtaRollbackRepository operations;
    /** 原认证预检。 */ private final OtaRollbackPreflightRepository preflights;
    /** 项目控制与时钟。 */ private final OtaCampaignRuntimeRepository runtime;
    /** 原作业及执行来源。 */ private final OtaJobProgressRepository progress;
    /** 原规范来源恢复。 */ private final OtaExecutionOriginGuard origins;
    /** 当前身份和模型。 */ private final OtaDeviceIdentityPort identities;
    /** 只读已知设备下限。 */ private final OtaDeviceCommitPort floors;
    /** 当前受控完整字节。 */ private final OtaRollbackBaselineQualification baselines;
    /** ACTIVE项目物理许可。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 数据面实际连接范围。 */ private final TransactionLocalRlsScope rls;
    /** 同事务审计。 */ private final AuditLogService audit;

    /** 全部读取共享外层事务，安全拒绝仅产生观察，数据库异常保留原首因。 */
    public OtaRollbackIngestionService(OtaRollbackRepository operations,OtaRollbackPreflightRepository preflights,
            OtaCampaignRuntimeRepository runtime,OtaJobProgressRepository progress,OtaExecutionOriginGuard origins,
            OtaDeviceIdentityPort identities,OtaDeviceCommitPort floors,OtaRollbackBaselineQualification baselines,
            ProjectLifecycleAccessService lifecycle,TransactionLocalRlsScope rls,AuditLogService audit) {
        this.operations=operations;this.preflights=preflights;this.runtime=runtime;this.progress=progress;this.origins=origins;
        this.identities=identities;this.floors=floors;this.baselines=baselines;this.lifecycle=lifecycle;this.rls=rls;this.audit=audit;
    }

    /** 主动报告保留原协议字节；迟到不重开过期阶段。 */
    @Transactional(timeout=5)
    public Outcome acceptOperation(AuthenticatedDeviceIdentity identity,byte[] payload,Instant brokerAt) {
        var decoded=new OtaRollbackOperationReportCodec().decode(payload);
        return accept(identity,decoded.value(),decoded.canonical(),decoded.sha256(),null,null,brokerAt);
    }

    /** 查询报告独立nonce并保留自身协议字节，不能重写主动报告历史。 */
    @Transactional(timeout=5)
    public Outcome acceptStatus(AuthenticatedDeviceIdentity identity,byte[] payload,Instant brokerAt) {
        var decoded=new OtaRollbackStatusReportCodec().decode(payload);var value=decoded.value();
        var report=new OtaRollbackOperationReportCodec.Report("tc-ota-rollback-operation-report/v1",value.operationId(),value.jobId(),
                value.attemptNo(),value.authorizationId(),value.manifestSha256(),value.operationSha256(),value.reportId(),
                value.reportSeq(),value.bootId(),value.status(),value.evidence());
        return accept(identity,report,decoded.canonical(),decoded.sha256(),value.queryId(),value.queryNonce(),brokerAt);
    }

    /** 先认证及只读重放，再判断时序、完整证明和不可变方向。 */
    private Outcome accept(AuthenticatedDeviceIdentity identity,OtaRollbackOperationReportCodec.Report report,
            byte[] canonical,String hash,UUID queryId,UUID queryNonce,Instant brokerAt) {
        if(identity==null||brokerAt==null) throw invalid();
        if(TenantContext.current().isPresent()) throw new IllegalStateException("回退报告不能继承管理身份");
        rls.establish(identity.tenantId(),identity.projectId());
        if(!lifecycle.lockActiveForWrite(identity.tenantId(),identity.projectId())) throw invalid();
        runtime.controlLock(identity.tenantId(),identity.projectId());
        var located=progress.locate(report.jobId()).orElseThrow(OtaRollbackIngestionService::invalid);
        if(!located.deviceId().equals(identity.deviceId())) throw invalid();
        var graph=runtime.lockRuntime(identity.projectId(),located.campaignId()).orElseThrow(OtaRollbackIngestionService::invalid);
        var job=progress.locate(report.jobId()).orElseThrow(OtaRollbackIngestionService::invalid);
        var operation=operations.find(report.operationId()).orElseThrow(OtaRollbackIngestionService::invalid);
        requireAxes(identity,job,operation,report);
        var device=identities.lockCurrent(identity).orElseThrow(OtaRollbackIngestionService::invalid);
        var prior=operations.findReport(identity.deviceId(),report.reportId()).orElse(null);
        if(prior!=null) {
            if(!prior.receipt().payloadHash().equals(hash)||!Arrays.equals(prior.receipt().canonical(),canonical)
                    ||!identities.credentialValid(identity)) throw invalid();
            return Outcome.REPLAY;
        }
        if(operations.findReportBySequence(operation.id(),report.reportSeq()).isPresent()) throw invalid();
        if(!OBSERVABLE.contains(graph.campaign().status())||!operations.hasOperationSendReservation(operation.id())) throw invalid();
        var decoded=new OtaRollbackOperationCodec().decode(operation.canonical());
        if(!decoded.sha256().equals(operation.payloadHash())||!Arrays.equals(decoded.canonical(),operation.canonical()))
            throw new IllegalStateException("回退固定操作规范事实损坏");
        var command=decoded.value();
        Instant now=runtime.currentTime();
        boolean fresh=OtaDeviceReportIngestionService.fresh(brokerAt,now)&&!brokerAt.isBefore(operation.createdAt());
        boolean window;
        if(queryId!=null) {
            var query=operations.findStatusQuery(queryId).orElseThrow(OtaRollbackIngestionService::invalid);
            var original=new OtaRollbackStatusQueryCodec().decode(query.canonical());
            if(!query.operationId().equals(operation.id())||!original.value().queryNonce().equals(queryNonce)
                    ||!original.sha256().equals(query.payloadHash())||!Arrays.equals(original.canonical(),query.canonical())) throw invalid();
            if(operations.findReportForStatusQuery(queryId).isPresent()) throw invalid();
            window=fresh&&operations.statusAdoptionAllowed(queryId,brokerAt);
        } else {
            window=fresh&&Set.of("ROLLBACK_PENDING","ROLLING_BACK").contains(job.status())&&job.deadlineAt()!=null
                    &&!brokerAt.isAfter(Instant.ofEpochSecond(job.deadlineAt().getEpochSecond()));
        }
        // 完成仅接受方向观察和矛盾隔离，不再执行当前操作的状态采用。
        window = window && !"COMPLETED".equals(graph.campaign().status());
        boolean ordered=report.reportSeq()>operations.latestSequence(operation.id());
        var control=operations.control(operation.id()).orElseThrow();
        var preflight=preflights.findQuery(operation.preflightQueryId()).orElseThrow();
        var originalReceipt=preflights.findReportForQuery(preflight.id()).orElseThrow().receipt();
        if(!originalReceipt.id().equals(operation.preflightReceiptId())||!originalReceipt.payloadHash().equals(command.reportSha256()))
            throw new IllegalStateException("回退原预检引用不一致");
        var originalReport=new OtaRollbackPreflightReportCodec().decode(originalReceipt.canonical()).value();
        var evidence=report.evidence();
        boolean accepted=OtaRollbackExecutionEvaluator.acceptedJournal(command,operation.payloadHash(),evidence);
        String proof=OtaRollbackExecutionEvaluator.proofMismatch(command,operation.payloadHash(),report.bootId(),report.status(),
                evidence,originalReport,graph.campaign().canonicalManifest());
        boolean commitWon="COMMIT_WON".equals(report.status())&&proof==null;
        String disposition="OBSERVED";String reason=accepted?"ATOMIC_ACCEPTANCE_OBSERVED":"UNQUALIFIED_OBSERVATION";
        if(control.conflictedAt()!=null||accepted&&control.commitWonReportId()!=null||commitWon&&control.acceptedReportId()!=null) {
            disposition="CONFLICT";reason="ATOMIC_DIRECTION_CONTRADICTION";
        } else if(window&&ordered&&proof==null) {
            String unsafe=currentSafety(identity,job,operation,evidence,preflight.baselineCanonical(),preflight.typeBaselineCanonical(),commitWon);
            if(unsafe!=null) reason=unsafe;
            else if(commitWon) { disposition="COMMIT_WON";reason="ORIGINAL_COMMIT_WON"; }
            else if("ROLLED_BACK".equals(report.status())&&Set.of("ROLLBACK_PENDING","ROLLING_BACK","RECOVERY_REQUIRED").contains(job.status())) {
                disposition="ROLLED_BACK";reason="ORIGINAL_SLOT_HEALTHY_AND_FENCED";
            } else if(Set.of("ACCEPTED","ROLLING_BACK").contains(report.status())
                    &&Set.of("ROLLBACK_PENDING","ROLLING_BACK").contains(job.status())) {
                disposition="ROLLING_BACK";reason="ATOMIC_ROLLBACK_ACCEPTED";
            }
        } else if(!window) reason=accepted?"ATOMIC_ACCEPTANCE_OBSERVED":"OUTSIDE_CURRENT_WINDOW";
        else if(!ordered) reason=accepted?"ATOMIC_ACCEPTANCE_OBSERVED":"REPORT_SEQUENCE_NOT_CURRENT";
        else reason=proof;
        if(!identities.credentialValid(identity)) throw invalid();
        var receipt=new OtaRollbackReport(Uuid7.generate(),identity.tenantId(),identity.projectId(),job.campaignId(),operation.id(),job.jobId(),
                identity.deviceId(),job.attemptNo(),identity.credentialVersion(),queryId,report.reportId(),report.reportSeq(),report.bootId(),
                report.status(),evidence.committedSecurityVersion(),canonical,hash,brokerAt.truncatedTo(ChronoUnit.MICROS),runtime.currentTime());
        if(!operations.acceptReport(job,receipt,disposition,reason)) throw new IllegalStateException("回退最终围栏变化，原事务重试");
        audit.record(new AuditLogEntry(identity.tenantId(),identity.projectId(),null,"ota_device_job",job.jobId(),"ota.rollback.result",
                Map.of("actorKind","SYSTEM","operationId",operation.id().toString(),"reason",reason)));
        return Outcome.valueOf(disposition);
    }

    /** 独立当前配置、原模型、设备下限和所有认证历史计数；不写设备模型或下限。 */
    private String currentSafety(AuthenticatedDeviceIdentity identity,OtaJobProgressRepository.Context job,
            OtaRollbackOperation operation,OtaRollbackOperationReportCodec.Evidence evidence,byte[] extension,byte[] parent,boolean commitWon) {
        if(operations.deviceConflicted(identity.deviceId())) return "DEVICE_SAFETY_CONFLICT";
        var device=identities.lockCurrent(identity).orElseThrow(OtaRollbackIngestionService::invalid);
        var source=origins.decode(progress.origin(job.jobId(),job.attemptNo()).orElseThrow());
        if(!OtaDeviceReportIngestionService.matchesModel(device,source)) return "CURRENT_MODEL_CHANGED";
        OtaRollbackBaselineQualification.Qualified current;
        try { current=baselines.requireCurrent(identity.tenantId(),identity.projectId(),device.deviceTypeId()); }
        catch(BusinessException failure) {
            if(failure.errorCode()!=OtaRollbackPreflightErrorCode.UNAVAILABLE) throw failure;
            return "CONTROLLED_BASELINE_UNAVAILABLE";
        }
        if(!Arrays.equals(current.extension().canonical(),extension)||!Arrays.equals(current.parent().canonical(),parent))
            return "CONTROLLED_BASELINE_CHANGED";
        long counter=evidence.committedSecurityVersion();
        var observed=operations.maximumObservedCommitted(operation.id());
        var preflightMax=preflights.maxObservedCommitted(job.jobId(),job.attemptNo());
        if(observed.isPresent()&&observed.getAsLong()>counter||preflightMax.isPresent()&&preflightMax.getAsLong()>counter)
            return "OBSERVED_SECURITY_COUNTER_CONFLICT";
        var floor=floors.currentFloor(identity.tenantId(),identity.projectId(),identity.deviceId());
        if(floor.isPresent()) {
            var known=floor.orElseThrow();
            String protectedSlot=commitWon?evidence.activeSlot():source.activeSlot();
            var active=evidence.slots().stream().filter(s->s.slot().equals(protectedSlot)).findFirst().orElseThrow();
            if(known.committedSecurityVersion()>counter||known.committedSecurityVersion()==active.securityVersion()
                    &&!known.artifactSha256().equals(active.artifactSha256())) return "KNOWN_SECURITY_FLOOR_CONFLICT";
        }
        return null;
    }

    /** 四轴认证及原尝试、授权、清单和固定操作绑定不能被重连替换。 */
    private static void requireAxes(AuthenticatedDeviceIdentity identity,OtaJobProgressRepository.Context job,
            OtaRollbackOperation operation,OtaRollbackOperationReportCodec.Report report) {
        if(!operation.tenantId().equals(identity.tenantId())||!operation.projectId().equals(identity.projectId())
                ||!operation.deviceId().equals(identity.deviceId())||operation.credentialVersion()!=identity.credentialVersion()
                ||job.credentialVersion()!=identity.credentialVersion()||!operation.jobId().equals(job.jobId())
                ||operation.attemptNo()!=report.attemptNo()||job.attemptNo()!=report.attemptNo()
                ||!operation.authorizationId().equals(report.authorizationId())||!operation.authorizationId().equals(job.authorizationId())
                ||!operation.manifestSha256().equals(report.manifestSha256())||!operation.manifestSha256().equals(job.manifestSha256())
                ||!operation.payloadHash().equals(report.operationSha256())) throw invalid();
    }
    /** 永久协议错误不携带原设备载荷。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("回退报告不满足原认证操作合同"); }
    /** 仅表示平台事务已接纳事实，不冒充硬件独立验收。 */
    public enum Outcome {
        /** 同字节报告只读。 */ REPLAY,
        /** 非采用观察。 */ OBSERVED,
        /** 已原子接纳。 */ ROLLING_BACK,
        /** 原槽健康封闭。 */ ROLLED_BACK,
        /** 只开放独立提交对账。 */ COMMIT_WON,
        /** 不可清除的方向冲突。 */ CONFLICT
    }
}
