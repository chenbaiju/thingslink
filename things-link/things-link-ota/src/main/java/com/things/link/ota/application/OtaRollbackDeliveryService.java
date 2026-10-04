package com.things.link.ota.application;

import com.things.link.device.application.DeviceMqttDownlinkRoute;
import com.things.link.device.application.DeviceMqttDownlinkRoutePort;
import com.things.link.device.application.OtaDeviceCommitPort;
import com.things.link.device.application.OtaDeviceIdentity;
import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.device.application.OtaDeviceNotificationRoutePort;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaJobProgressRepository;
import com.things.link.ota.domain.OtaRollbackDeliveryRepository;
import com.things.link.ota.domain.OtaRollbackOperation;
import com.things.link.ota.domain.OtaRollbackPreflightErrorCode;
import com.things.link.ota.domain.OtaRollbackPreflightRepository;
import com.things.link.ota.domain.OtaRollbackRepository;
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
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 原子操作只预留一次物理发送，未知后仅允许独立状态查询。 */
@Service
@DataPlaneDatabase
public class OtaRollbackDeliveryService {
    /** 既有安全责任在暂停或取消中仍需收束。 */
    private static final Set<String> ACTIVE = Set.of("RUNNING", "PAUSED", "CANCELLING");
    /** 单次物理租约与晚观察。 */ private final OtaRollbackDeliveryRepository delivery;
    /** 固定逻辑操作及方向。 */ private final OtaRollbackRepository operations;
    /** 原查询完整字节与历史计数。 */ private final OtaRollbackPreflightRepository preflights;
    /** 当前项目运行图。 */ private final OtaCampaignRuntimeRepository runtime;
    /** 原作业与来源。 */ private final OtaJobProgressRepository progress;
    /** 当前真实受控配置。 */ private final OtaRollbackBaselineQualification baselines;
    /** 原始来源规范恢复。 */ private final OtaExecutionOriginGuard origins;
    /** 当前设备与最终代际。 */ private final OtaDeviceIdentityPort identities;
    /** 当前设备路由。 */ private final OtaDeviceNotificationRoutePort routes;
    /** 同预留事务冻结MQTT配置空间。 */ private final DeviceMqttDownlinkRoutePort mqttRoutes;
    /** 真实已知安全下限。 */ private final OtaDeviceCommitPort floors;
    /** ACTIVE共享项目锁。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 可信定位后建立原数据范围。 */ private final TransactionLocalRlsScope rls;
    /** 当前项目实际路由。 */ private final ProjectService projects;
    /** 仅读取配置，网络始终在事务外。 */ private final OtaRollbackPublisher publisher;
    /** 预留和观察同事务审计。 */ private final AuditLogService audit;
    /** 历史方向冲突形成设备级永久安全隔离。 */ private final OtaRollbackDirectionGuard directions;

    /** 所有公开技术端口必须注入实际组件，不创建成功替身。 */
    public OtaRollbackDeliveryService(OtaRollbackDeliveryRepository delivery, OtaRollbackRepository operations,
            OtaRollbackPreflightRepository preflights, OtaCampaignRuntimeRepository runtime,
            OtaJobProgressRepository progress, OtaRollbackBaselineQualification baselines,
            OtaExecutionOriginGuard origins, OtaDeviceIdentityPort identities, OtaDeviceNotificationRoutePort routes,
            OtaDeviceCommitPort floors, ProjectLifecycleAccessService lifecycle, TransactionLocalRlsScope rls,
            ProjectService projects, OtaRollbackPublisher publisher, AuditLogService audit, OtaRollbackDirectionGuard directions, DeviceMqttDownlinkRoutePort mqttRoutes) {
        this.delivery = delivery; this.operations = operations; this.preflights = preflights;
        this.runtime = runtime; this.progress = progress; this.baselines = baselines; this.origins = origins;
        this.identities = identities; this.routes = routes; this.floors = floors; this.lifecycle = lifecycle;
        this.rls = rls; this.projects = projects; this.publisher = publisher; this.audit = audit; this.directions = directions; this.mqttRoutes = mqttRoutes;
    }

    /** 单独事务领取，不与其他项目的创建事务共用锁。 */
    @Transactional(timeout = 5)
    public Optional<OtaRollbackDeliveryRepository.Claim> claimOne() {
        background();
        return delivery.claimOne();
    }

