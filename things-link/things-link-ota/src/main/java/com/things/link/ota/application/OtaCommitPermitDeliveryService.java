package com.things.link.ota.application;

import com.things.link.device.application.DeviceMqttDownlinkRoute;
import com.things.link.device.application.DeviceMqttDownlinkRoutePort;
import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.device.application.OtaDeviceNotificationRoutePort;
import com.things.link.ota.domain.OtaCampaignRuntime;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaCommitPermitDeliveryRepository;
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

/** 提交许可预留和观察分离；暂停、撤销不被解释为已撤回设备端许可。 */
@Service
@DataPlaneDatabase
public class OtaCommitPermitDeliveryService {
    /** 原许可事务冻结MQTT设备身份及配置空间。 */ private final DeviceMqttDownlinkRoutePort mqttRoutes;
    /** 独立真实许可交付能力。 */ private final OtaCommitPermitDeliveryRepository repository;
    /** 当前项目控制与完整图。 */ private final OtaCampaignRuntimeRepository runtime;
    /** ACTIVE真实共享锁。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 实际连接范围。 */ private final TransactionLocalRlsScope rls;
    /** 原DIRECT身份和当前权威路由。 */ private final OtaDeviceNotificationRoutePort routes;
    /** 当前凭据最终时钟复验。 */ private final OtaDeviceIdentityPort identities;
    /** 原已准入执行来源，不读取会覆盖的当前报告推测。 */ private final OtaJobProgressRepository progress;
    /** 原规范报告恢复。 */ private final OtaExecutionOriginGuard origins;
    /** 已知设备提交下限关闭新的不可逆许可。 */ private final OtaKnownSecurityFloor floors;
    /** 历史方向冲突形成设备级永久安全隔离。 */ private final OtaRollbackDirectionGuard directions;
    /** 不借旧设备报告重新推测安装前来源，只检查当前发布。 */ private final OtaRuntimeQualification qualification;
    /** 项目公开路由端口。 */ private final ProjectService projects;
    /** 事务中只读配置，网络在worker内执行。 */ private final OtaCommitPermitPublisher publisher;
    /** 原子系统审计。 */ private final AuditLogService audit;
    /** 许可完整规范字节复验。 */ private final OtaCommitPermitCodec codec = new OtaCommitPermitCodec();

    /** 外部网络与持锁事务明确分开。 */
    public OtaCommitPermitDeliveryService(OtaCommitPermitDeliveryRepository repository, OtaCampaignRuntimeRepository runtime,
            ProjectLifecycleAccessService lifecycle, TransactionLocalRlsScope rls, OtaDeviceNotificationRoutePort routes,
            OtaDeviceIdentityPort identities, OtaRuntimeQualification qualification, ProjectService projects,
            OtaCommitPermitPublisher publisher, AuditLogService audit, OtaJobProgressRepository progress,
            OtaExecutionOriginGuard origins, OtaKnownSecurityFloor floors, OtaRollbackDirectionGuard directions, DeviceMqttDownlinkRoutePort mqttRoutes) {
        this.mqttRoutes=mqttRoutes;
        this.repository = repository; this.runtime = runtime; this.lifecycle = lifecycle; this.rls = rls;
        this.routes = routes; this.identities = identities; this.qualification = qualification; this.projects = projects;
        this.publisher = publisher; this.audit = audit; this.progress = progress; this.origins = origins; this.floors = floors; this.directions = directions;
    }
    /** 有界领取不持有伪造账号。 */
    @Transactional(timeout = 5)
    public Optional<OtaCommitPermitDeliveryRepository.Claim> claimOne() { background(); return repository.claimOne(); }

