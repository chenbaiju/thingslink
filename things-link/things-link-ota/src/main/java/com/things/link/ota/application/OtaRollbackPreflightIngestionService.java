package com.things.link.ota.application;

import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaJobProgressRepository;
import com.things.link.ota.domain.OtaRollbackPreflightQuery;
import com.things.link.ota.domain.OtaRollbackPreflightReceipt;
import com.things.link.ota.domain.OtaRollbackPreflightRepository;
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

/** 认证双槽报告仅形成只读准备事实，不能推进作业或修改设备下限。 */
@Service
@DataPlaneDatabase
public class OtaRollbackPreflightIngestionService {
    /** 仍有设备责任的活动允许安全观察。 */ private static final Set<String> ACTIVE=Set.of("RUNNING","PAUSED","CANCELLING");
    /** 完整查询与唯一报告事务图。 */ private final OtaRollbackPreflightRepository queries;
    /** 当前运行图及数据库时间。 */ private final OtaCampaignRuntimeRepository runtime;
    /** 不可变执行上下文。 */ private final OtaJobProgressRepository progress;
    /** 当前设备原代际与最终身份复验。 */ private final OtaDeviceIdentityPort identities;
    /** 当前真实资格判断，无写设备副作用。 */ private final OtaRollbackPreflightAssessmentService assessments;
    /** 真实ACTIVE项目共享锁。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 当前数据面连接范围。 */ private final TransactionLocalRlsScope rls;
    /** 与报告同事务审计。 */ private final AuditLogService audit;
    /** 严格新协议，不扩已有报告v1。 */ private final OtaRollbackPreflightReportCodec codec=new OtaRollbackPreflightReportCodec();
    /** 原规范查询身份恢复。 */ private final OtaRollbackPreflightQueryCodec queryCodec=new OtaRollbackPreflightQueryCodec();

    /** 所有状态动作必须参加同一外层短事务。 */
    public OtaRollbackPreflightIngestionService(OtaRollbackPreflightRepository queries, OtaCampaignRuntimeRepository runtime,
            OtaJobProgressRepository progress, OtaDeviceIdentityPort identities, OtaRollbackPreflightAssessmentService assessments,
            ProjectLifecycleAccessService lifecycle, TransactionLocalRlsScope rls, AuditLogService audit) {
        this.queries=queries; this.runtime=runtime; this.progress=progress; this.identities=identities;
        this.assessments=assessments; this.lifecycle=lifecycle; this.rls=rls; this.audit=audit;
    }