    /** 原操作需要再次完整验真；状态查询不要求候选继续具备执行资格。 */
    @Transactional(timeout = 5)
    public Optional<Prepared> prepare(UUID eventId, UUID token) {
        background();
        if (eventId == null || token == null) return Optional.empty();
        var located = delivery.authoritativeClaim(eventId, token).orElse(null);
        if (located == null) return Optional.empty();
        var envelope = located.envelope();
        rls.establish(envelope.tenantId(), envelope.projectId());
        if (!lifecycle.lockActiveForWrite(envelope.tenantId(), envelope.projectId())) return Optional.empty();
        runtime.controlLock(envelope.tenantId(), envelope.projectId());
        var graph = runtime.lockRuntime(envelope.projectId(), envelope.campaignId()).orElse(null);
        var claim = delivery.authoritativeClaim(eventId, token).orElse(null);
        if (graph == null || claim == null) return Optional.empty();
        if ("IN_FLIGHT".equals(claim.status())) {
            if (delivery.recoverExpired(claim)) record(envelope, "RECOVERED_UNKNOWN");
            return Optional.empty();
        }
        boolean operation = "OPERATION".equals(envelope.kind());
        if (!operation && !"STATUS".equals(envelope.kind())) throw corrupt();
        if (!runtime.currentTime().isBefore(Instant.ofEpochSecond(envelope.deadlineAt().getEpochSecond()))
                || claim.transportCount() >= (operation ? 1 : 3)) {
            if (delivery.exhaustDue(claim, "ROLLBACK_DELIVERY_EXHAUSTED")) record(envelope, "EXHAUSTED");
            return Optional.empty();
        }
        var job = progress.locate(envelope.jobId()).orElse(null);
        var op = operations.find(envelope.operationId()).orElse(null);
        if (!ACTIVE.contains(graph.campaign().status()) || job == null || op == null
                || !sameIdentity(envelope, op, job)
                || !Set.of("ROLLBACK_PENDING", "ROLLING_BACK", "RECOVERY_REQUIRED").contains(job.status())) {
            return defer(claim, "ROLLBACK_CONTEXT_CHANGED");
        }
        if (!directions.deviceAllowed(op.deviceId())) return defer(claim, "DEVICE_ATOMIC_DIRECTION_CONFLICT");
        var direction = operations.control(op.id()).orElse(null);
        if (direction == null || direction.conflictedAt() != null || direction.commitWonReportId() != null
                || operation && (direction.acceptedReportId() != null || !"ROLLBACK_PENDING".equals(job.status()))) {
            return defer(claim, "ROLLBACK_DIRECTION_CHANGED");
        }
        var identity = new AuthenticatedDeviceIdentity(envelope.tenantId(), envelope.projectId(),
                envelope.deviceId(), envelope.credentialVersion());
        var route = routes.lockCurrent(identity).orElse(null);
        var device = identities.lockCurrent(identity).orElse(null);
        if (route == null || device == null || !publisher.configured()) return defer(claim, "ROUTE_OR_TRANSPORT_UNAVAILABLE");
        var decoded = new OtaRollbackOperationCodec().decode(op.canonical());
        if (!Arrays.equals(decoded.canonical(), op.canonical()) || !decoded.sha256().equals(op.payloadHash())) throw corrupt();
        var command = decoded.value();
        if (!command.operationId().equals(op.id()) || !command.jobId().equals(op.jobId())
                || command.attemptNo() != op.attemptNo() || !command.authorizationId().equals(op.authorizationId())
                || !command.manifestSha256().equals(op.manifestSha256())
                || command.expiresAt() != op.pendingDeadlineAt().getEpochSecond()) throw corrupt();
        if (operation) {
            if (!Arrays.equals(envelope.canonical(), op.canonical()) || !envelope.payloadHash().equals(op.payloadHash())
                    || !envelope.id().equals(op.id())) throw corrupt();
            if (!executable(op, job, command, device, graph.campaign().canonicalManifest())) {
                return defer(claim, "ROLLBACK_PREPARATION_CHANGED");
            }
        } else {
            var query = new OtaRollbackStatusQueryCodec().decode(envelope.canonical());
            var body = query.value();
            if (!Arrays.equals(query.canonical(), envelope.canonical()) || !query.sha256().equals(envelope.payloadHash())
                    || !body.queryId().equals(envelope.id()) || !body.operationId().equals(op.id())
                    || !body.jobId().equals(op.jobId()) || body.attemptNo() != op.attemptNo()
                    || !body.authorizationId().equals(op.authorizationId()) || !body.manifestSha256().equals(op.manifestSha256())
                    || !body.operationSha256().equals(op.payloadHash()) || body.expiresAt() != envelope.deadlineAt().getEpochSecond()) throw corrupt();
        }
        var project = projects.requireRoutingContext(envelope.projectId());
        if (!project.tenantId().equals(envelope.tenantId())) throw corrupt();
        var mqttRoute = mqttRoutes.lockCurrent(envelope.tenantId(), envelope.projectId(), envelope.deviceId()).orElse(null);
        if (mqttRoute == null) return defer(claim, "ROUTE_OR_TRANSPORT_UNAVAILABLE");
        if (!mqttRoute.projectKey().equals(project.projectKey()) || !mqttRoute.deviceKey().equals(route.deviceKey())) throw corrupt();
        String topic = "tc/v1/" + project.projectKey() + "/" + route.deviceKey() + "/down/ota/rollback/"
                + (operation ? "operation" : "status/query");
        if (claim.topic() != null && !claim.topic().equals(topic)) return defer(claim, "ROUTE_CHANGED");
        if (!identities.credentialValid(identity)) return defer(claim, "CREDENTIAL_CHANGED");
        // 锁等待不能延长原预检窗口；最终租约和一次发送预算仍由数据库CAS裁决。
        if (operation && !preflightLive(op)) return defer(claim, "PREFLIGHT_EXPIRED");
        var transport = delivery.reserveSend(claim, topic);
        if (transport.isEmpty()) return Optional.empty();
        record(envelope, "RESERVED");
        Instant physicalDeadline = claim.leaseUntil();
        if (operation) {
            var query = preflights.currentQuery(op.jobId(), op.attemptNo()).orElseThrow(OtaRollbackDeliveryService::corrupt);
            Instant preflightDeadline = Instant.ofEpochSecond(query.deadlineAt().getEpochSecond());
            if (preflightDeadline.isBefore(physicalDeadline)) physicalDeadline = preflightDeadline;
        }
        return Optional.of(new Prepared(transport.orElseThrow(), mqttRoute, physicalDeadline));
    }