    /** 固定许可和原路由有界重传，绝不签发第二张许可或刷新原期限。 */
    @Transactional(timeout = 5)
    public Optional<Prepared> prepare(UUID permitId, UUID token) {
        background();
        if (permitId == null || token == null) return Optional.empty();
        var located = repository.authoritativeClaim(permitId, token).orElse(null);
        if (located == null) return Optional.empty();
        var permit = located.permit();
        rls.establish(permit.tenantId(), permit.projectId());
        if (!lifecycle.lockActiveForWrite(permit.tenantId(), permit.projectId())) return Optional.empty();
        runtime.controlLock(permit.tenantId(), permit.projectId());
        var graph = runtime.lockRuntime(permit.projectId(), permit.campaignId()).orElse(null);
        var claim = repository.authoritativeClaim(permitId, token).orElse(null);
        if (graph == null || claim == null) return Optional.empty();
        if ("IN_FLIGHT".equals(claim.status())) {
            if (repository.recoverExpired(claim)) record(claim, "RECOVERED_UNKNOWN");
            return Optional.empty();
        }
        Instant expiry = Instant.ofEpochSecond(permit.deadlineAt().getEpochSecond());
        if (!runtime.currentTime().isBefore(expiry) || claim.transportCount() >= 3) {
            if (repository.exhaustDue(claim, "COMMIT_PERMIT_DELIVERY_EXHAUSTED")) record(claim, "EXHAUSTED");
            return Optional.empty();
        }
        if (!"RUNNING".equals(graph.campaign().status())) {
            if (repository.deferIneligible(claim, "CAMPAIGN_NOT_RUNNING")) record(claim, "DEFERRED");
            return Optional.empty();
        }
        if (!directions.deviceAllowed(permit.deviceId())) {
            if (repository.deferIneligible(claim, "DEVICE_ATOMIC_DIRECTION_CONFLICT")) record(claim, "DEFERRED");
            return Optional.empty();
        }
        var identity = new AuthenticatedDeviceIdentity(permit.tenantId(), permit.projectId(), permit.deviceId(), permit.credentialVersion());
        var route = routes.lockCurrent(identity).orElse(null);
        var device = identities.lockCurrent(identity).orElse(null);
        if (route == null || device == null) {
            if (repository.deferIneligible(claim, "CURRENT_IDENTITY_UNAVAILABLE")) record(claim, "DEFERRED");
            return Optional.empty();
        }
        var origin = progress.origin(permit.jobId(), permit.attemptNo()).orElse(null);
        if (origin == null || !OtaDeviceReportIngestionService.matchesModel(device, origins.decode(origin))
                || !floors.accepts(permit.tenantId(), permit.projectId(), permit.deviceId(), origins.decode(origin))) {
            pause(claim, "COMMIT_SOURCE_NO_LONGER_CURRENT"); return Optional.empty();
        }
        try {
            var release = qualification.validateCurrent(permit.tenantId(), permit.projectId(), graph.campaign().firmwareId());
            if (!release.release().id().equals(graph.campaign().releaseId())) {
                pause(claim, "COMMIT_RELEASE_IDENTITY_CHANGED"); return Optional.empty();
            }
        } catch (BusinessException unavailable) { pause(claim, "COMMIT_RELEASE_UNAVAILABLE"); return Optional.empty(); }
        if (!publisher.configured()) { pause(claim, "COMMIT_TRANSPORT_UNAVAILABLE"); return Optional.empty(); }
        var project = projects.requireRoutingContext(permit.projectId());
        if (!project.tenantId().equals(permit.tenantId())) throw new IllegalStateException("OTA许可路由范围不符");
        var mqttRoute=mqttRoutes.lockCurrent(permit.tenantId(),permit.projectId(),permit.deviceId()).orElse(null);
        if(mqttRoute==null) { if (repository.deferIneligible(claim, "CURRENT_IDENTITY_UNAVAILABLE")) record(claim, "DEFERRED"); return Optional.empty(); }
        if(!mqttRoute.projectKey().equals(project.projectKey())||!mqttRoute.deviceKey().equals(route.deviceKey())) {
            throw new IllegalStateException("OTA发送许可MQTT路由不匹配");
        }
        String topic = "tc/v1/" + project.projectKey() + "/" + route.deviceKey() + "/down/ota/commit/permit";
        var decoded = codec.decode(permit.canonical());
        if (!Arrays.equals(decoded.canonical(), permit.canonical()) || !decoded.sha256().equals(permit.payloadHash())
                || !decoded.value().permitId().equals(permit.id()) || decoded.value().expiresAt() != expiry.getEpochSecond()
                || claim.topic() != null && !claim.topic().equals(topic)) {
            pause(claim, "COMMIT_PERMIT_OR_ROUTE_CHANGED"); return Optional.empty();
        }
        if (!identities.credentialValid(identity)) return Optional.empty();
        var transport = repository.reserveSend(claim, topic);
        if (transport.isEmpty()) return Optional.empty();
        record(claim, "RESERVED");
        return Optional.of(new Prepared(transport.orElseThrow(), mqttRoute, claim.leaseUntil()));
    }

