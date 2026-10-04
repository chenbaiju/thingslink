package com.things.link.ota.application;

import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.device.application.DeviceMqttDownlinkRoute;
import com.things.link.device.application.DeviceMqttDownlinkRoutePort;
import com.things.link.device.application.OtaDeviceNotificationRoutePort;
import com.things.link.device.application.OtaDeviceNotificationRoute;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaDownloadAuthorizationRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.storage.VersionedPrivateObjectStorage;
import com.things.link.support.tenant.DataPlaneDatabase;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ADR0129授权的短事务围栏；签址与MQTT网络均在事务外，秘密只封存在独立AEAD信封。 */
@Service
@DataPlaneDatabase
public class OtaDownloadAuthorizationService {
    /** 一次签址和独立传输观察能力。 */ private final OtaDownloadAuthorizationRepository repository;
    /** 统一项目控制锁及活动图。 */ private final OtaCampaignRuntimeRepository runtime;
    /** 项目持续许可。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 真实连接双轴。 */ private final TransactionLocalRlsScope rls;
    /** 当前设备路由。 */ private final OtaDeviceNotificationRoutePort routes;
    /** 发送许可事务内冻结当前配置命名空间。 */ private final DeviceMqttDownlinkRoutePort mqttRoutes;
    /** 最终凭据有效期复核。 */ private final OtaDeviceIdentityPort devices;
    /** 当前完整资格，不继承管理账号。 */ private final OtaRuntimeQualification qualification;
    /** 每次新签址/发送仍须保持原执行来源。 */ private final OtaExecutionOriginGuard origins;
    /** 项目公开路由应用端口。 */ private final ProjectService projects;
    /** 缺失或歧义存储不得预留签址。 */ private final ObjectProvider<VersionedPrivateObjectStorage> storages;
    /** 独立响应密钥环。 */ private final OtaDownloadResponseCipher cipher;
    /** 事务内仅检查传输配置，不执行网络。 */ private final OtaDownloadResponsePublisher publisher;
    /** 同事务SYSTEM审计。 */ private final AuditLogService audit;
    /** 默认HTTPS的严格秘密响应合同。 */ private final OtaDownloadResponseCodec codec;

    /** 注入独立事实端口，不装配默认成功签名器或响应密钥。 */
    public OtaDownloadAuthorizationService(OtaDownloadAuthorizationRepository repository,
            OtaCampaignRuntimeRepository runtime, ProjectLifecycleAccessService lifecycle, TransactionLocalRlsScope rls,
            OtaDeviceNotificationRoutePort routes, OtaDeviceIdentityPort devices, OtaRuntimeQualification qualification,
            ProjectService projects, ObjectProvider<VersionedPrivateObjectStorage> storages,
            OtaDownloadResponseCipher cipher, OtaDownloadResponsePublisher publisher, AuditLogService audit, OtaExecutionOriginGuard origins, DeviceMqttDownlinkRoutePort mqttRoutes,
            @Value("${things-link.ota.download.allow-insecure-loopback:false}") boolean allowInsecureLoopback) {
        this.repository = repository; this.runtime = runtime; this.lifecycle = lifecycle; this.rls = rls;
        this.routes = routes; this.devices = devices; this.qualification = qualification; this.projects = projects;
        this.storages = storages; this.cipher = cipher; this.publisher = publisher; this.audit = audit; this.origins = origins;
        this.codec = new OtaDownloadResponseCodec(allowInsecureLoopback); this.mqttRoutes = mqttRoutes;
    }

    /** 可信单条领取只取得范围能力，不建立虚构账号。 */
    @Transactional(timeout = 5)
    public Optional<OtaDownloadAuthorizationRepository.Claim> claimOne() {
        requireBackground();
        return repository.claimOne();
    }

