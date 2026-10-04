package com.things.link.ota.application;

import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.device.application.OtaDeviceTargetSnapshotPort;
import com.things.link.device.application.OtaModelSnapshotPort;
import com.things.link.ota.domain.OtaDeviceReportRepository;
import com.things.link.ota.domain.OtaFirmwareRepository;
import com.things.link.ota.domain.OtaTrustRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 租约调用者专用同事务资格，自己不授予scope、不创建账号、不执行网络。 */
@Component
public class OtaRuntimeQualification {
    /** 当前物理事务及RLS验真。 */ private final JdbcTemplate jdbc;
    /** 先锁类型及设备，即使没有报告也遵循统一锁序。 */ private final OtaDeviceTargetSnapshotPort targets;
    /** 当前代际、绑定与有效凭据。 */ private final OtaDeviceIdentityPort devices;
    /** 当前规范报告。 */ private final OtaDeviceReportRepository reports;
    /** 未锁定位与最终发布图的固件事实。 */ private final OtaFirmwareRepository firmwares;
    /** 当前完整受控基线。 */ private final OtaTypeBaselineService baselines;
    /** 无账号发布图核验。 */ private final OtaReleaseDownloadService releases;
    /** 当前被验真的包。 */ private final OtaTrustRepository trusts;
    /** 目标模型权威摘要。 */ private final OtaModelSnapshotPort models;
    /** 新提交下限先于旧报告参与每次新资格。 */ private final OtaKnownSecurityFloor floors;
    /** 历史方向冲突形成设备级永久安全隔离。 */ private final OtaRollbackDirectionGuard directions;
    /** 规范报告恢复。 */ private final OtaDeviceReportCodec codec = new OtaDeviceReportCodec();
    /** 纯四方兼容计算。 */ private final OtaDeviceEligibilityEvaluator evaluator = new OtaDeviceEligibilityEvaluator();

    /** 所有依赖只共享调用方事务，不建立后台系统账号。 */
    public OtaRuntimeQualification(JdbcTemplate jdbc, OtaDeviceTargetSnapshotPort targets, OtaDeviceIdentityPort devices,
            OtaDeviceReportRepository reports, OtaFirmwareRepository firmwares, OtaTypeBaselineService baselines,
            OtaReleaseDownloadService releases, OtaTrustRepository trusts, OtaModelSnapshotPort models, OtaKnownSecurityFloor floors, OtaRollbackDirectionGuard directions) {
        this.jdbc = jdbc;
        this.targets = targets;
        this.devices = devices;
        this.reports = reports;
        this.firmwares = firmwares;
        this.baselines = baselines;
        this.releases = releases;
        this.trusts = trusts;
        this.models = models; this.floors = floors; this.directions = directions;
    }

