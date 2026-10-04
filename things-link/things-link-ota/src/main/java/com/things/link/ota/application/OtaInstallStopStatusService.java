package com.things.link.ota.application;

import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaInstallStopRepository;
import com.things.link.ota.domain.OtaInstallStopStatusQuery;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.tenant.DataPlaneDatabase;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 未知停止只查询原固定操作；查询不会创造第二次停止能力。 */
@Service
@DataPlaneDatabase
public class OtaInstallStopStatusService {
    /** 保留责任期间允许安全查询。 */ private static final Set<String> ACTIVE=Set.of("CANCELLING");
    /** 查询事实。 */ private final OtaInstallStopRepository operations;
    /** 项目控制图。 */ private final OtaCampaignRuntimeRepository runtime;
    /** 原代际身份。 */ private final OtaDeviceIdentityPort identities;
    /** ACTIVE项目许可。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 真实连接范围。 */ private final TransactionLocalRlsScope rls;
    /** 同事务审计。 */ private final AuditLogService audit;
    /** 依赖原操作，不依赖任何网络执行结果推定。 */
    public OtaInstallStopStatusService(OtaInstallStopRepository operations,OtaCampaignRuntimeRepository runtime,
            OtaDeviceIdentityPort identities,ProjectLifecycleAccessService lifecycle,TransactionLocalRlsScope rls,AuditLogService audit) {
        this.operations=operations;this.runtime=runtime;this.identities=identities;this.lifecycle=lifecycle;this.rls=rls;this.audit=audit;
    }
    /** 持久退避与审计一起提交，保留后台没有创建新挑战的原因。 */
    private void defer(com.things.link.ota.domain.OtaInstallStopOperation op) {
        if(operations.deferStatusCandidate(op.id())) audit.record(new AuditLogEntry(op.tenantId(),op.projectId(),null,
                "ota_device_job",op.jobId(),"ota.install_stop.status.query",
                Map.of("actorKind","SYSTEM","reason","CANDIDATE_UNAVAILABLE")));
    }
    /** 单个未知操作独立查询，固定六十秒窗口与持久冷却。 */
    @Transactional(timeout=5)
    public boolean seedOne() {
        if(TenantContext.current().isPresent()) throw new IllegalStateException("停止查询不能继承管理身份");
        var op=operations.nextStatusCandidate().orElse(null);if(op==null) return false;
        rls.establish(op.tenantId(),op.projectId());
        if(!lifecycle.lockActiveForWrite(op.tenantId(),op.projectId())) return false;
        runtime.controlLock(op.tenantId(),op.projectId());
        var graph=runtime.lockRuntime(op.projectId(),op.campaignId()).orElse(null);
        if(graph==null||!ACTIVE.contains(graph.campaign().status())) return false;
        var old=operations.currentStatusQuery(op.id()).orElse(null);
        boolean expired=old!=null&&operations.expireStatusQuery(old.id());
        if(expired) audit.record(new AuditLogEntry(op.tenantId(),op.projectId(),null,"ota_device_job",op.jobId(),
                "ota.install_stop.status.query",Map.of("actorKind","SYSTEM","reason","WINDOW_FENCED")));
        var identity=new AuthenticatedDeviceIdentity(op.tenantId(),op.projectId(),op.deviceId(),op.credentialVersion());
        if(identities.lockCurrent(identity).isEmpty()||!operations.hasOperationSendReservation(op.id())) {
            defer(op); return expired;
        }
        var id=Uuid7.generate();var now=runtime.currentTime();var deadline=now.plusSeconds(60);
        var query=new OtaInstallStopStatusQueryCodec.Query("tc-ota-install-stop-status-query/v1",id,UUID.randomUUID(),op.id(),op.jobId(),
                op.attemptNo(),op.manifestSha256(),op.payloadHash(),deadline.getEpochSecond());
        byte[] bytes=new OtaInstallStopStatusQueryCodec().encode(query);
        if(!identities.credentialValid(identity)) { defer(op); return expired; }
        boolean created=operations.createStatusQuery(new OtaInstallStopStatusQuery(id,op.tenantId(),op.projectId(),op.campaignId(),op.id(),
                op.jobId(),op.deviceId(),op.attemptNo(),op.credentialVersion(),bytes,OtaTrustBundleCodec.sha256(bytes),now,deadline));
        if(created) audit.record(new AuditLogEntry(op.tenantId(),op.projectId(),null,"ota_device_job",op.jobId(),
                "ota.install_stop.status.query",Map.of("actorKind","SYSTEM","reason","CREATED")));
        return created||expired;
    }
}
