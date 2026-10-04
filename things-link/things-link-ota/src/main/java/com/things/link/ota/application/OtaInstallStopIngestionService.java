package com.things.link.ota.application;

import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaInstallStopErrorCode;
import com.things.link.ota.domain.OtaInstallStopOperation;
import com.things.link.ota.domain.OtaInstallStopReport;
import com.things.link.ota.domain.OtaInstallStopRepository;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 安装前停止的认证证明，未知或安装先赢均不伪造设备取消。 */
@Service
@DataPlaneDatabase
public class OtaInstallStopIngestionService {
    /** 封闭取消之后仍需接纳原操作相反证据，不能重开活动。 */
    private static final Set<String> OBSERVABLE=Set.of("CANCELLING","CANCELLED");
    /** 真正允许安全取消的原责任。 */
    private static final Set<String> CANCELLABLE=Set.of("DISPATCHED","DOWNLOADING","VERIFYING","RECOVERY_REQUIRED","RETRY_WAIT");
    /** 操作、日志与最终CAS。 */ private final OtaInstallStopRepository operations;
    /** 原取消请求和完整图。 */ private final OtaCampaignRuntimeRepository runtime;
    /** 当前原尝试与封存授权。 */ private final OtaJobProgressRepository progress;
    /** 当前制造合同，不能借设备自报能力授权。 */ private final OtaInstallStopBaselineQualification baselines;
    /** 当前认证代际。 */ private final OtaDeviceIdentityPort identities;
    /** ACTIVE持续许可。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 真实物理连接范围。 */ private final TransactionLocalRlsScope rls;
    /** 所有观察同事务审计。 */ private final AuditLogService audit;
    /** 停止先赢后收束被围栏的重试等待作业，避免其永久停留。 */ private final OtaBusinessRetryService retries;