    /** 当前项目控制锁和真实lease须由调用者先验真；安全检查优先于普通跳过返回。 */
    Outcome check(UUID tenant, UUID project, UUID deviceId, UUID firmwareId) {
        OtaRuntimeScope scope = OtaRuntimeScope.require(jdbc, tenant, project);
        if (!directions.deviceAllowed(deviceId)) return outcome(Disposition.SECURITY, "DEVICE_ATOMIC_DIRECTION_CONFLICT", null, null, null);
        var located = firmwares.find(project, firmwareId, false).orElse(null);
        if (located == null) return outcome(Disposition.SECURITY, "FIRMWARE_UNAVAILABLE", null, null, null);
        var target = targets.lockExplicit(tenant, project, located.deviceTypeId(), List.of(deviceId)).orElse(List.of());
        AuthenticatedDeviceIdentity identity = target.size() == 1
                ? new AuthenticatedDeviceIdentity(tenant, project, deviceId, target.getFirst().credentialVersion()) : null;
        var device = identity == null ? null : devices.lockCurrent(identity).orElse(null);
        var report = reports.find(project, deviceId, false, true).orElse(null);
        Long revision = report == null ? null : report.revision();
        String hash = report == null ? null : report.reportHash();
        Long version = identity == null ? null : identity.credentialVersion();
        OtaReleaseDownloadService.ReleaseFacts release;
        OtaTypeBaselineService.Snapshot baseline;
        try {
            baseline = baselines.lockRuntime(scope, located.deviceTypeId());
            release = releases.lockRuntime(scope, firmwareId);
        } catch (BusinessException | IllegalArgumentException unsafe) {
            return outcome(Disposition.SECURITY, "RELEASE_OR_POLICY_UNAVAILABLE", version, revision, hash);
        }
        if (!located.deviceTypeId().equals(release.firmware().deviceTypeId())) {
            return outcome(Disposition.SECURITY, "RELEASE_IDENTITY_CHANGED", version, revision, hash);
        }
        var configured = baseline.decoded().value();
        if (!configured.rootFingerprint().equals(release.grant().rootFingerprint())
                || !configured.trustDomain().equals(release.grant().binding().trustDomain())
                || !configured.productKey().equals(release.firmware().productKey())
                || !configured.propertyProfile().equals(release.firmware().schemaProfile())) {
            return outcome(Disposition.SECURITY, "BASELINE_RELEASE_MISMATCH", version, revision, hash);
        }
        if (device == null) return outcome(Disposition.SKIP, "IDENTITY_CHANGED", version, revision, hash);
        if (report == null) return outcome(Disposition.SKIP, "REPORT_MISSING", version, null, null);
        if (report.credentialVersion() != identity.credentialVersion()) {
            return outcome(Disposition.SKIP, "IDENTITY_CHANGED", version, revision, hash);
        }
        OtaDeviceReportCodec.Decoded decoded;
        try {
            decoded = codec.decode(report.canonical());
            if (!decoded.sha256().equals(hash) || !Arrays.equals(decoded.canonical(), report.canonical())
                    || decoded.value().reportSequence() != report.reportSequence()
                    || decoded.value().committedSecurityVersion() != report.committedSecurityVersion()) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException unsafe) {
            return outcome(Disposition.SECURITY, "REPORT_INTEGRITY", version, revision, hash);
        }
        if (!floors.accepts(tenant, project, deviceId, decoded.value()))
            return outcome(Disposition.SECURITY, "COMMITTED_SECURITY_FLOOR_MISMATCH", version, revision, hash);
        if (!OtaDeviceReportIngestionService.matchesModel(device, decoded.value())) {
            return outcome(Disposition.SKIP, "IDENTITY_CHANGED", version, revision, hash);
        }
        var firmware = release.firmware();
        var model = models.find(project, device.deviceTypeId(), firmware.thingModelVersionId()).orElse(null);
        if (model == null || !Objects.equals(model.schemaDigestAlgorithm(), firmware.schemaDigestAlgorithm())
                || !Objects.equals(model.schemaDigest(), firmware.schemaDigest())
                || !Objects.equals(model.schemaProfile(), firmware.schemaProfile())) {
            return outcome(Disposition.SECURITY, "TARGET_MODEL_INTEGRITY", version, revision, hash);
        }
        var trust = trusts.find(project, release.grant().binding().trustDomain(), false, true)
                .orElseThrow(() -> new IllegalStateException("持锁当前信任包不存在"));
        if (trust.bundleVersion() != release.grant().bundleVersion()
                || !trust.rootFingerprint().equals(release.grant().rootFingerprint())) {
            return outcome(Disposition.SECURITY, "TRUST_INTEGRITY", version, revision, hash);
        }
        var reason = evaluator.evaluate(decoded, baseline.decoded(), release.release().canonicalManifest(),
                trust.bundleVersion(), trust.bundleSha256(), trust.rootFingerprint(), trust.trustDomain());
        if (!devices.credentialValid(identity)) reason = OtaDeviceEligibilityEvaluator.Reason.IDENTITY_CHANGED;
        Instant checkedAt = Instant.now();
        if (!OtaDeviceReportIngestionService.fresh(report.brokerReceivedAt(), checkedAt)) {
            reason = OtaDeviceEligibilityEvaluator.Reason.REPORT_STALE;
        }
        if (reason == OtaDeviceEligibilityEvaluator.Reason.ELIGIBLE && !decoded.value().supportsAbSlots()) {
            return new Outcome(Disposition.SKIP, "SAFE_SLOT_STRATEGY_UNSUPPORTED", version, revision, hash, checkedAt);
        }
        return new Outcome(reason == OtaDeviceEligibilityEvaluator.Reason.ELIGIBLE ? Disposition.ELIGIBLE : Disposition.SKIP,
                reason.name(), version, revision, hash, checkedAt);
    }

    /** 启动/恢复只验证基线与发布，不取设备或报告锁；调用方已持项目控制锁。 */
    OtaReleaseDownloadService.ReleaseFacts validateCurrent(UUID tenant, UUID project, UUID firmwareId) {
        OtaRuntimeScope scope = OtaRuntimeScope.require(jdbc, tenant, project);
        var located = firmwares.find(project, firmwareId, false)
                .orElseThrow(() -> new IllegalArgumentException("当前OTA固件不存在"));
        var baseline = baselines.lockRuntime(scope, located.deviceTypeId());
        var release = releases.lockRuntime(scope, firmwareId);
        var configured = baseline.decoded().value();
        if (!configured.rootFingerprint().equals(release.grant().rootFingerprint())
                || !configured.trustDomain().equals(release.grant().binding().trustDomain())
                || !configured.productKey().equals(release.firmware().productKey())
                || !configured.propertyProfile().equals(release.firmware().schemaProfile())) {
            throw new IllegalArgumentException("当前OTA基线与发布信任不匹配");
        }
        if (!located.deviceTypeId().equals(release.firmware().deviceTypeId())) {
            throw new IllegalArgumentException("当前OTA固件类型变化");
        }
        return release;
    }

    /** 构造不携带外部异常正文的稳定决定。 */
    private static Outcome outcome(Disposition disposition, String reason, Long version, Long revision, String hash) {
        return new Outcome(disposition, reason, version, revision, hash, Instant.now());
    }
    /** 准入只允许三种处置。 */
    enum Disposition {
        /** 可提交通知意图。 */ ELIGIBLE,
        /** 当前设备普通不兼容。 */ SKIP,
        /** 活动应安全暂停。 */ SECURITY
    }
    /** 当前同事务结果，不是可转交给其他事务的授权。
     * @param disposition 准入处置
     * @param reason 固定安全原因
     * @param credentialVersion 当前真实代际，可空
     * @param reportRevision 报告修订，可空
     * @param reportHash 报告摘要，可空
     * @param checkedAt 检查时间
     */
    record Outcome(Disposition disposition, String reason, Long credentialVersion, Long reportRevision,
                   String reportHash, Instant checkedAt) { }
}
