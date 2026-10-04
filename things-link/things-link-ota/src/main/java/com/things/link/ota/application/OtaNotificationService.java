package com.things.link.ota.application;

import com.things.link.device.application.OtaDeviceNotificationRoutePort;
import com.things.link.device.application.DeviceMqttDownlinkRoute;
import com.things.link.device.application.DeviceMqttDownlinkRoutePort;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaCampaignRuntime;
import com.things.link.ota.domain.OtaNotificationRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
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

/** ADR0127通知预留与回执各自提交；数据库锁不跨越物理网络。 */
@Service
@DataPlaneDatabase
public class OtaNotificationService {
    /** 固定范围与单次传输能力。 */ private final OtaNotificationRepository repository;
    /** 项目控制锁与活动图。 */ private final OtaCampaignRuntimeRepository runtime;
    /** 活跃项目持续许可。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 真实事务连接双轴。 */ private final TransactionLocalRlsScope rls;
    /** 类型、设备之前取得权威路由。 */ private final OtaDeviceNotificationRoutePort routes;
    /** 同事务冻结当前MQTT配置空间。 */ private final DeviceMqttDownlinkRoutePort mqttRoutes;
    /** 每次发送重新校验当前完整资格。 */ private final OtaRuntimeQualification qualification;
    /** 项目公开应用端口，不跨域查询表。 */ private final ProjectService projects;
    /** 这里只检查配置，不在事务内调用网络。 */ private final OtaNotificationPublisher publisher;
    /** 同事务系统审计。 */ private final AuditLogService audit;
    /** 严格八字段规范正文。 */ private final OtaNotificationCodec codec = new OtaNotificationCodec();

    /** 明确注入真实边界，不接受客户端指定身份。 */
    public OtaNotificationService(OtaNotificationRepository repository, OtaCampaignRuntimeRepository runtime,
            ProjectLifecycleAccessService lifecycle, TransactionLocalRlsScope rls,
            OtaDeviceNotificationRoutePort routes, OtaRuntimeQualification qualification, ProjectService projects,
            OtaNotificationPublisher publisher, AuditLogService audit, DeviceMqttDownlinkRoutePort mqttRoutes) {
        this.repository = repository; this.runtime = runtime; this.lifecycle = lifecycle; this.rls = rls;
        this.routes = routes; this.qualification = qualification; this.projects = projects;
        this.publisher = publisher; this.audit = audit; this.mqttRoutes = mqttRoutes;
    }

    /** 有界领取由数据库固定谓词选择项目，提交后独立处理。 */
    @Transactional(timeout = 5)
    public Optional<OtaNotificationRepository.Claim> claimOne() {
        requireBackground();
        return repository.claimOne();
    }

    /** 只返回已提交后可以尝试的单次能力；失租恢复这一轮绝不发送。 */
    @Transactional(timeout = 5)
    public Optional<Prepared> prepare(UUID eventId, UUID token) {
        requireBackground();
        if (eventId == null || token == null) return Optional.empty();
        var found = repository.authoritativeClaim(eventId, token);
        if (found.isEmpty()) return Optional.empty();
        var located = found.orElseThrow();
        rls.establish(located.tenantId(), located.projectId());
        if (!lifecycle.lockActiveForWrite(located.tenantId(), located.projectId())) return Optional.empty();
        runtime.controlLock(located.tenantId(), located.projectId());
        var graph = runtime.lockRuntime(located.projectId(), located.campaignId()).orElse(null);
        var claim = repository.authoritativeClaim(eventId, token).orElse(null);
        if (graph == null || claim == null) return Optional.empty();
        OtaTransportRetryAudit.watch(runtime, audit, claim.tenantId(), claim.projectId(), claim.jobId(), claim.eventId());
        if ("IN_FLIGHT".equals(claim.status())) {
            if (repository.recoverExpired(claim)) record(claim, "RECOVERED_UNKNOWN");
            return Optional.empty();
        }
        if (!runtime.currentTime().isBefore(claim.deadline()) || claim.transportCount() >= 3) {
            if (repository.exhaustDue(claim, "NOTIFICATION_DELIVERY_EXHAUSTED")) record(claim, "EXHAUSTED");
            return Optional.empty();
        }
        if (!"RUNNING".equals(graph.campaign().status())) return Optional.empty();
        var identity = new AuthenticatedDeviceIdentity(claim.tenantId(), claim.projectId(), claim.deviceId(),
                claim.credentialVersion());
        var route = routes.lockCurrent(identity).orElse(null);
        var outcome = qualification.check(claim.tenantId(), claim.projectId(), claim.deviceId(), claim.firmwareId());
        if (outcome.disposition() == OtaRuntimeQualification.Disposition.SECURITY) {
            pause(claim, outcome.reason());
            return Optional.empty();
        }
        if (route == null || outcome.disposition() != OtaRuntimeQualification.Disposition.ELIGIBLE
                || outcome.credentialVersion() == null || outcome.credentialVersion() != claim.credentialVersion()) {
            if (repository.deferIneligible(claim, "CURRENT_DEVICE_INELIGIBLE")) record(claim, "DEFERRED");
            return Optional.empty();
        }
        var mqttRoute = mqttRoutes.lockCurrent(claim.tenantId(), claim.projectId(), claim.deviceId()).orElse(null);
        if (mqttRoute == null) {
            if (repository.deferIneligible(claim, "CURRENT_DEVICE_INELIGIBLE")) record(claim, "DEFERRED");
            return Optional.empty();
        }
        if (!publisher.configured()) {
            pause(claim, "NOTIFICATION_TRANSPORT_UNAVAILABLE");
            return Optional.empty();
        }
        var project = projects.requireRoutingContext(claim.projectId());
        if (!claim.tenantId().equals(project.tenantId())) throw new IllegalStateException("OTA项目路由范围不符");
        if (!project.projectKey().equals(mqttRoute.projectKey()) || !route.deviceKey().equals(mqttRoute.deviceKey())) {
            throw new IllegalStateException("OTA通知MQTT路由不匹配");
        }
        String topic;
        byte[] canonical;
        try {
            topic = OtaNotificationCodec.topic(project.projectKey(), route.deviceKey());
            canonical = codec.encode(new OtaNotificationCodec.Notification("tc-ota-available/v1", claim.eventId(),
                    claim.campaignId(), claim.jobId(), claim.firmwareId(), claim.jobAttemptNo(),
                    claim.manifestSha256(), claim.deadline()));
        } catch (IllegalArgumentException invalid) {
            pause(claim, "NOTIFICATION_INTENT_INVALID");
            return Optional.empty();
        }
        if (claim.topic() != null && (!claim.topic().equals(topic) || !Arrays.equals(claim.canonical(), canonical))) {
            pause(claim, "NOTIFICATION_ROUTE_OR_INTENT_CHANGED");
            return Optional.empty();
        }
        var transport = repository.reserveSend(claim, topic, canonical);
        if (transport.isEmpty()) return Optional.empty();
        record(claim, "RESERVED");
        return Optional.of(new Prepared(transport.orElseThrow(), mqttRoute, claim.leaseUntil()));
    }