    /** 原始预检仍须处于其固定窗口，不复制虚拟作业状态来绕过资格服务。 */
    private boolean preflightLive(OtaRollbackOperation op) {
        var query = preflights.currentQuery(op.jobId(), op.attemptNo()).orElse(null);
        return query != null && query.id().equals(op.preflightQueryId())
                && runtime.currentTime().isBefore(Instant.ofEpochSecond(query.deadlineAt().getEpochSecond()));
    }

    /** 精确原来源、受控基线、报告及已知安全计数共同限制一次真实执行。 */
    private boolean executable(OtaRollbackOperation op, OtaJobProgressRepository.Context job,
            OtaRollbackOperationCodec.Operation command, OtaDeviceIdentity device, byte[] manifest) {
        var query = preflights.currentQuery(op.jobId(), op.attemptNo()).orElse(null);
        var observed = preflights.latestReport(op.jobId(), op.attemptNo()).orElse(null);
        if (query == null || observed == null || !preflightLive(op) || !query.id().equals(command.queryId())
                || !observed.receipt().id().equals(op.preflightReceiptId())
                || !observed.receipt().queryId().equals(query.id()) || query.recoveryRevision() != op.recoveryRevision()) return false;
        var receipt = observed.receipt();
        var decoded = new OtaRollbackPreflightReportCodec().decode(receipt.canonical());
        if (!Arrays.equals(decoded.canonical(), receipt.canonical()) || !decoded.sha256().equals(receipt.payloadHash())) throw corrupt();
        var report = decoded.value();
        if (!command.reportId().equals(report.reportId()) || !command.reportSha256().equals(decoded.sha256())
                || !command.expectedBootId().equals(report.bootId())
                || command.expectedOperationRevision() != report.evidence().journal().operationRevision()
                || command.expectedCommittedSecurityVersion() != report.committedSecurityVersion()
                || !command.rollbackBaselineSha256().equals(OtaTrustBundleCodec.sha256(query.baselineCanonical()))
                || !command.targetSlot().equals(query.targetSlot()) || !command.permitIds().equals(query.permitIds())
                || !OtaDeviceReportIngestionService.fresh(receipt.brokerReceivedAt(), runtime.currentTime())
                || receipt.brokerReceivedAt().isBefore(query.createdAt())
                || receipt.brokerReceivedAt().isAfter(Instant.ofEpochSecond(query.deadlineAt().getEpochSecond()))) return false;
        OtaRollbackBaselineQualification.Qualified current;
        try { current = baselines.requireCurrent(op.tenantId(), op.projectId(), device.deviceTypeId()); }
        catch (BusinessException failure) {
            if (failure.errorCode() != OtaRollbackPreflightErrorCode.UNAVAILABLE) throw failure;
            return false;
        }
        if (!Arrays.equals(current.extension().canonical(), query.baselineCanonical())
                || !Arrays.equals(current.parent().canonical(), query.typeBaselineCanonical())) return false;
        var source = origins.decode(progress.origin(job.jobId(), job.attemptNo()).orElseThrow(OtaRollbackDeliveryService::corrupt));
        var expected = new OtaRollbackOperationCodec.Source(source.activeSlot(), source.currentFirmwareSha256(),
                source.currentSecurityVersion(), source.thingModelVersionId(), source.thingModelSchemaDigestAlgorithm(),
                source.thingModelSchemaDigest(), source.propertyProfile());
        if (!command.source().equals(expected)) return false;
        OptionalLong maximum = preflights.maxObservedCommitted(job.jobId(), job.attemptNo());
        OptionalLong reported = operations.maximumObservedCommitted(op.id());
        if (reported.isPresent() && (maximum.isEmpty() || reported.getAsLong() > maximum.getAsLong())) maximum = reported;
        var floor = floors.currentFloor(op.tenantId(), op.projectId(), op.deviceId());
        var decision = new OtaRollbackPreflightEvaluator().evaluate(source, device, current.parent().value(),
                current.extension().value(), report, query.permitIds(), floor, maximum);
        return "PREPARABLE".equals(decision.disposition())
                && OtaRollbackExecutionEvaluator.candidateUnhealthy(report, manifest, command.targetSlot());
    }