    /** 签址前完整资格与唯一预算提交后，调用方才可以执行一次物理签址。 */
    @Transactional(timeout = 5)
    public Optional<Signing> prepareSigning(UUID authorizationId, UUID token) {
        var claim = current(authorizationId, token);
        if (claim == null) return Optional.empty();
        // D-161：已封存且已被接受的同一授权可以在原冻结响应窗口内再签一次址（同身份、同窗口、不新建预算）。
        // 这条分支只改变「谁可以进入SIGNING」，不改变任何身份字段、期限、传输预算或并发槽；
        // 窗口是否仍开放由 ota_download_reserve_signing 在同一事务内最终裁决。
        boolean reissue = "BROKER_ACCEPTED".equals(claim.status());
        if ("SIGNING".equals(claim.status()) || "IN_FLIGHT".equals(claim.status())) {
            if (repository.recoverExpired(claim)) record(claim, "RECOVERED_UNKNOWN");
            return Optional.empty();
        }
        if (!"WAITING".equals(claim.status()) && !reissue) return Optional.empty();
        if (!reissue && !runtime.currentTime().isBefore(claim.request().originalDeadline())) {
            if (repository.exhaustDue(claim, "DOWNLOAD_REQUEST_EXPIRED")) record(claim, "EXHAUSTED");
            return Optional.empty();
        }
        var route = eligible(claim);
        if (route == null) return Optional.empty();
        if (!cipher.configured() || !publisher.configured() || storages.orderedStream().limit(2).count() != 1) {
            pause(claim, "DOWNLOAD_DEPENDENCY_UNAVAILABLE");
            return Optional.empty();
        }
        var request = claim.request();
        var release = qualification.validateCurrent(request.tenantId(), request.projectId(), request.firmwareId());
        if (!sameRelease(claim, release) || !devices.credentialValid(identity(claim))) return Optional.empty();
        var reserved = repository.reserveSigning(claim, claim.jobRevision());
        if (reserved.isEmpty()) {
            // 最终SQL可在跨秒边界耗尽或额度不足时退避；清租约后只能在已建立RLS内读取结果。
            repository.findCurrent(claim.authorizationId()).filter(after -> after.revision() > claim.revision())
                    .ifPresent(after -> {
                        if ("EXHAUSTED".equals(after.status())) record(after, "EXHAUSTED");
                        else if ("WAITING".equals(after.status())) record(after, "BUDGET_DEFERRED");
                    });
            return Optional.empty();
        }
        var value = reserved.orElseThrow();
        record(value, "SIGNING_RESERVED");
        var upload = release.upload();
        return Optional.of(new Signing(value, new VersionedPrivateObjectStorage.VersionRef(
                upload.bucket(), upload.objectKey(), upload.versionId())));
    }

    /** 地址从未先于此事务提交发出；失租、暂停或当前资格变化只丢弃临时地址。 */
    @Transactional(timeout = 5)
    public boolean seal(UUID authorizationId, UUID token, URI url) {
        var claim = current(authorizationId, token);
        if (claim == null || !"SIGNING".equals(claim.status()) || !token.equals(claim.signingLeaseToken())) return false;
        if (!runtime.currentTime().isBefore(claim.responseExpiresAt())) {
            if (repository.signingUnknown(claim, "DOWNLOAD_SIGNING_EXPIRED")) record(claim, "SIGNING_UNKNOWN");
            return false;
        }
        var route = eligible(claim);
        if (route == null) return false;
        var request = claim.request();
        var release = qualification.validateCurrent(request.tenantId(), request.projectId(), request.firmwareId());
        if (!sameRelease(claim, release)) return false;
        var project = projects.requireRoutingContext(request.projectId());
        if (!request.tenantId().equals(project.tenantId())) throw new IllegalStateException("OTA项目路由范围不符");
        byte[] plaintext = null;
        OtaDownloadResponseCipher.Envelope envelope;
        String topic;
        try {
            topic = OtaDownloadResponseCodec.topic(project.projectKey(), route.deviceKey());
            var value = release.release();
            plaintext = codec.encode(new OtaDownloadResponseCodec.Response("tc-ota-download-response/v1",
                    claim.authorizationId(), request.requestId(), request.campaignId(), request.jobId(),
                    request.firmwareId(), request.attemptNo(), request.manifestSha256(), value.canonicalManifest(),
                    value.signature(), value.spki(), release.grant().binding().profile(),
                    release.grant().binding().fingerprint(), url, claim.responseExpiresAt()));
            envelope = cipher.encrypt(binding(claim), plaintext);
        } catch (IllegalArgumentException invalid) {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            pause(claim, "DOWNLOAD_RESPONSE_INTEGRITY");
            return false;
        }
        try {
            if (!devices.credentialValid(identity(claim))) return false;
            boolean saved = repository.seal(claim, claim.jobRevision(), envelope.keyVersion(), envelope.nonce(),
                    envelope.ciphertext(), OtaTrustBundleCodec.sha256(plaintext), topic);
            if (saved) record(claim, "SEALED");
            return saved;
        } finally { Arrays.fill(plaintext, (byte) 0); }
    }

