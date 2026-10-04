package com.things.link.ota.application;

import com.things.link.device.application.DeviceMqttDownlinkRoute;
import com.things.link.device.application.DeviceMqttDownlinkRoutePort;
import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.device.application.OtaDeviceNotificationRoutePort;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaInstallStopDeliveryRepository;
import com.things.link.ota.domain.OtaInstallStopErrorCode;
import com.things.link.ota.domain.OtaInstallStopOperation;
import com.things.link.ota.domain.OtaInstallStopRepository;
import com.things.link.ota.domain.OtaJobProgressRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.tenant.DataPlaneDatabase;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 停止操作只发送一次，未知后只有原操作的只读查询能力。 */
@Service
@DataPlaneDatabase
public class OtaInstallStopDeliveryService {
    /** 真实短租约与独立晚观察。 */ private final OtaInstallStopDeliveryRepository delivery;
    /** 固定操作及耐久方向。 */ private final OtaInstallStopRepository operations;
    /** 当前取消请求与完整图。 */ private final OtaCampaignRuntimeRepository runtime;
    /** 原来源和可能为空的授权。 */ private final OtaJobProgressRepository progress;
    /** 当前制造合同。 */ private final OtaInstallStopBaselineQualification baselines;
    /** 当前设备代际。 */ private final OtaDeviceIdentityPort identities;
    /** 精确设备当前路由。 */ private final OtaDeviceNotificationRoutePort routes;
    /** 同预留事务冻结MQTT配置空间。 */ private final DeviceMqttDownlinkRoutePort mqttRoutes;
    /** 当前项目真实路由。 */ private final ProjectService projects;
    /** ACTIVE事务许可。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 原真实连接范围。 */ private final TransactionLocalRlsScope rls;
    /** 仅配置检查，网络在事务外。 */ private final OtaInstallStopPublisher publisher;
    /** 系统审计。 */ private final AuditLogService audit;

    /** 不允许默认成功的技术端口替身。 */
    public OtaInstallStopDeliveryService(OtaInstallStopDeliveryRepository delivery,OtaInstallStopRepository operations,
            OtaCampaignRuntimeRepository runtime,OtaJobProgressRepository progress,OtaInstallStopBaselineQualification baselines,
            OtaDeviceIdentityPort identities,OtaDeviceNotificationRoutePort routes,ProjectService projects,
            ProjectLifecycleAccessService lifecycle,TransactionLocalRlsScope rls,OtaInstallStopPublisher publisher,AuditLogService audit,DeviceMqttDownlinkRoutePort mqttRoutes) {
        this.delivery=delivery;this.operations=operations;this.runtime=runtime;this.progress=progress;this.baselines=baselines;
        this.identities=identities;this.routes=routes;this.projects=projects;this.lifecycle=lifecycle;this.rls=rls;this.publisher=publisher;this.audit=audit;this.mqttRoutes=mqttRoutes;
    }
    /** 领取仅返回持久能力，不发送网络。 */
    @Transactional(timeout=5)
    public Optional<OtaInstallStopDeliveryRepository.Claim> claimOne() { background();return delivery.claimOne(); }