    /** 持久身份不能以当前路由或同设备另一作业替换。 */
    private static boolean sameIdentity(OtaRollbackDeliveryRepository.Envelope envelope, OtaRollbackOperation op,
            OtaJobProgressRepository.Context job) {
        return envelope.tenantId().equals(op.tenantId()) && envelope.projectId().equals(op.projectId())
                && envelope.campaignId().equals(op.campaignId()) && envelope.jobId().equals(op.jobId())
                && envelope.deviceId().equals(op.deviceId()) && envelope.attemptNo() == op.attemptNo()
                && envelope.credentialVersion() == op.credentialVersion()
                && job.tenantId().equals(op.tenantId()) && job.projectId().equals(op.projectId())
                && job.campaignId().equals(op.campaignId()) && job.jobId().equals(op.jobId())
                && job.deviceId().equals(op.deviceId()) && job.attemptNo() == op.attemptNo()
                && job.credentialVersion() == op.credentialVersion() && op.authorizationId().equals(job.authorizationId())
                && op.manifestSha256().equals(job.manifestSha256());
    }

    /** 预期资格不满足只退避，不在事务中发送网络。 */
    private Optional<Prepared> defer(OtaRollbackDeliveryRepository.Claim claim, String reason) {
        if (delivery.deferIneligible(claim, reason)) record(claim.envelope(), reason);
        return Optional.empty();
    }

    /** 迟到真实外部观察保留，只有当前租约能结算交付头。 */
    @Transactional(timeout=5)
    public boolean complete(UUID transportId,UUID token,OtaRollbackPublisher.Result result) {
        background(); if(transportId==null||token==null||result==null||result.outcome()==null) return false;
        var transport=delivery.authoritativeTransport(transportId,token).orElse(null); if(transport==null) return false;
        var query=transport.envelope(); rls.establish(query.tenantId(),query.projectId());
        boolean active=lifecycle.lockActiveForWrite(query.tenantId(),query.projectId());
        if(active) { runtime.controlLock(query.tenantId(),query.projectId()); runtime.lockRuntime(query.projectId(),query.campaignId()); }
        boolean observed=delivery.recordObservation(transport,result.outcome().name(),result.status(),
                result.outcome()==OtaRollbackPublisher.Outcome.BROKER_ACCEPTED?null:result.reason());
        if(active) delivery.authoritativeClaim(query.id(),transport.deliveryLeaseToken()).ifPresent(c->delivery.settleCurrent(c,transport.id()));
        if(observed) record(query,"TRANSPORT_"+result.outcome().name());
        return observed;
    }

    /** 固定系统审计不携带规范正文或凭据。 */
    private void record(OtaRollbackDeliveryRepository.Envelope envelope, String reason) {
        audit.record(new AuditLogEntry(envelope.tenantId(), envelope.projectId(), null, "ota_device_job", envelope.jobId(),
                "ota.rollback.delivery", Map.of("actorKind", "SYSTEM", "eventId", envelope.id().toString(), "reason", reason)));
    }
    /** 后台不继承管理账号权限。 */
    private static void background() {
        if (TenantContext.current().isPresent()) throw new IllegalStateException("OTA回退交付不能继承管理账号");
    }
    /** 固定持久事实损坏分类。 */
    private static IllegalStateException corrupt() { return new IllegalStateException("OTA回退交付原规范事实不一致"); }
    /** 已提交物理发送能力，不是新的逻辑操作。
     * @param transport 精确发送预留
     * @param route 同事务冻结的MQTT身份和配置空间
     * @param leaseUntil 原发送租约截止
     */
    public record Prepared(OtaRollbackDeliveryRepository.Transport transport, DeviceMqttDownlinkRoute route, Instant leaseUntil) {
        /** 原设备所见项目路径。 */ public String projectKey() { return route.projectKey(); }
        /** 原设备所见设备路径。 */ public String deviceKey() { return route.deviceKey(); }
    }
}