    /** 签址异常不传播供应商正文，不以新尝试掩盖一次物理操作的未知结果。 */
    @Transactional(timeout = 5)
    public boolean signingUnknown(UUID authorizationId, UUID token, String reason) {
        var claim = current(authorizationId, token);
        if (claim == null || !"SIGNING".equals(claim.status()) || !token.equals(claim.signingLeaseToken())) return false;
        // 外部传入的reason不进入持久证据，固定类别避免异常链携带URL。
        boolean saved = repository.signingUnknown(claim, "DOWNLOAD_SIGNING_UNKNOWN");
        if (saved) record(claim, "SIGNING_UNKNOWN");
        return saved;
    }

    /** 每次发送重新锁定当前资格；只解密原封存正文，不重新签址或续期。 */
    @Transactional(timeout = 5)
    public Optional<Sending> prepareSend(UUID authorizationId, UUID token) {
        var claim = current(authorizationId, token);
        if (claim == null || !"SEALED".equals(claim.status())) return Optional.empty();
        if (!runtime.currentTime().isBefore(claim.responseExpiresAt()) || claim.transportCount() >= 3) {
            if (repository.exhaustDue(claim, "DOWNLOAD_RESPONSE_EXHAUSTED")) record(claim, "EXHAUSTED");
            return Optional.empty();
        }
        var route = eligible(claim);
        if (route == null) return Optional.empty();
        if (!publisher.configured() || !cipher.configured()) {
            pause(claim, "DOWNLOAD_DEPENDENCY_UNAVAILABLE");
            return Optional.empty();
        }
        var request = claim.request();
        var mqttRoute = mqttRoutes.lockCurrent(request.tenantId(), request.projectId(), request.deviceId()).orElse(null);
        if (mqttRoute == null) {
            if (repository.deferIneligible(claim, "CURRENT_DEVICE_INELIGIBLE")) record(claim, "DEFERRED");
            return Optional.empty();
        }
        var project = projects.requireRoutingContext(request.projectId());
        if (!request.tenantId().equals(project.tenantId())) throw new IllegalStateException("OTA项目路由范围不符");
        if (!project.projectKey().equals(mqttRoute.projectKey()) || !route.deviceKey().equals(mqttRoute.deviceKey())) {
            throw new IllegalStateException("OTA下载回复MQTT路由不匹配");
        }
        byte[] plaintext = null;
        try {
            if (!OtaDownloadResponseCodec.topic(project.projectKey(), route.deviceKey()).equals(claim.topic())) {
                throw new IllegalArgumentException();
            }
            plaintext = cipher.decrypt(binding(claim), new OtaDownloadResponseCipher.Envelope(
                    claim.keyVersion(), claim.nonce(), claim.ciphertext()));
            var response = codec.decode(plaintext);
            if (!claim.plaintextSha256().equals(OtaTrustBundleCodec.sha256(plaintext))
                    || !Arrays.equals(plaintext, codec.encode(response))
                    || !claim.authorizationId().equals(response.authorizationId())
                    || !request.requestId().equals(response.requestId()) || !request.jobId().equals(response.jobId())
                    || !request.campaignId().equals(response.campaignId()) || !request.firmwareId().equals(response.firmwareId())
                    || request.attemptNo() != response.attemptNo() || !request.manifestSha256().equals(response.manifestSha256())
                    || !claim.responseExpiresAt().equals(response.expiresAt())) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            pause(claim, "DOWNLOAD_RESPONSE_INTEGRITY");
            return Optional.empty();
        }
        try {
            if (!devices.credentialValid(identity(claim))) return Optional.empty();
            var transport = repository.reserveSend(claim);
            if (transport.isEmpty()) return Optional.empty();
            record(claim, "TRANSPORT_RESERVED");
            return Optional.of(new Sending(transport.orElseThrow(), mqttRoute,
                    plaintext, claim.responseExpiresAt(), claim.leaseUntil()));
        } finally { Arrays.fill(plaintext, (byte) 0); }
    }

