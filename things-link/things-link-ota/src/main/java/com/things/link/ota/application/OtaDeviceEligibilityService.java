package com.things.link.ota.application;

import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.device.application.OtaModelSnapshotPort;
import com.things.link.ota.application.OtaDeviceEligibilityEvaluator.Reason;
import com.things.link.ota.domain.OtaDeviceReportRepository;
import com.things.link.ota.domain.OtaFirmwareRepository;
import com.things.link.ota.domain.OtaDeviceReportErrorCode;
import com.things.link.ota.domain.OtaTrustRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理端当前资格查询；只返回本次事务快照，不签发可缓存的设备升级授权。 */
@Service
public class OtaDeviceEligibilityService {
    /** 当前真实管理成员。 */ private final ProjectService projects;
    /** 项目ACTIVE锁。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 设备身份与模型共享锁。 */ private final OtaDeviceIdentityPort devices;
    /** 已接纳当前报告。 */ private final OtaDeviceReportRepository reports;
    /** 当前受控类型基线。 */ private final OtaTypeBaselineService baselines;
    /** READY状态快速检查与锁。 */ private final OtaFirmwareRepository firmwares;
    /** 完整发布图及当前trust验签；必须加入本事务。 */ private final OtaReleaseDownloadService releases;
    /** 已被release入口验真的同一持锁trust包。 */ private final OtaTrustRepository trusts;
    /** 目标模型权威不可变事实。 */ private final OtaModelSnapshotPort models;
    /** 严格报告恢复，不信数据库中仅有摘要的投影。 */
    private final OtaDeviceReportCodec codec = new OtaDeviceReportCodec();
    /** 已知设备提交下限不受旧报告替换。 */ private final OtaKnownSecurityFloor floors;
    /** 四方合同交集不执行网络。 */
    private final OtaDeviceEligibilityEvaluator evaluator = new OtaDeviceEligibilityEvaluator();

    /** 资格各依赖均明确持锁或不可变，不使用远程签名调用。 */
    public OtaDeviceEligibilityService(ProjectService projects, ProjectLifecycleAccessService lifecycle,
            OtaDeviceIdentityPort devices, OtaDeviceReportRepository reports, OtaTypeBaselineService baselines,
            OtaFirmwareRepository firmwares, OtaReleaseDownloadService releases, OtaTrustRepository trusts,
            OtaModelSnapshotPort models, OtaKnownSecurityFloor floors) {
        this.projects = projects; this.lifecycle = lifecycle; this.devices = devices; this.reports = reports;
        this.baselines = baselines; this.firmwares = firmwares; this.releases = releases;
        this.trusts = trusts; this.models = models; this.floors = floors;
    }

    /** 报告缺失/失效返回明确不合格，基础设施错误原样失败，不伪装普通不兼容。 */
    @Transactional(timeout = 5)
    public Result check(UUID projectId, UUID deviceId, UUID firmwareId) {
        manage(projectId);
        UUID tenant = projects.requireProjectTenant(projectId);
        lifecycle.requireActiveForWrite(tenant, projectId);
        manage(projectId);
        var observed = reports.find(projectId, deviceId, false, false).orElse(null);
        if (observed == null) return result(Reason.REPORT_MISSING, null);
        var identity = new AuthenticatedDeviceIdentity(tenant, projectId, deviceId, observed.credentialVersion());
        var device = devices.lockCurrent(identity).orElse(null);
        if (device == null) return result(Reason.IDENTITY_CHANGED, observed.revision());
        var current = reports.find(projectId, deviceId, false, true).orElse(null);
        if (current == null || current.credentialVersion() != identity.credentialVersion()) {
            return result(Reason.IDENTITY_CHANGED, observed.revision());
        }
        OtaDeviceReportCodec.Decoded decoded;
        try {
            decoded = codec.decode(current.canonical());
        } catch (IllegalArgumentException corrupted) {
            throw new IllegalStateException("OTA报告持久正文不满足已冻结合同", corrupted);
        }
        if (!decoded.sha256().equals(current.reportHash())
                || decoded.value().reportSequence() != current.reportSequence()
                || decoded.value().committedSecurityVersion() != current.committedSecurityVersion()) {
            throw new IllegalStateException("OTA报告持久投影与规范事实不一致");
        }
        if (!floors.accepts(tenant, projectId, deviceId, decoded.value())) return result(Reason.INCOMPATIBLE, current.revision());
        if (!OtaDeviceReportIngestionService.matchesModel(device, decoded.value())) {
            return result(Reason.IDENTITY_CHANGED, current.revision());
        }
        if (!OtaDeviceReportIngestionService.fresh(current.brokerReceivedAt(), Instant.now())) {
            return result(Reason.REPORT_STALE, current.revision());
        }
        var baseline = baselines.lockCurrent(tenant, projectId, device.deviceTypeId());
        var firmware = firmwares.find(projectId, firmwareId, true).orElse(null);
        if (firmware == null || !"READY".equals(firmware.status())
                || !firmware.deviceTypeId().equals(device.deviceTypeId())) {
            return result(Reason.FIRMWARE_UNAVAILABLE, current.revision());
        }
        var release = releases.lockForQualification(projectId, firmwareId);
        var target = models.find(projectId, device.deviceTypeId(), firmware.thingModelVersionId()).orElse(null);
        if (target == null || !Objects.equals(target.schemaDigestAlgorithm(), firmware.schemaDigestAlgorithm())
                || !Objects.equals(target.schemaDigest(), firmware.schemaDigest())
                || !Objects.equals(target.schemaProfile(), firmware.schemaProfile())) {
            return result(Reason.INCOMPATIBLE, current.revision());
        }
        var trust = trusts.find(projectId, release.grant().binding().trustDomain(), false, true)
                .orElseThrow(() -> new IllegalStateException("已验真信任包在同一事务丢失"));
        if (trust.bundleVersion() != release.grant().bundleVersion()
                || !trust.rootFingerprint().equals(release.grant().rootFingerprint())) {
            throw new IllegalStateException("当前信任包与持锁验签事实不一致");
        }
        Reason evaluated = evaluator.evaluate(decoded, baseline.decoded(), release.release().canonicalManifest(),
                trust.bundleVersion(), trust.bundleSha256(), trust.rootFingerprint(), trust.trustDomain());
        if (!devices.credentialValid(identity)) {
            evaluated = Reason.IDENTITY_CHANGED;
        }
        Instant checkedAt = Instant.now();
        if (!OtaDeviceReportIngestionService.fresh(current.brokerReceivedAt(), checkedAt)) {
            evaluated = Reason.REPORT_STALE;
        }
        return new Result(evaluated == Reason.ELIGIBLE, evaluated, current.revision(), checkedAt);
    }

    /** 管理账号来自当前会话，不把设备ID或项目创建人当system actor。 */
    private void manage(UUID projectId) {
        ProjectRole role = projects.requireRoleInProject(projectId);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) {
            throw new BusinessException(OtaDeviceReportErrorCode.FORBIDDEN);
        }
    }

    /** 检查时间仅描述当前快照，不能作为未来派发凭证。 */
    private static Result result(Reason reason, Long revision) {
        return new Result(reason == Reason.ELIGIBLE, reason, revision, Instant.now());
    }

    /** 明确可空报告修订，不返回下载地址或设备密钥。
     * @param eligible 当前完整交集是否满足
     * @param reason 闭集原因
     * @param reportRevision 当前报告修订，缺失时为空
     * @param checkedAt 当前检查时间
     */
    public record Result(boolean eligible, Reason reason, Long reportRevision, Instant checkedAt) { }
}