    /** 等待控制锁后重新核对能力、身份、规范字节与固定期限。 */
    @Transactional(timeout=5)
    public Optional<Prepared> prepare(UUID event,UUID token) {
        background();if(event==null||token==null) return Optional.empty();
        var located=delivery.authoritativeClaim(event,token).orElse(null);if(located==null) return Optional.empty();
        var envelope=located.envelope();rls.establish(envelope.tenantId(),envelope.projectId());
        if(!lifecycle.lockActiveForWrite(envelope.tenantId(),envelope.projectId())) return Optional.empty();
        runtime.controlLock(envelope.tenantId(),envelope.projectId());
        var graph=runtime.lockRuntime(envelope.projectId(),envelope.campaignId()).orElse(null);
        var claim=delivery.authoritativeClaim(event,token).orElse(null);if(graph==null||claim==null) return Optional.empty();
        if("IN_FLIGHT".equals(claim.status())) {
            if(delivery.recoverExpired(claim)) record(envelope,"RECOVERED_UNKNOWN");return Optional.empty();
        }
        boolean command="OPERATION".equals(envelope.kind());if(!command&&!"STATUS".equals(envelope.kind())) throw corrupt();
        if(!runtime.currentTime().isBefore(Instant.ofEpochSecond(envelope.deadlineAt().getEpochSecond()))
                ||claim.transportCount()>=(command?1:3)) {
            if(delivery.exhaustDue(claim,"INSTALL_STOP_DELIVERY_EXHAUSTED")) record(envelope,"EXHAUSTED");return Optional.empty();
        }
        if(!"CANCELLING".equals(graph.campaign().status())||graph.runtimeCancellation()==null||!publisher.configured())
            return defer(claim,"CANCELLATION_OR_TRANSPORT_UNAVAILABLE");
        var operation=operations.find(envelope.operationId()).orElseThrow(OtaInstallStopDeliveryService::corrupt);
        var job=progress.locate(operation.jobId()).orElseThrow(OtaInstallStopDeliveryService::corrupt);
        if(!operation.tenantId().equals(envelope.tenantId())||!operation.projectId().equals(envelope.projectId())
                ||!operation.campaignId().equals(envelope.campaignId())||!operation.jobId().equals(envelope.jobId())
                ||!operation.deviceId().equals(envelope.deviceId())||operation.attemptNo()!=envelope.attemptNo()
                ||operation.credentialVersion()!=envelope.credentialVersion()
                ||operation.cancellationRevision()!=graph.runtimeCancellation().requestedRevision()) throw corrupt();
        if(operations.deviceConflicted(operation.deviceId())||job.attemptNo()!=operation.attemptNo()
                ||job.credentialVersion()!=operation.credentialVersion()||!job.manifestSha256().equals(operation.manifestSha256()))
            return defer(claim,"ORIGINAL_IDENTITY_UNAVAILABLE");
        var identity=new AuthenticatedDeviceIdentity(operation.tenantId(),operation.projectId(),operation.deviceId(),operation.credentialVersion());
        var device=identities.lockCurrent(identity).orElse(null);var route=routes.lockCurrent(identity).orElse(null);
        if(device==null||route==null) return defer(claim,"ORIGINAL_CREDENTIAL_UNAVAILABLE");
        var original=progress.origin(job.jobId(),job.attemptNo()).orElseThrow(OtaInstallStopDeliveryService::corrupt);
        if(!original.reportHash().equals(operation.originHash())) throw corrupt();
        validateOperation(operation);
        if(command) {
            if(!envelope.id().equals(operation.id())||!Arrays.equals(envelope.canonical(),operation.canonical())
                    ||!envelope.payloadHash().equals(operation.payloadHash())) throw corrupt();
            try {
                var current=baselines.requireCurrent(operation.tenantId(),operation.projectId(),device.deviceTypeId());
                if(!Arrays.equals(current.parent().canonical(),operation.parentBaseline())
                        ||!Arrays.equals(current.extension().canonical(),operation.stopBaseline())) return defer(claim,"CONTROLLED_BASELINE_CHANGED");
            } catch(BusinessException failure) {
                if(failure.errorCode()!=OtaInstallStopErrorCode.UNAVAILABLE) throw failure;
                return defer(claim,"CONTROLLED_BASELINE_UNAVAILABLE");
            }
        } else {
            var query=new OtaInstallStopStatusQueryCodec().decode(envelope.canonical());var value=query.value();
            if(!Arrays.equals(query.canonical(),envelope.canonical())||!query.sha256().equals(envelope.payloadHash())
                    ||!value.queryId().equals(envelope.id())||!value.operationId().equals(operation.id())
                    ||!value.jobId().equals(operation.jobId())||value.attemptNo()!=operation.attemptNo()
                    ||!value.manifestSha256().equals(operation.manifestSha256())||!value.operationSha256().equals(operation.payloadHash())
                    ||value.expiresAt()!=envelope.deadlineAt().getEpochSecond()) throw corrupt();
        }
        var project=projects.requireRoutingContext(envelope.projectId());if(!project.tenantId().equals(envelope.tenantId())) throw corrupt();
        var mqttRoute=mqttRoutes.lockCurrent(envelope.tenantId(),envelope.projectId(),envelope.deviceId()).orElse(null);
        if(mqttRoute==null) return defer(claim,"ORIGINAL_CREDENTIAL_UNAVAILABLE");
        if(!mqttRoute.projectKey().equals(project.projectKey())||!mqttRoute.deviceKey().equals(route.deviceKey())) throw corrupt();
        String topic="tc/v1/"+project.projectKey()+"/"+route.deviceKey()+"/down/ota/install-stop/"+(command?"operation":"status/query");
        if(claim.topic()!=null&&!claim.topic().equals(topic)) return defer(claim,"ROUTE_CHANGED");
        if(!identities.credentialValid(identity)) return defer(claim,"CREDENTIAL_CHANGED");
        var transport=delivery.reserveSend(claim,topic);if(transport.isEmpty()) return Optional.empty();
        record(envelope,"SEND_RESERVED");
        return Optional.of(new Prepared(transport.orElseThrow(),mqttRoute,claim.leaseUntil()));
    }
    /** 固定命令与所有原操作轴一致，持久损坏不降格为资格缺失。 */
    static void validateOperation(OtaInstallStopOperation operation) {
        OtaInstallStopOperationCodec.Decoded decoded;
        try {
            decoded=new OtaInstallStopOperationCodec().decode(operation.canonical());
            var parent=new OtaTypeBaselineCodec().decode(operation.parentBaseline());
            var extension=new OtaInstallStopBaselineCodec().decode(operation.stopBaseline());
            if(!OtaInstallStopBaselineQualification.matches(parent,extension)) throw corrupt();
        } catch(IllegalArgumentException failure) {
            // 原持久事实损坏属于基础设施故障，不能被raw入口误判为设备协议错误。
            throw new IllegalStateException("安装前停止原规范事实损坏",failure);
        }
        var command=decoded.value();
        if(!Arrays.equals(decoded.canonical(),operation.canonical())||!decoded.sha256().equals(operation.payloadHash())
                ||!command.operationId().equals(operation.id())||!command.campaignId().equals(operation.campaignId())
                ||!command.jobId().equals(operation.jobId())||command.attemptNo()!=operation.attemptNo()
                ||!command.manifestSha256().equals(operation.manifestSha256())||!command.authorizationIds().equals(operation.authorizationIds())
                ||!command.stopBaselineSha256().equals(OtaTrustBundleCodec.sha256(operation.stopBaseline()))
                ||command.cancellationRevision()!=operation.cancellationRevision()||command.expiresAt()!=operation.deadlineAt().getEpochSecond()) throw corrupt();
    }
    /** 暂缺当前资格只退避，不借机刷新操作期限。 */
    private Optional<Prepared> defer(OtaInstallStopDeliveryRepository.Claim claim,String reason) {
        if(delivery.deferIneligible(claim,reason)) record(claim.envelope(),reason);return Optional.empty();
    }
    /** 晚到HTTP观察与当前发送租约分开处理，未知结果不创建新操作。 */
    @Transactional(timeout=5)
    public boolean complete(UUID transportId,UUID token,OtaInstallStopPublisher.Result result) {
        background();if(transportId==null||token==null||result==null||result.outcome()==null) return false;
        var transport=delivery.authoritativeTransport(transportId,token).orElse(null);if(transport==null) return false;
        var envelope=transport.envelope();rls.establish(envelope.tenantId(),envelope.projectId());
        boolean active=lifecycle.lockActiveForWrite(envelope.tenantId(),envelope.projectId());
        if(active) {runtime.controlLock(envelope.tenantId(),envelope.projectId());runtime.lockRuntime(envelope.projectId(),envelope.campaignId());}
        boolean observed=delivery.recordObservation(transport,result.outcome().name(),result.status(),
                result.outcome()==OtaInstallStopPublisher.Outcome.BROKER_ACCEPTED?null:result.reason());
        if(active) delivery.authoritativeClaim(envelope.id(),transport.deliveryLeaseToken()).ifPresent(c->delivery.settleCurrent(c,transport.id()));
        if(observed) record(envelope,"TRANSPORT_"+result.outcome().name());return observed;
    }
    /** 固定分类系统审计，不输出规范证据或凭据。 */
    private void record(OtaInstallStopDeliveryRepository.Envelope envelope,String reason) {
        audit.record(new AuditLogEntry(envelope.tenantId(),envelope.projectId(),null,"ota_device_job",envelope.jobId(),
                "ota.install_stop.delivery",Map.of("actorKind","SYSTEM","eventId",envelope.id().toString(),"reason",reason)));
    }
    /** 后台只接受权威持久范围。 */
    private static void background() {if(TenantContext.current().isPresent()) throw new IllegalStateException("停止交付不能继承管理账号");}
    /** 固定不可变事实损坏异常。 */
    private static IllegalStateException corrupt() {return new IllegalStateException("安装前停止原规范事实不一致");}
    /** 原物理发送能力，不赋予新的逻辑操作。
     * @param transport 真实发送预留
     * @param route 同事务冻结的MQTT身份和配置空间
     * @param leaseUntil 原物理截止
     */
    public record Prepared(OtaInstallStopDeliveryRepository.Transport transport,DeviceMqttDownlinkRoute route,Instant leaseUntil) {
        /** 原设备所见项目路径。 */ public String projectKey() { return route.projectKey(); }
        /** 原设备所见设备路径。 */ public String deviceKey() { return route.deviceKey(); }
    }
}