    /** 迟到观察独立追加，只有当前仍运行的有效授权能力才可采用。 */
    @Transactional(timeout = 5)
    public boolean complete(UUID transportId, UUID reservationToken, OtaDownloadResponsePublisher.Result result) {
        requireBackground();
        if (transportId == null || reservationToken == null || result == null || result.outcome() == null) return false;
        var located = repository.authoritativeTransport(transportId, reservationToken);
        if (located.isEmpty()) return false;
        var transport = located.orElseThrow();
        rls.establish(transport.tenantId(), transport.projectId());
        boolean active = lifecycle.lockActiveForWrite(transport.tenantId(), transport.projectId());
        boolean running = false;
        if (active) {
            runtime.controlLock(transport.tenantId(), transport.projectId());
            var graph = runtime.lockRuntime(transport.projectId(), transport.campaignId()).orElse(null);
            running = graph != null && "RUNNING".equals(graph.campaign().status());
        }
        if (running) OtaTransportRetryAudit.watch(runtime, audit, transport.tenantId(), transport.projectId(),
                transport.jobId(), transport.authorizationId());
        boolean observed = repository.recordObservation(transport, result.outcome().name(), result.status(),
                result.outcome() == OtaDownloadResponsePublisher.Outcome.BROKER_ACCEPTED ? null : result.reason());
        if (running) repository.authoritativeClaim(transport.authorizationId(), transport.authorizationLeaseToken())
                .ifPresent(claim -> repository.settleCurrent(claim, transportId));
        if (observed) audit.record(new AuditLogEntry(transport.tenantId(), transport.projectId(), null,
                "ota_download_transport", transport.id(), "ota.download.observed",
                Map.of("actorKind", "SYSTEM", "outcome", result.outcome().name())));
        return observed;
    }

    /** 先以可信能力取scope，再项目门控及完整图锁，最后重新读取当前修订。 */
    private OtaDownloadAuthorizationRepository.Claim current(UUID id, UUID token) {
        requireBackground();
        if (id == null || token == null) return null;
        var found = repository.authoritativeClaim(id, token);
        if (found.isEmpty()) return null;
        var request = found.orElseThrow().request();
        rls.establish(request.tenantId(), request.projectId());
        if (!lifecycle.lockActiveForWrite(request.tenantId(), request.projectId())) return null;
        runtime.controlLock(request.tenantId(), request.projectId());
        var graph = runtime.lockRuntime(request.projectId(), request.campaignId()).orElse(null);
        if (graph == null) return null;
        OtaTransportRetryAudit.watch(runtime, audit, request.tenantId(), request.projectId(), request.jobId(), id);
        return repository.authoritativeClaim(id, token).orElse(null);
    }

    /** 锁序保持类型/设备在发布图前；活动暂停时不再借旧资格取得新能力。 */
    private OtaDeviceNotificationRoute eligible(OtaDownloadAuthorizationRepository.Claim claim) {
        var request = claim.request();
        var graph = runtime.read(request.projectId(), request.campaignId()).orElse(null);
        if (graph == null || !"RUNNING".equals(graph.campaign().status())) return null;
        var route = routes.lockCurrent(identity(claim)).orElse(null);
        var outcome = qualification.check(request.tenantId(), request.projectId(), request.deviceId(), request.firmwareId());
        if (!devices.credentialValid(identity(claim))) return null;
        if (outcome.disposition() == OtaRuntimeQualification.Disposition.SECURITY) {
            pause(claim, outcome.reason());
            return null;
        }
        if (route == null || outcome.disposition() != OtaRuntimeQualification.Disposition.ELIGIBLE
                || outcome.credentialVersion() == null || outcome.credentialVersion() != request.credentialVersion()) {
            if (repository.deferIneligible(claim, "CURRENT_DEVICE_INELIGIBLE")) record(claim, "DEFERRED");
            return null;
        }
        String sourceReason = origins.rejection(request.projectId(), request.jobId(), request.attemptNo(), request.deviceId(),
                request.credentialVersion(), outcome.reportRevision(), outcome.reportHash());
        if (!devices.credentialValid(identity(claim))) return null;
        if (sourceReason != null) {
            pause(claim, sourceReason);
            return null;
        }
        return route;
    }

