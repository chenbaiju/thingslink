package com.things.link.ota.application;

import com.things.link.device.application.DeviceMqttDownlinkRoute;
import com.things.link.device.application.DeviceMqttDownlinkRoutePort;
import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.device.application.OtaDeviceNotificationRoutePort;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaConfirmationRepository;
import com.things.link.ota.domain.OtaJobProgressRepository;
import com.things.link.ota.domain.OtaReconciliationDeliveryRepository;
import com.things.link.ota.domain.OtaReconciliationQuery;
import com.things.link.ota.domain.OtaReconciliationRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 恢复查询不产生新的刷写权；创建、预留和观察各用独立短事务。 */
@Service
@DataPlaneDatabase
public class OtaCommitReconciliationDeliveryService {
    /** 原许可事务冻结MQTT设备身份及配置空间。 */ private final DeviceMqttDownlinkRoutePort mqttRoutes;
    /** 保留责任期间允许只读安全查询。 */
    private static final Set<String> ACTIVE = Set.of("RUNNING", "PAUSED", "CANCELLING");
    /** 查询与围栏事实。 */ private final OtaReconciliationRepository queries;
    /** 单次物理发送能力。 */ private final OtaReconciliationDeliveryRepository delivery;
    /** 项目控制及完整运行图。 */ private final OtaCampaignRuntimeRepository runtime;
    /** 不可变来源与当前作业。 */ private final OtaJobProgressRepository progress;
    /** 原提交许可及实际预留事实。 */ private final OtaConfirmationRepository confirmations;
    /** 当前身份锁与最终凭据复验。 */ private final OtaDeviceIdentityPort identities;
    /** 当前认证路由。 */ private final OtaDeviceNotificationRoutePort routes;
    /** ACTIVE真实项目共享锁。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 实际数据连接范围。 */ private final TransactionLocalRlsScope rls;
    /** 项目路由公开端口。 */ private final ProjectService projects;
    /** 事务内仅读配置，不运行网络。 */ private final OtaCommitReconciliationPublisher publisher;
    /** 同事务系统审计。 */ private final AuditLogService audit;
    /** 回退和提交不可跨越的方向门禁。 */ private final OtaRollbackDirectionGuard directions;
    /** 查询严格规范字节。 */ private final OtaCommitReconciliationQueryCodec codec = new OtaCommitReconciliationQueryCodec();

    /** 依赖均加入当前短事务，发送在外部worker中执行。 */
    public OtaCommitReconciliationDeliveryService(OtaReconciliationRepository queries,
            OtaReconciliationDeliveryRepository delivery, OtaCampaignRuntimeRepository runtime,
            OtaJobProgressRepository progress, OtaConfirmationRepository confirmations,
            OtaDeviceIdentityPort identities, OtaDeviceNotificationRoutePort routes,
            ProjectLifecycleAccessService lifecycle, TransactionLocalRlsScope rls, ProjectService projects,
            OtaCommitReconciliationPublisher publisher, AuditLogService audit, OtaRollbackDirectionGuard directions, DeviceMqttDownlinkRoutePort mqttRoutes) {
        this.mqttRoutes=mqttRoutes;
        this.queries=queries; this.delivery=delivery; this.runtime=runtime; this.progress=progress;
        this.confirmations=confirmations; this.identities=identities; this.routes=routes; this.lifecycle=lifecycle;
        this.rls=rls; this.projects=projects; this.publisher=publisher; this.audit=audit; this.directions=directions;
    }