    /** 失租或暂停后的真实外部观察仍保存，不能恢复旧发送授权。 */
    @Transactional(timeout = 5)
    public boolean complete(UUID transportId, UUID reservationToken, OtaCommitPermitPublisher.Result result) {
        background();
        if (transportId == null || reservationToken == null || result == null || result.outcome() == null) return false;
        var transport = repository.authoritativeTransport(transportId, reservationToken).orElse(null);
        if (transport == null) return false;
        var permit = transport.permit(); rls.establish(permit.tenantId(), permit.projectId());
        boolean active = lifecycle.lockActiveForWrite(permit.tenantId(), permit.projectId());
        OtaCampaignRuntime graph = null;
        if (active) {
            runtime.controlLock(permit.tenantId(), permit.projectId());
            graph = runtime.lockRuntime(permit.projectId(), permit.campaignId()).orElse(null);
        }
        boolean observed = repository.recordObservation(transport, result.outcome().name(), result.status(),
                result.outcome() == OtaCommitPermitPublisher.Outcome.BROKER_ACCEPTED ? null : result.reason());
        if (active && graph != null && "RUNNING".equals(graph.campaign().status())) {
            repository.authoritativeClaim(permit.id(), transport.deliveryLeaseToken())
                    .ifPresent(claim -> repository.settleCurrent(claim, transport.id()));
        }
        if (observed) audit.record(new AuditLogEntry(permit.tenantId(), permit.projectId(), null, "ota_commit_permit_transport",
                transport.id(), "ota.commit_permit.observed", Map.of("actorKind", "SYSTEM", "outcome", result.outcome().name())));
        return observed;
    }
    /** 当前真实交付租约内关闭新许可投递，既有设备责任不抹去。 */
    private void pause(OtaCommitPermitDeliveryRepository.Claim claim, String reason) {
        if (repository.pauseSecurity(claim, reason)) record(claim, "SECURITY_PAUSED");
    }
    /** 固定分类审计不含设备证据或供应商正文。 */
    private void record(OtaCommitPermitDeliveryRepository.Claim claim, String action) {
        var permit = claim.permit();
        audit.record(new AuditLogEntry(permit.tenantId(), permit.projectId(), null, "ota_commit_permit_delivery", permit.id(),
                "ota.commit_permit.delivery", Map.of("actorKind", "SYSTEM", "action", action)));
    }
    /** 后台不能继承管理主体。 */
    private static void background() {
        if (TenantContext.current().isPresent()) throw new IllegalStateException("OTA许可交付不能继承管理账号");
    }
    /** 物理worker只使用本事务提交后的固定能力。 */
    public record Prepared(OtaCommitPermitDeliveryRepository.Transport transport, DeviceMqttDownlinkRoute route, Instant leaseUntil) {
        /** 原设备所见项目路径。 */ public String projectKey() { return route.projectKey(); }
        /** 原设备所见设备路径。 */ public String deviceKey() { return route.deviceKey(); }
    }
}