    /** 同报告只读重放；新报告错身份永久拒绝，真实最终CAS或审计异常整笔重试。 */
    @Transactional(timeout=5)
    public Outcome accept(AuthenticatedDeviceIdentity identity, byte[] payload, Instant brokerAt) {
        if(identity==null||brokerAt==null) throw invalid();
        if(TenantContext.current().isPresent()) throw new IllegalStateException("OTA预检不能继承管理账号");
        var decoded=codec.decode(payload); var report=decoded.value();
        rls.establish(identity.tenantId(),identity.projectId());
        if(!lifecycle.lockActiveForWrite(identity.tenantId(),identity.projectId())) throw invalid();
        runtime.controlLock(identity.tenantId(),identity.projectId());
        var located=progress.locate(report.jobId()).orElseThrow(OtaRollbackPreflightIngestionService::invalid);
        if(!located.deviceId().equals(identity.deviceId())) throw invalid();
        var graph=runtime.lockRuntime(identity.projectId(),located.campaignId()).orElseThrow(OtaRollbackPreflightIngestionService::invalid);
        var job=progress.locate(report.jobId()).orElseThrow(OtaRollbackPreflightIngestionService::invalid);
        var query=queries.findQuery(report.queryId()).orElseThrow(OtaRollbackPreflightIngestionService::invalid);
        requireAxes(identity,job,query,report);
        identities.lockCurrent(identity).orElseThrow(OtaRollbackPreflightIngestionService::invalid);
        var previous=queries.findReport(identity.deviceId(),report.reportId()).orElse(null);
        if(previous!=null) {
            if(!previous.receipt().payloadHash().equals(decoded.sha256())
                    ||!Arrays.equals(previous.receipt().canonical(),decoded.canonical())||!identities.credentialValid(identity)) throw invalid();
            return Outcome.REPLAY;
        }
        if(queries.findReportForQuery(query.id()).isPresent()) throw invalid();
        if(!ACTIVE.contains(graph.campaign().status())||!"RECOVERY_REQUIRED".equals(job.status())
                ||!queries.hasSendReservation(query.id())) throw invalid();
        var assessment=assessments.assess(job,query,report,brokerAt,queries.adoptionAllowed(job,query.id(),brokerAt));
        if(!identities.credentialValid(identity)) throw invalid();
        var receipt=new OtaRollbackPreflightReceipt(Uuid7.generate(),identity.tenantId(),identity.projectId(),job.campaignId(),job.jobId(),
                identity.deviceId(),job.attemptNo(),identity.credentialVersion(),query.id(),report.reportId(),report.bootId(),
                report.committedSecurityVersion(),decoded.canonical(),decoded.sha256(),brokerAt.truncatedTo(ChronoUnit.MICROS),runtime.currentTime());
        var decision=assessment.decision();
        if(!queries.acceptReport(job,receipt,decision.disposition(),decision.reason(),assessment.canonical()))
            throw new IllegalStateException("OTA预检最终围栏变化，原事务重试");
        audit.record(new AuditLogEntry(identity.tenantId(),identity.projectId(),null,"ota_device_job",job.jobId(),
                "ota.rollback.preflight.result",Map.of("actorKind","SYSTEM","queryId",query.id().toString(),"reason",decision.reason())));
        return "PREPARABLE".equals(decision.disposition())?Outcome.PREPARABLE:Outcome.OBSERVED;
    }

    /** 固定原查询完整身份，不能以当前凭据版本附着历史消息。 */
    private void requireAxes(AuthenticatedDeviceIdentity identity, OtaJobProgressRepository.Context job,
            OtaRollbackPreflightQuery query, OtaRollbackPreflightReportCodec.Report report) {
        var original=queryCodec.decode(query.canonical());
        if(!original.sha256().equals(query.payloadHash())||!Arrays.equals(original.canonical(),query.canonical()))
            throw new IllegalStateException("OTA预检查询规范事实不符");
        if(!query.tenantId().equals(identity.tenantId())||!query.projectId().equals(identity.projectId())
                ||!query.deviceId().equals(identity.deviceId())||query.credentialVersion()!=identity.credentialVersion()
                ||job.credentialVersion()!=identity.credentialVersion()||!query.jobId().equals(job.jobId())
                ||job.attemptNo()!=report.attemptNo()||query.attemptNo()!=report.attemptNo()
                ||!query.authorizationId().equals(report.authorizationId())||!query.authorizationId().equals(job.authorizationId())
                ||!query.manifestSha256().equals(report.manifestSha256())||!query.manifestSha256().equals(job.manifestSha256())
                ||query.recoveryRevision()!=report.recoveryRevision()||!original.value().queryNonce().equals(report.queryNonce())
                ||!original.value().rollbackBaselineSha256().equals(report.rollbackBaselineSha256())) throw invalid();
    }
    /** 固定协议错误不泄露原报告或受控配置。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("OTA回退预检不满足原认证查询合同"); }
    /** 仅表达事务内的观察接纳，不表达执行权。 */
    public enum Outcome {
        /** 同字节原报告只读。 */ REPLAY,
        /** 保留不合格或未知事实。 */ OBSERVED,
        /** 具备进一步原子准备前提，仍无槽位切换权。 */ PREPARABLE
    }
}