    /** 冻结发布物身份校验不允许当前图替换原请求清单。 */
    private boolean sameRelease(OtaDownloadAuthorizationRepository.Claim claim, OtaReleaseDownloadService.ReleaseFacts release) {
        if (claim.request().manifestSha256().equals(OtaTrustBundleCodec.sha256(release.release().canonicalManifest()))) return true;
        pause(claim, "DOWNLOAD_RELEASE_IDENTITY_CHANGED");
        return false;
    }
    /** AAD全部来自不可变receipt及固定授权期限。 */
    private static OtaDownloadResponseCipher.Binding binding(OtaDownloadAuthorizationRepository.Claim claim) {
        var request = claim.request();
        return new OtaDownloadResponseCipher.Binding(request.tenantId(), request.projectId(), request.deviceId(),
                request.credentialVersion(), request.requestId(), request.jobId(), claim.authorizationId(),
                request.attemptNo(), request.manifestSha256(), claim.responseExpiresAt(), "tc-ota-download-response/v1");
    }
    /** 原始认证代际不能由当前device行替换。 */
    private static AuthenticatedDeviceIdentity identity(OtaDownloadAuthorizationRepository.Claim claim) {
        var request = claim.request();
        return new AuthenticatedDeviceIdentity(request.tenantId(), request.projectId(), request.deviceId(), request.credentialVersion());
    }
    /** 只有精确当前能力才能关闭该活动新授权。 */
    private void pause(OtaDownloadAuthorizationRepository.Claim claim, String reason) {
        if (repository.pauseSecurity(claim, reason)) record(claim, "SECURITY_PAUSED");
    }
    /** 固定系统分类，不存URL、密文或异常正文。 */
    private void record(OtaDownloadAuthorizationRepository.Claim claim, String action) {
        var request = claim.request();
        audit.record(new AuditLogEntry(request.tenantId(), request.projectId(), null, "ota_download_authorization",
                claim.authorizationId(), "ota.download.authorization", Map.of("actorKind", "SYSTEM", "action", action)));
    }
    /** 后台入口拒绝所有管理账号继承。 */
    private static void requireBackground() {
        if (TenantContext.current().isPresent()) throw new IllegalStateException("OTA下载授权不能继承管理账号");
    }
    /** 已持久唯一签址预算与固定私有版本，不代表地址已发出。 */
    public record Signing(OtaDownloadAuthorizationRepository.Claim claim, VersionedPrivateObjectStorage.VersionRef version) {
        /** 固定响应期限。 */ public Instant expiresAt() { return claim.responseExpiresAt(); }
        /** 原物理能力期限。 */ public Instant leaseUntil() { return claim.leaseUntil(); }
    }
    /** 只交给当前物理Worker的临时明文；不允许默认字符串泄露。 */
    public record Sending(OtaDownloadAuthorizationRepository.Transport transport, DeviceMqttDownlinkRoute route,
            byte[] canonical, Instant expiresAt, Instant leaseUntil) {
        /** 原响应公开项目路径。 */ public String projectKey() { return route.projectKey(); }
        /** 原响应公开设备路径。 */ public String deviceKey() { return route.deviceKey(); }
        /** 临时正文防御复制。 */ public Sending { canonical = canonical.clone(); }
        /** 每个调用者取得独立秘密字节。 */ @Override public byte[] canonical() { return canonical.clone(); }
        /** 不打印响应与私有路由。 */ @Override public String toString() { return "OtaDownloadSending[redacted]"; }
    }
}
