package com.things.link.ota.application;

import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaJobProgressRepository;
import com.things.link.ota.domain.OtaInstallStopErrorCode;
import com.things.link.ota.domain.OtaInstallStopOperation;
import com.things.link.ota.domain.OtaInstallStopRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.tenant.DataPlaneDatabase;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 固定取消请求只生成一个安装前停止操作，不把取消请求当作设备已停止。 */
@Service
@DataPlaneDatabase
public class OtaInstallStopOperationService {
    /** 未获安装接纳证明的可停止候选，数据库另核原历史。 */
    private static final Set<String> STATES=Set.of("DISPATCHED","DOWNLOADING","VERIFYING","RECOVERY_REQUIRED","RETRY_WAIT");
    /** 固定操作与原子最终围栏。 */ private final OtaInstallStopRepository operations;
    /** 真实控制锁和取消请求。 */ private final OtaCampaignRuntimeRepository runtime;
    /** 不可变原作业来源。 */ private final OtaJobProgressRepository progress;
    /** 原规范报告完整性。 */ private final OtaExecutionOriginGuard origins;
    /** 独立受控制造合同。 */ private final OtaInstallStopBaselineQualification baselines;
    /** 原代际设备身份。 */ private final OtaDeviceIdentityPort identities;
    /** 当前项目生命周期。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 权威候选建立真实范围。 */ private final TransactionLocalRlsScope rls;
    /** 同事务系统审计。 */ private final AuditLogService audit;

    /** 不接受HTTP指定scope或构造管理账号。 */
    public OtaInstallStopOperationService(OtaInstallStopRepository operations,OtaCampaignRuntimeRepository runtime,
            OtaJobProgressRepository progress,OtaExecutionOriginGuard origins,OtaInstallStopBaselineQualification baselines,
            OtaDeviceIdentityPort identities,ProjectLifecycleAccessService lifecycle,TransactionLocalRlsScope rls,AuditLogService audit) {
        this.operations=operations;this.runtime=runtime;this.progress=progress;this.origins=origins;this.baselines=baselines;
        this.identities=identities;this.lifecycle=lifecycle;this.rls=rls;this.audit=audit;
    }
    /** 一条可信候选独立短事务；缺基线退避并保留原取消责任。 */
    @Transactional(timeout=5)
    public boolean seedOne() {
        if(TenantContext.current().isPresent()) throw new IllegalStateException("停止后台不能继承管理账号");
        var candidate=operations.nextCandidate().orElse(null);if(candidate==null) return false;
        rls.establish(candidate.tenantId(),candidate.projectId());
        if(!lifecycle.lockActiveForWrite(candidate.tenantId(),candidate.projectId())) return false;
        runtime.controlLock(candidate.tenantId(),candidate.projectId());
        var graph=runtime.lockRuntime(candidate.projectId(),candidate.campaignId()).orElse(null);
        var job=progress.locate(candidate.jobId()).orElse(null);
        if(graph==null||job==null||!"CANCELLING".equals(graph.campaign().status())||graph.runtimeCancellation()==null
                ||!STATES.contains(job.status())||operations.findForJob(job.jobId(),job.attemptNo()).isPresent()) return false;
        if(operations.deviceConflicted(job.deviceId())) return defer(job);
        var origin=progress.origin(job.jobId(),job.attemptNo()).orElseThrow(()->new IllegalStateException("停止原执行来源缺失"));
        origins.decode(origin);
        var identity=new AuthenticatedDeviceIdentity(job.tenantId(),job.projectId(),job.deviceId(),job.credentialVersion());
        var device=identities.lockCurrent(identity).orElse(null);if(device==null) return defer(job);
        OtaInstallStopBaselineQualification.Qualified baseline;
        try { baseline=baselines.requireCurrent(job.tenantId(),job.projectId(),device.deviceTypeId()); }
        catch(BusinessException failure) {
            if(failure.errorCode()!=OtaInstallStopErrorCode.UNAVAILABLE) throw failure;
            return defer(job);
        }
        var id=Uuid7.generate();var now=runtime.currentTime();var deadline=now.plusSeconds(60);
        // D-157：停止命令必须携带作业的下载授权id（含尚未封存的），顺序为DB的ORDER BY id；
        // locate(...)只返回已封存授权（sealed_at IS NOT NULL），会漏掉“申请已受理未封存”窗口的授权。
        // D-158：授权集合收窄到当前尝试（与DB函数的r.attempt_no=j.attempt_no一致），历史尝试授权不进入命令。
        List<java.util.UUID> authorizations=operations.authorizationIds(job.jobId(),job.attemptNo());
        long cancelRevision=graph.runtimeCancellation().requestedRevision();
        var command=new OtaInstallStopOperationCodec.Operation("tc-ota-install-stop-operation/v1",id,job.campaignId(),
                job.jobId(),job.attemptNo(),job.manifestSha256(),authorizations,baseline.extension().sha256(),cancelRevision,deadline.getEpochSecond());
        byte[] bytes=new OtaInstallStopOperationCodec().encode(command);
        if(!identities.credentialValid(identity)) return defer(job);
        var operation=new OtaInstallStopOperation(id,job.tenantId(),job.projectId(),job.campaignId(),job.jobId(),job.deviceId(),
                job.attemptNo(),job.credentialVersion(),job.manifestSha256(),origin.reportHash(),cancelRevision,job.revision(),
                baseline.parent().canonical(),baseline.extension().canonical(),authorizations,bytes,OtaTrustBundleCodec.sha256(bytes),now,deadline);
        boolean created=operations.create(job,operation,"CANCELLATION_ATOMIC_STOP_REQUESTED");
        if(created) audit.record(new AuditLogEntry(job.tenantId(),job.projectId(),null,"ota_device_job",job.jobId(),
                "ota.install_stop.created",Map.of("actorKind","SYSTEM","operationId",id.toString())));
        return created;
    }
    /** 缺资格只持久退避，不把退避当作操作创建成功。 */
    private boolean defer(OtaJobProgressRepository.Context job) {
        if(operations.deferCandidate(job)) audit.record(new AuditLogEntry(job.tenantId(),job.projectId(),null,
                "ota_device_job",job.jobId(),"ota.install_stop.deferred",Map.of("actorKind","SYSTEM","reason","CANDIDATE_UNAVAILABLE")));
        return false;
    }

}