    /** 单次只处理一个候选，先围栏已到期窗口，再按持久冷却创建新查询。 */
    @Transactional(timeout=5)
    public boolean seedOne() {
        background();
        var candidate=queries.nextCandidate().orElse(null);
        if(candidate==null) return false;
        rls.establish(candidate.tenantId(),candidate.projectId());
        if(!lifecycle.lockActiveForWrite(candidate.tenantId(),candidate.projectId())) return false;
        runtime.controlLock(candidate.tenantId(),candidate.projectId());
        var graph=runtime.lockRuntime(candidate.projectId(),candidate.campaignId()).orElse(null);
        var job=progress.locate(candidate.jobId()).orElse(null);
        if(graph==null||job==null||!ACTIVE.contains(graph.campaign().status())||!"RECOVERY_REQUIRED".equals(job.status())) return false;
        if(!directions.commitAllowed(job.jobId(),job.attemptNo(),job.deviceId())) { queries.deferCandidate(job); return false; }
        var previous=queries.currentQuery(job.jobId(),job.attemptNo()).orElse(null);
        boolean expired=previous!=null&&queries.expireQuery(previous.id());
        if(expired) record(previous,"WINDOW_FENCED");
        var identity=new AuthenticatedDeviceIdentity(job.tenantId(),job.projectId(),job.deviceId(),job.credentialVersion());
        if(identities.lockCurrent(identity).isEmpty()||progress.origin(job.jobId(),job.attemptNo()).isEmpty()) {
            queries.deferCandidate(job); return expired;
        }
        var permit=confirmations.findPermit(job.jobId(),job.attemptNo()).orElse(null);
        if(permit==null||!confirmations.hasSendReservation(permit.id())||job.revision()<1||job.revision()>9_007_199_254_740_991L) {
            queries.deferCandidate(job); return expired;
        }
        Instant now=runtime.currentTime(); Instant deadline=now.plusSeconds(60);
        UUID id=Uuid7.generate(), nonce=UUID.randomUUID();
        var body=new OtaCommitReconciliationQueryCodec.Query("tc-ota-commit-reconciliation-query/v1",id,nonce,
                job.jobId(),job.attemptNo(),job.revision(),job.authorizationId(),job.manifestSha256(),permit.id(),permit.bootId(),deadline.getEpochSecond());
        byte[] canonical=codec.encode(body);
        var query=new OtaReconciliationQuery(id,job.tenantId(),job.projectId(),job.campaignId(),job.jobId(),job.deviceId(),
                job.attemptNo(),job.credentialVersion(),job.revision(),job.authorizationId(),permit.id(),nonce,permit.bootId(),
                job.manifestSha256(),canonical,OtaTrustBundleCodec.sha256(canonical),now,deadline);
        if(!identities.credentialValid(identity)) { queries.deferCandidate(job); return expired; }
        boolean created=queries.createQuery(job,query);
        if(created) record(query,"CREATED");
        return created||expired;
    }

    /** 领取在单独事务中进行，不持有另一项目的完整控制图。 */
    @Transactional(timeout=5)
    public Optional<OtaReconciliationDeliveryRepository.Claim> claimOne() { background(); return delivery.claimOne(); }

