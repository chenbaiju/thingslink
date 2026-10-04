package com.things.link.ota.application;

import java.util.List;
import java.util.Map;

/** 纯四方能力交集；调用者负责持锁验真身份、模型、发布签名和时间，不产生派发授权。 */
public final class OtaDeviceEligibilityEvaluator {
    /** 管理端公开闭集结果，不以不合格掩盖基础设施异常。 */
    public enum Reason {
        /** 当前快照兼容。 */ ELIGIBLE,
        /** 没有报告。 */ REPORT_MISSING,
        /** 报告已过期，由服务时间围栏判断。 */ REPORT_STALE,
        /** 当前认证或绑定改变，由服务判断。 */ IDENTITY_CHANGED,
        /** 能力或安全边界不兼容。 */ INCOMPATIBLE,
        /** 尚未精确确认当前可信包。 */ TRUST_NOT_ACKNOWLEDGED,
        /** 发布不可用，由服务持锁判断。 */ FIRMWARE_UNAVAILABLE
    }
    /** 对已验真权威输入进行严格能力收窄；缺失依赖输入抛错，不能伪装设备不合格。 */
    public Reason evaluate(OtaDeviceReportCodec.Decoded decoded, OtaTypeBaselineCodec.Decoded baseline,
                           byte[] canonicalManifest, long currentBundleVersion, String currentBundleSha256,
                           String currentRootFingerprint, String currentTrustDomain) {
        if (decoded == null) return Reason.REPORT_MISSING;
        if (baseline == null || currentBundleVersion < 1 || currentBundleSha256 == null
                || currentRootFingerprint == null || currentTrustDomain == null) throw new IllegalArgumentException("OTA资格输入不完整");
        var r = decoded.value();
        var b = baseline.value();
        Map<String,Object> m = new OtaCanonicalJson().parseObject(new OtaManifestCodec().canonicalize(canonicalManifest));
        if (!r.rootFingerprint().equals(currentRootFingerprint) || !r.trustDomain().equals(currentTrustDomain)
                || r.trustBundleVersion() != currentBundleVersion || !r.trustBundleSha256().equals(currentBundleSha256)
                || currentBundleVersion < number(m,"minimumTrustBundleVersion")) return Reason.TRUST_NOT_ACKNOWLEDGED;
        if (!b.rootFingerprint().equals(currentRootFingerprint) || !b.trustDomain().equals(currentTrustDomain)
                || !currentTrustDomain.equals(m.get("trustDomain")) || !b.deviceTypeId().toString().equals(m.get("deviceTypeId"))
                || !b.productKey().equals(m.get("productKey"))) return Reason.INCOMPATIBLE;
        var hardware = OtaTrustBundleCodec.object(m.get("hardware"));
        var requirements = OtaTrustBundleCodec.object(m.get("requirements"));
        if (!r.hardware().model().equals(b.hardware().model()) || !r.hardware().model().equals(hardware.get("model"))
                || r.hardware().boardRevision() < b.hardware().boardRevisionMin() || r.hardware().boardRevision() > b.hardware().boardRevisionMax()
                || r.hardware().boardRevision() < number(hardware,"boardRevisionMin") || r.hardware().boardRevision() > number(hardware,"boardRevisionMax")
                || compare(r.bootloaderVersion(),b.bootloader().minimumVersion()) < 0 || compare(r.bootloaderVersion(),b.bootloader().maximumVersion()) > 0
                || compare(r.bootloaderVersion(),(String)m.get("bootloaderMinimumVersion")) < 0) return Reason.INCOMPATIBLE;
        if (r.maximumArtifactBytes() > b.maximumArtifactBytes() || r.availableRamBytes() > b.availableRamBytes()
                || r.availableFlashBytes() > b.availableFlashBytes() || r.protectedSecurityCounterBits() > b.protectedSecurityCounterBits()
                || r.supportsAbSlots() && !b.supportsAbSlots() || r.supportsRangeDownload() && !b.supportsRangeDownload()
                || r.supportsResumeDownload() && !b.supportsResumeDownload()
                || !b.signatureProfiles().containsAll(r.signatureProfiles())
                || !b.compressionAlgorithms().containsAll(r.compressionAlgorithms()) || !b.deltaModes().containsAll(r.deltaModes())
                || !b.propertyProfile().equals(r.propertyProfile())) return Reason.INCOMPATIBLE;
        long targetSecurity = number(m,"securityVersion");
        // NONE完整固件至少需要自身字节空间，manifest的额外Flash要求不能降低此物理下限。
        if (number(m, "artifactSize") > r.maximumArtifactBytes()
                || number(m, "artifactSize") > r.availableFlashBytes()
                || number(requirements, "minimumRamBytes") > r.availableRamBytes()
                || number(requirements,"minimumFlashBytes") > r.availableFlashBytes()
                || Boolean.TRUE.equals(requirements.get("requiresAbSlots")) && !r.supportsAbSlots()
                || Boolean.TRUE.equals(requirements.get("requiresRangeDownload")) && !r.supportsRangeDownload()
                || r.protectedSecurityCounterBits() == 0 || targetSecurity > (1L << r.protectedSecurityCounterBits()) - 1
                || targetSecurity < r.committedSecurityVersion() || r.currentSecurityVersion() != r.committedSecurityVersion()
                || !"HEALTHY".equals(r.bootState()) || !r.propertyProfile().equals(requirements.get("profile"))
                || r.signatureProfiles().stream().noneMatch(p -> p.value().equals(m.get("signatureProfile")))
                || !r.compressionAlgorithms().contains(m.get("compression"))
                || !r.deltaModes().contains(OtaTrustBundleCodec.object(m.get("delta")).get("mode"))
                || !((List<?>)m.get("allowedSourceThingModelVersionIds")).contains(r.thingModelVersionId().toString())) return Reason.INCOMPATIBLE;
        return Reason.ELIGIBLE;
    }
    /** 已由manifest codec严格验证的整数。 */
    private static long number(Map<String, Object> value, String name) {
        return (Long) value.get(name);
    }
    /** 数值版本逐轴比较，避免字典顺序误判。 */
    private static int compare(String first, String second) {
        String[] left = first.split("\\.");
        String[] right = second.split("\\.");
        for (int i = 0; i < 3; i++) {
            int result = Integer.compare(Integer.parseInt(left[i]), Integer.parseInt(right[i]));
            if (result != 0) return result;
        }
        return 0;
    }
}
