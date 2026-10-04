package com.things.link.ota.application;

import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaJobProgressRepository;
import com.things.link.ota.domain.OtaRollbackOperation;
import com.things.link.ota.domain.OtaRollbackPreflightRepository;
import com.things.link.ota.domain.OtaRollbackRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.tenant.DataPlaneDatabase;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 新鲜完整预检只创建一次固定原子回退；历史准备结论没有执行权。 */
@Service
@DataPlaneDatabase
public class OtaRollbackOperationService {
    /** 安全责任可在暂停和取消中收束。 */ private static final Set<String> ACTIVE=Set.of("RUNNING","PAUSED","CANCELLING");
    /** 固定操作事实。 */ private final OtaRollbackRepository operations;
    /** 当前预检与报告。 */ private final OtaRollbackPreflightRepository preflights;
    /** 完整资格独立重验。 */ private final OtaRollbackPreflightAssessmentService assessments;
    /** 项目运行图。 */ private final OtaCampaignRuntimeRepository runtime;
    /** 原作业身份。 */ private final OtaJobProgressRepository progress;
    /** 最终凭据。 */ private final OtaDeviceIdentityPort identities;
    /** 真实项目许可。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 数据连接范围。 */ private final TransactionLocalRlsScope rls;
    /** 与操作同事务的审计。 */ private final AuditLogService audit;
    /** 历史方向冲突形成设备级永久安全隔离。 */ private final OtaRollbackDirectionGuard directions;

    /** 注入事务端口，不在创建事务内发送网络请求。 */
    public OtaRollbackOperationService(OtaRollbackRepository operations,OtaRollbackPreflightRepository preflights,
            OtaRollbackPreflightAssessmentService assessments,OtaCampaignRuntimeRepository runtime,
            OtaJobProgressRepository progress,OtaDeviceIdentityPort identities,ProjectLifecycleAccessService lifecycle,
            TransactionLocalRlsScope rls,AuditLogService audit,OtaRollbackDirectionGuard directions) {
        this.operations=operations;this.preflights=preflights;this.assessments=assessments;this.runtime=runtime;
        this.progress=progress;this.identities=identities;this.lifecycle=lifecycle;this.rls=rls;this.audit=audit;this.directions=directions;
    }

    /** 一个完整候选单独短事务；不可准备时持久退避，不能借报告刷新阶段预算。 */
    @Transactional(timeout=5)
    public boolean seedOne() {
        if(TenantContext.current().isPresent()) throw new IllegalStateException("回退不能继承管理身份");
        var candidate=operations.nextCandidate().orElse(null); if(candidate==null) return false;
        rls.establish(candidate.tenantId(),candidate.projectId());
        if(!lifecycle.lockActiveForWrite(candidate.tenantId(),candidate.projectId())) return false;
        runtime.controlLock(candidate.tenantId(),candidate.projectId());
        var graph=runtime.lockRuntime(candidate.projectId(),candidate.campaignId()).orElse(null);
        var job=progress.locate(candidate.jobId()).orElse(null);
        if(graph==null||job==null||!ACTIVE.contains(graph.campaign().status())||!"RECOVERY_REQUIRED".equals(job.status())) return false;
        if(!directions.deviceAllowed(job.deviceId())) return defer(job);
        if(operations.findForJob(job.jobId(),job.attemptNo()).isPresent()) return false;
        var result=preflights.latestReport(job.jobId(),job.attemptNo()).orElse(null);
        var query=preflights.currentQuery(job.jobId(),job.attemptNo()).orElse(null);
        if(result==null||query==null||!result.receipt().queryId().equals(query.id())) return defer(job);
        var receipt=result.receipt();
        var decoded=new OtaRollbackPreflightReportCodec().decode(receipt.canonical());
        if(!Arrays.equals(decoded.canonical(),receipt.canonical())||!decoded.sha256().equals(receipt.payloadHash()))
            throw new IllegalStateException("回退预检原规范事实损坏");
        var report=decoded.value();
        boolean current=runtime.currentTime().isBefore(Instant.ofEpochSecond(query.deadlineAt().getEpochSecond()))
                &&!receipt.brokerReceivedAt().isBefore(query.createdAt())
                &&!receipt.brokerReceivedAt().isAfter(Instant.ofEpochSecond(query.deadlineAt().getEpochSecond()))
                &&preflights.hasSendReservation(query.id());
        var assessment=assessments.assess(job,query,report,receipt.brokerReceivedAt(),current);
        if(!"PREPARABLE".equals(assessment.decision().disposition())
                ||!OtaRollbackExecutionEvaluator.candidateUnhealthy(report,graph.campaign().canonicalManifest(),query.targetSlot())
                ||report.evidence().journal().operationRevision()>=9_007_199_254_740_991L) return defer(job);
        var source=report.evidence().slots().stream().filter(s->s.slot().equals(query.sourceSlot())).findFirst().orElseThrow();
        var budgets=new OtaCampaignPlanCodec().decode(graph.campaign().canonicalPlan()).value().executionPolicy().stageTimeoutSeconds();
        Instant now=runtime.currentTime();
        if(!now.isBefore(Instant.ofEpochSecond(query.deadlineAt().getEpochSecond()))) return defer(job);
        Instant pending=now.plusSeconds(budgets.rollbackPending()); Instant rolling=pending.plusSeconds(budgets.rollingBack());
        var id=Uuid7.generate();
        var value=new OtaRollbackOperationCodec.Operation("tc-ota-rollback-operation/v1",id,job.jobId(),job.attemptNo(),
                job.authorizationId(),job.manifestSha256(),query.id(),receipt.reportId(),receipt.payloadHash(),
                OtaTrustBundleCodec.sha256(query.baselineCanonical()),report.bootId(),report.evidence().journal().operationRevision(),
                report.committedSecurityVersion(),new OtaRollbackOperationCodec.Source(source.slot(),source.artifactSha256(),
                source.securityVersion(),source.thingModelVersionId(),source.thingModelSchemaDigestAlgorithm(),
                source.thingModelSchemaDigest(),source.propertyProfile()),query.targetSlot(),query.permitIds(),pending.getEpochSecond());
        byte[] bytes=new OtaRollbackOperationCodec().encode(value);
        var operation=new OtaRollbackOperation(id,job.tenantId(),job.projectId(),job.campaignId(),job.jobId(),job.deviceId(),
                job.attemptNo(),job.credentialVersion(),job.authorizationId(),job.manifestSha256(),query.id(),receipt.id(),job.revision(),
                bytes,OtaTrustBundleCodec.sha256(bytes),now,pending,rolling);
        var identity=new AuthenticatedDeviceIdentity(job.tenantId(),job.projectId(),job.deviceId(),job.credentialVersion());
        if(!identities.credentialValid(identity)) return defer(job);
        boolean created=operations.create(job,operation,"AUTHENTICATED_CANDIDATE_UNHEALTHY");
        if(created) audit.record(new AuditLogEntry(job.tenantId(),job.projectId(),null,"ota_device_job",job.jobId(),
                "ota.rollback.operation",Map.of("actorKind","SYSTEM","operationId",id.toString(),"reason","AUTHENTICATED_CANDIDATE_UNHEALTHY")));
        return created;
    }

    /** 缺资格不改变原恢复责任。 */
    private boolean defer(OtaJobProgressRepository.Context job) { operations.deferCandidate(job); return false; }
}