    /** 重新核对原恢复状态和当前路由，只预留同一查询的真实发送。 */
    @Transactional(timeout=5)
    public Optional<Prepared> prepare(UUID queryId,UUID token) {
        background(); if(queryId==null||token==null) return Optional.empty();
        var located=delivery.authoritativeClaim(queryId,token).orElse(null); if(located==null) return Optional.empty();
        var query=located.query(); rls.establish(query.tenantId(),query.projectId());
        if(!lifecycle.lockActiveForWrite(query.tenantId(),query.projectId())) return Optional.empty();
        runtime.controlLock(query.tenantId(),query.projectId());
        var graph=runtime.lockRuntime(query.projectId(),query.campaignId()).orElse(null);
        var claim=delivery.authoritativeClaim(queryId,token).orElse(null);
        if(graph==null||claim==null) return Optional.empty();
        if("IN_FLIGHT".equals(claim.status())) {
            if(delivery.recoverExpired(claim)) record(query,"RECOVERED_UNKNOWN"); return Optional.empty();
        }
        if(!runtime.currentTime().isBefore(Instant.ofEpochSecond(query.deadlineAt().getEpochSecond()))||claim.transportCount()>=3) {
            if(delivery.exhaustDue(claim,"QUERY_DELIVERY_EXHAUSTED")) record(query,"EXHAUSTED"); return Optional.empty();
        }
        var job=progress.locate(query.jobId()).orElse(null);
        if(!ACTIVE.contains(graph.campaign().status())||job==null||!"RECOVERY_REQUIRED".equals(job.status())||job.revision()!=query.recoveryRevision()
                ||!directions.commitAllowed(job.jobId(),job.attemptNo(),job.deviceId())) {
            delivery.deferIneligible(claim,"RECOVERY_CONTEXT_CHANGED"); return Optional.empty();
        }
        var identity=new AuthenticatedDeviceIdentity(query.tenantId(),query.projectId(),query.deviceId(),query.credentialVersion());
        var route=routes.lockCurrent(identity).orElse(null);
        if(route==null||identities.lockCurrent(identity).isEmpty()||!publisher.configured()) {
            delivery.deferIneligible(claim,"CURRENT_ROUTE_OR_TRANSPORT_UNAVAILABLE"); return Optional.empty();
        }
        var project=projects.requireRoutingContext(query.projectId());
        if(!project.tenantId().equals(query.tenantId())) throw new IllegalStateException("OTA对账查询路由范围不符");
        var mqttRoute=mqttRoutes.lockCurrent(query.tenantId(),query.projectId(),query.deviceId()).orElse(null);
        if(mqttRoute==null) { delivery.deferIneligible(claim,"CURRENT_ROUTE_OR_TRANSPORT_UNAVAILABLE"); return Optional.empty(); }
        if(!mqttRoute.projectKey().equals(project.projectKey())||!mqttRoute.deviceKey().equals(route.deviceKey())) {
            throw new IllegalStateException("OTA发送许可MQTT路由不匹配");
        }
        String topic="tc/v1/"+project.projectKey()+"/"+route.deviceKey()+"/down/ota/commit/reconciliation/query";
        var decoded=codec.decode(query.canonical());
        if(!Arrays.equals(decoded.canonical(),query.canonical())||!decoded.sha256().equals(query.payloadHash())
                ||!decoded.value().queryId().equals(query.id())||claim.topic()!=null&&!claim.topic().equals(topic)) {
            delivery.deferIneligible(claim,"QUERY_OR_ROUTE_CHANGED"); return Optional.empty();
        }
        if(!identities.credentialValid(identity)) return Optional.empty();
        var transport=delivery.reserveSend(claim,topic); if(transport.isEmpty()) return Optional.empty();
        record(query,"RESERVED");
        return Optional.of(new Prepared(transport.orElseThrow(), mqttRoute,claim.leaseUntil()));
    }

    /** 迟到真实外部观察保留，只有当前租约能结算交付头。 */
    @Transactional(timeout=5)
    public boolean complete(UUID transportId,UUID token,OtaCommitReconciliationPublisher.Result result) {
        background(); if(transportId==null||token==null||result==null||result.outcome()==null) return false;
        var transport=delivery.authoritativeTransport(transportId,token).orElse(null); if(transport==null) return false;
        var query=transport.query(); rls.establish(query.tenantId(),query.projectId());
        boolean active=lifecycle.lockActiveForWrite(query.tenantId(),query.projectId());
        if(active) { runtime.controlLock(query.tenantId(),query.projectId()); runtime.lockRuntime(query.projectId(),query.campaignId()); }
        boolean observed=delivery.recordObservation(transport,result.outcome().name(),result.status(),
                result.outcome()==OtaCommitReconciliationPublisher.Outcome.BROKER_ACCEPTED?null:result.reason());
        if(active) delivery.authoritativeClaim(query.id(),transport.deliveryLeaseToken()).ifPresent(c->delivery.settleCurrent(c,transport.id()));
        if(observed) record(query,"TRANSPORT_"+result.outcome().name());
        return observed;
    }

    /** 只记录稳定原因和非秘密查询身份。 */
    private void record(OtaReconciliationQuery query,String reason) {
        audit.record(new AuditLogEntry(query.tenantId(),query.projectId(),null,"ota_reconciliation_query",query.id(),
                "ota.reconciliation.query",Map.of("actorKind","SYSTEM","reason",reason)));
    }
    /** 后台不能继承管理主体。 */
    private static void background() { if(TenantContext.current().isPresent()) throw new IllegalStateException("OTA恢复查询不能继承管理账号"); }
    /** 本事务已提交的固定物理能力。 */
    public record Prepared(OtaReconciliationDeliveryRepository.Transport transport, DeviceMqttDownlinkRoute route, Instant leaseUntil) {
        /** 原设备所见项目路径。 */ public String projectKey() { return route.projectKey(); }
        /** 原设备所见设备路径。 */ public String deviceKey() { return route.deviceKey(); }
    }
}
