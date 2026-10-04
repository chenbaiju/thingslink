package com.things.link.ota.application;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 比对认证声明与原来源/签名目标，不把匹配声明当作硬件证明。 */
final class OtaJobProgressEvidenceValidator {
    /** 仅提供确定性证据比较，不创建可替代事务资格的组件。 */
    private OtaJobProgressEvidenceValidator() { }

    /** 返回稳定安全不一致原因；持久manifest损坏保留内部首因，不归咎本次设备载荷。 */
    static String mismatch(OtaJobProgressCodec.Progress progress, OtaDeviceReportCodec.Report source,
            byte[] canonicalManifest, UUID firstBootId) {
        Map<String, Object> manifest;
        try {
            if (!Arrays.equals(new OtaManifestCodec().canonicalize(canonicalManifest), canonicalManifest)) {
                throw new IllegalArgumentException("持久manifest不是规范字节");
            }
            manifest = new OtaCanonicalJson().parseObject(canonicalManifest);
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("OTA进度引用的持久manifest不完整", failure);
        }
        var proof = progress.evidence();
        if (!source.supportsAbSlots() || !"A".equals(source.activeSlot()) && !"B".equals(source.activeSlot())) {
            return "SAFE_SLOT_STRATEGY_UNSUPPORTED";
        }
        if (!proof.artifactSha256().equals(manifest.get("artifactSha256"))
                || proof.artifactSize() != (Long) manifest.get("artifactSize")
                || proof.securityVersion() != (Long) manifest.get("securityVersion")
                || !proof.thingModelVersionId().toString().equals(manifest.get("thingModelVersionId"))
                || !proof.thingModelSchemaDigestAlgorithm().equals(manifest.get("thingModelSchemaDigestAlgorithm"))
                || !proof.thingModelSchemaDigest().equals(manifest.get("thingModelSchemaDigest"))
                || !proof.propertyProfile().equals(OtaTrustBundleCodec.object(manifest.get("requirements")).get("profile"))) {
            return "MANIFEST_TUPLE_MISMATCH";
        }
        if (!proof.trustDomain().equals(source.trustDomain()) || !proof.trustDomain().equals(manifest.get("trustDomain"))
                || !proof.rootFingerprint().equals(source.rootFingerprint())
                || proof.trustBundleVersion() != source.trustBundleVersion()
                || !proof.trustBundleSha256().equals(source.trustBundleSha256())) {
            return "TRUST_TUPLE_MISMATCH";
        }
        if (proof.committedSecurityVersion() != source.committedSecurityVersion()) {
            return "SECURITY_COMMIT_PREMATURE";
        }
        boolean health = "HEALTH_CHECKING".equals(progress.stage());
        String targetSlot = "A".equals(source.activeSlot()) ? "B" : "A";
        if (!proof.sourceSlot().equals(source.activeSlot()) || !proof.targetSlot().equals(targetSlot)
                || !proof.activeSlot().equals(health ? targetSlot : source.activeSlot())) {
            return "SOURCE_SLOT_MISMATCH";
        }
        String verification = "VERIFYING".equals(progress.stage()) ? "NOT_STARTED" : "PASSED";
        if (!verification.equals(proof.verification()) || proof.bootVerified() != health
                || proof.selfTestPassed() != health || proof.watchdogHealthy() != health) {
            return "STAGE_EVIDENCE_INVALID";
        }
        if (firstBootId == null) {
            if (!"VERIFYING".equals(progress.stage())) return "BOOT_SESSION_MISMATCH";
        } else if (health == firstBootId.equals(progress.bootId())) {
            return "BOOT_SESSION_MISMATCH";
        }
        return null;
    }

    /** 序号和资源余量可改变，已冻结的执行来源和信任元组不能静默替换。 */
    static boolean sameSource(OtaDeviceReportCodec.Report original, OtaDeviceReportCodec.Report current) {
        return Objects.equals(original.hardware(), current.hardware())
                && original.bootloaderVersion().equals(current.bootloaderVersion())
                && original.activeSlot().equals(current.activeSlot())
                && original.currentFirmwareSha256().equals(current.currentFirmwareSha256())
                && original.currentSecurityVersion() == current.currentSecurityVersion()
                && original.committedSecurityVersion() == current.committedSecurityVersion()
                && original.thingModelVersionId().equals(current.thingModelVersionId())
                && original.thingModelSchemaDigestAlgorithm().equals(current.thingModelSchemaDigestAlgorithm())
                && original.thingModelSchemaDigest().equals(current.thingModelSchemaDigest())
                && original.propertyProfile().equals(current.propertyProfile())
                && original.trustDomain().equals(current.trustDomain())
                && original.rootFingerprint().equals(current.rootFingerprint())
                && original.trustBundleVersion() == current.trustBundleVersion()
                && original.trustBundleSha256().equals(current.trustBundleSha256());
    }
}