    /** 不注入模型采用或安全下限写入端口，停止协议没有这些权限。 */
    public OtaInstallStopIngestionService(OtaInstallStopRepository operations,OtaCampaignRuntimeRepository runtime,
            OtaJobProgressRepository progress,OtaInstallStopBaselineQualification baselines,OtaDeviceIdentityPort identities,
            ProjectLifecycleAccessService lifecycle,TransactionLocalRlsScope rls,AuditLogService audit,
            OtaBusinessRetryService retries) {
        this.operations=operations;this.runtime=runtime;this.progress=progress;this.baselines=baselines;
        this.identities=identities;this.lifecycle=lifecycle;this.rls=rls;this.audit=audit;this.retries=retries;
    }
    /** 直接停止回执使用原操作期限，迟到仅保留观察或相反证据。 */
    @Transactional(timeout=5)
    public Outcome acceptOperation(AuthenticatedDeviceIdentity identity,byte[] bytes,Instant brokerAt) {
        var decoded=new OtaInstallStopOperationReportCodec().decode(bytes);
        return accept(identity,decoded.value(),decoded.canonical(),decoded.sha256(),null,null,brokerAt);
    }
    /** 新查询可以证明原操作已耐久停止，不生成第二个停止操作或新attempt。 */
    @Transactional(timeout=5)
    public Outcome acceptStatus(AuthenticatedDeviceIdentity identity,byte[] bytes,Instant brokerAt) {
        var decoded=new OtaInstallStopStatusReportCodec().decode(bytes);var value=decoded.value();
        var report=new OtaInstallStopOperationReportCodec.Report("tc-ota-install-stop-operation-report/v1",value.operationId(),
                value.jobId(),value.attemptNo(),value.manifestSha256(),value.operationSha256(),value.reportId(),value.reportSeq(),
                value.bootId(),value.status(),value.evidence());
        return accept(identity,report,decoded.canonical(),decoded.sha256(),value.queryId(),value.queryNonce(),brokerAt);
    }
    /** 固定逻辑身份、原范围、真实凭据与原规范事实必须同时成立。 */
    private Outcome accept(AuthenticatedDeviceIdentity identity,OtaInstallStopOperationReportCodec.Report report,
            byte[] canonical,String hash,UUID queryId,UUID nonce,Instant brokerAt) {
        if(identity==null||brokerAt==null) throw invalid();
        if(TenantContext.current().isPresent()) throw new IllegalStateException("停止观察不能继承管理账号");
        rls.establish(identity.tenantId(),identity.projectId());
        if(!lifecycle.lockActiveForWrite(identity.tenantId(),identity.projectId())) throw invalid();
        runtime.controlLock(identity.tenantId(),identity.projectId());
        var located=progress.locate(report.jobId()).orElseThrow(OtaInstallStopIngestionService::invalid);
        if(!located.deviceId().equals(identity.deviceId())) throw invalid();
        var graph=runtime.lockRuntime(identity.projectId(),located.campaignId()).orElseThrow(OtaInstallStopIngestionService::invalid);
        var job=progress.locate(report.jobId()).orElseThrow(OtaInstallStopIngestionService::invalid);
        var operation=operations.find(report.operationId()).orElseThrow(OtaInstallStopIngestionService::invalid);
        requireAxes(identity,job,operation,report);
        var device=identities.lockCurrent(identity).orElseThrow(OtaInstallStopIngestionService::invalid);
        var prior=operations.findReport(identity.deviceId(),report.reportId()).orElse(null);
        if(prior!=null) {
            if(!prior.receipt().payloadHash().equals(hash)||!Arrays.equals(prior.receipt().canonical(),canonical)
                    ||!identities.credentialValid(identity)) throw invalid();
            return Outcome.REPLAY;
        }
        if(operations.findReportBySequence(operation.id(),report.reportSeq()).isPresent()
                ||!OBSERVABLE.contains(graph.campaign().status())||!operations.hasOperationSendReservation(operation.id())) throw invalid();
        OtaInstallStopDeliveryService.validateOperation(operation);
        var origin=progress.origin(job.jobId(),job.attemptNo()).orElseThrow(OtaInstallStopIngestionService::invalid);
        if(!origin.reportHash().equals(operation.originHash())) throw new IllegalStateException("停止原来源哈希损坏");
        var now=runtime.currentTime();
        boolean fresh=OtaDeviceReportIngestionService.fresh(brokerAt,now)&&!brokerAt.isBefore(operation.createdAt());
        boolean window;
        if(queryId!=null) {
            var query=operations.findStatusQuery(queryId).orElseThrow(OtaInstallStopIngestionService::invalid);
            var decoded=new OtaInstallStopStatusQueryCodec().decode(query.canonical());
            if(!query.operationId().equals(operation.id())||!query.tenantId().equals(operation.tenantId())
                    ||!query.projectId().equals(operation.projectId())||!query.campaignId().equals(operation.campaignId())
                    ||!query.jobId().equals(operation.jobId())||!query.deviceId().equals(operation.deviceId())
                    ||query.attemptNo()!=operation.attemptNo()||query.credentialVersion()!=operation.credentialVersion()
                    ||!decoded.value().queryId().equals(query.id())||!decoded.value().operationId().equals(operation.id())
                    ||!decoded.value().jobId().equals(operation.jobId())||decoded.value().attemptNo()!=operation.attemptNo()
                    ||!decoded.value().manifestSha256().equals(operation.manifestSha256())
                    ||!decoded.value().operationSha256().equals(operation.payloadHash())
                    ||decoded.value().expiresAt()!=query.deadlineAt().getEpochSecond()||!decoded.value().queryNonce().equals(nonce)
                    ||!decoded.sha256().equals(query.payloadHash())||!Arrays.equals(decoded.canonical(),query.canonical())
                    ||operations.findReportForStatusQuery(queryId).isPresent()) throw invalid();
            window=fresh&&operations.statusAdoptionAllowed(queryId,brokerAt);
        } else window=fresh&&!brokerAt.isAfter(Instant.ofEpochSecond(operation.deadlineAt().getEpochSecond()))
                &&now.isBefore(Instant.ofEpochSecond(operation.deadlineAt().getEpochSecond()));
        window=window&&"CANCELLING".equals(graph.campaign().status())&&CANCELLABLE.contains(job.status());
        var evidence=report.evidence();var control=operations.control(operation.id()).orElseThrow();
        boolean bound=OtaInstallStopEvaluator.boundEvidence(operation,evidence);
        boolean accepted=OtaInstallStopEvaluator.accepted(operation,evidence);
        boolean installed=OtaInstallStopEvaluator.installed(operation,evidence,job.authorizationId());
        boolean ordered=report.reportSeq()>operations.latestSequence(operation.id());
        String disposition="OBSERVED",reason=accepted?"DURABLE_STOP_ACCEPTED":"UNQUALIFIED_OBSERVATION";
        Outcome outcome=Outcome.OBSERVED;
        if(control.conflictedAt()!=null||accepted&&(control.installWonReportId()!=null||control.externalEvidenceId()!=null||!evidence.installOperations().isEmpty())
                ||installed&&control.acceptedReportId()!=null
                ||bound&&!evidence.installOperations().isEmpty()&&!installed) {
            disposition="CONFLICT";reason="INSTALL_STOP_DIRECTION_CONTRADICTION";outcome=Outcome.CONFLICT;
        } else if(window&&ordered&&bound) {
            boolean current=false;
            try {
                var configured=baselines.requireCurrent(identity.tenantId(),identity.projectId(),device.deviceTypeId());
                current=Arrays.equals(configured.parent().canonical(),operation.parentBaseline())
                        &&Arrays.equals(configured.extension().canonical(),operation.stopBaseline());
            } catch(BusinessException failure) {
                if(failure.errorCode()!=OtaInstallStopErrorCode.UNAVAILABLE) throw failure;
            }
            if(!current) reason="CONTROLLED_BASELINE_UNAVAILABLE";
            else if(operations.deviceConflicted(identity.deviceId())) reason="DEVICE_SAFETY_CONFLICT";
            else if(OtaInstallStopEvaluator.stopped(operation,report)) {
                disposition="CANCELLED";reason="ATTEMPT_DURABLY_STOPPED_BEFORE_INSTALL";outcome=Outcome.STOPPED;
            } else if(installed&&"INSTALL_WON".equals(report.status())) {
                disposition="RECOVERY_REQUIRED".equals(job.status())?"OBSERVED":"RECOVERY_REQUIRED";
                reason="INSTALL_ALREADY_ACCEPTED";outcome=Outcome.INSTALL_WON;
            }
        } else if(!window) reason=accepted?"DURABLE_STOP_ACCEPTED":"OUTSIDE_CURRENT_WINDOW";
        else if(!ordered) reason=accepted?"DURABLE_STOP_ACCEPTED":"REPORT_SEQUENCE_NOT_CURRENT";
        if(!identities.credentialValid(identity)) throw invalid();
        var receipt=new OtaInstallStopReport(Uuid7.generate(),identity.tenantId(),identity.projectId(),job.campaignId(),operation.id(),
                job.jobId(),identity.deviceId(),job.attemptNo(),identity.credentialVersion(),queryId,report.reportId(),report.reportSeq(),
                report.bootId(),report.status(),canonical,hash,brokerAt.truncatedTo(ChronoUnit.MICROS),now);
        if(!operations.acceptReport(job,receipt,disposition,reason)) throw new IllegalStateException("安装前停止最终围栏变化，整事务重试");
        audit.record(new AuditLogEntry(identity.tenantId(),identity.projectId(),null,"ota_device_job",job.jobId(),
                "ota.install_stop.result",Map.of("actorKind","SYSTEM","operationId",operation.id().toString(),"reason",reason)));
        // ADR0138：停止先赢时，被围栏的重试等待作业不会被到期领取，必须由停止流程显式收束为取消。
        if(outcome==Outcome.STOPPED) retries.reconcileFencedRetry(identity.tenantId(),identity.projectId(),job.jobId());
        return outcome;
    }
    /** 原scope、job、attempt与规范操作摘要必须完全一致，不相信正文自报设备。 */
    private static void requireAxes(AuthenticatedDeviceIdentity identity,OtaJobProgressRepository.Context job,
            OtaInstallStopOperation operation,OtaInstallStopOperationReportCodec.Report report) {
        if(!job.tenantId().equals(identity.tenantId())||!job.projectId().equals(identity.projectId())
                ||job.credentialVersion()!=identity.credentialVersion()||job.attemptNo()!=report.attemptNo()
                ||!operation.tenantId().equals(identity.tenantId())||!operation.projectId().equals(identity.projectId())
                ||!operation.deviceId().equals(identity.deviceId())||operation.credentialVersion()!=identity.credentialVersion()
                ||!operation.campaignId().equals(job.campaignId())||!operation.jobId().equals(job.jobId())
                ||operation.attemptNo()!=job.attemptNo()||!operation.manifestSha256().equals(job.manifestSha256())
                ||!operation.manifestSha256().equals(report.manifestSha256())||!operation.payloadHash().equals(report.operationSha256())) throw invalid();
    }
    /** 永久身份/协议拒绝，由raw入口归入既有死信分类。 */
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("安装前停止认证观察不合法");}
    /** 接纳结果不是设备整体健康或硬件放行结论。 */
    public enum Outcome {
        /** 精确原字节重放。 */ REPLAY,
        /** 只保存必要证据。 */ OBSERVED,
        /** 原尝试安全取消。 */ STOPPED,
        /** 安装已先获接纳，继续恢复责任。 */ INSTALL_WON,
        /** 耐久方向矛盾，设备隔离。 */ CONFLICT
    }
}