    /** 迟到回执仅追加真实观察；当前活动、项目、期限、租约仍匹配才推进交付。 */
    @Transactional(timeout = 5)
    public boolean complete(UUID transportId, UUID reservationToken, OtaNotificationPublisher.Result result) {
        requireBackground();
        if (transportId == null || reservationToken == null || result == null || result.outcome() == null) return false;
        var found = repository.authoritativeTransport(transportId, reservationToken);
        if (found.isEmpty()) return false;
        var transport = found.orElseThrow();
        rls.establish(transport.tenantId(), transport.projectId());
        boolean active = lifecycle.lockActiveForWrite(transport.tenantId(), transport.projectId());
        OtaCampaignRuntime graph = null;
        if (active) {
            runtime.controlLock(transport.tenantId(), transport.projectId());
            graph = runtime.lockRuntime(transport.projectId(), transport.campaignId()).orElse(null);
        }
        if (active && graph != null) OtaTransportRetryAudit.watch(runtime, audit, transport.tenantId(),
                transport.projectId(), transport.jobId(), transport.eventId());
        boolean observed = repository.recordObservation(transport, result.outcome().name(), result.status(),
                result.outcome() == OtaNotificationPublisher.Outcome.BROKER_ACCEPTED ? null : result.reason());
        if (active && graph != null && "RUNNING".equals(graph.campaign().status())) {
            repository.authoritativeClaim(transport.eventId(), transport.deliveryLeaseToken())
                    .ifPresent(claim -> repository.settleCurrent(claim, transport.id()));
        }
        if (observed) audit.record(new AuditLogEntry(transport.tenantId(), transport.projectId(), null,
                "ota_notification_transport", transport.id(), "ota.notification.observed",
                Map.of("actorKind", "SYSTEM", "outcome", result.outcome().name())));
        return observed;
    }

    /** 安全暂停也必须通过精确交付租约围栏。 */
    private void pause(OtaNotificationRepository.Claim claim, String reason) {
        if (repository.pauseSecurity(claim, reason)) record(claim, "SECURITY_PAUSED");
    }
    /** 只审计固定分类和标识，不保存报文或供应商正文。 */
    private void record(OtaNotificationRepository.Claim claim, String action) {
        audit.record(new AuditLogEntry(claim.tenantId(), claim.projectId(), null, "ota_notification_delivery",
                claim.eventId(), "ota.notification.delivery", Map.of("actorKind", "SYSTEM", "action", action)));
    }
    /** 避免后台入口继承任何管理账号。 */
    private static void requireBackground() {
        if (TenantContext.current().isPresent()) throw new IllegalStateException("OTA通知不能继承管理账号");
    }
    /** 物理传输仅使用此事务提交后的短期能力。
     * @param transport 已预留尝试及不可变正文
     * @param route 同事务冻结的MQTT接收者和配置代次
     * @param leaseUntil 当前领取截止，不得跨越
     */
    public record Prepared(OtaNotificationRepository.Transport transport, DeviceMqttDownlinkRoute route,
                           Instant leaseUntil) {
        /** 原通知公开项目路径。 */ public String projectKey() { return route.projectKey(); }
        /** 原通知公开设备路径。 */ public String deviceKey() { return route.deviceKey(); }
    }
}
