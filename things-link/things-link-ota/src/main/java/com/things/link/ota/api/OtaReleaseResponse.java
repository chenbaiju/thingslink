package com.things.link.ota.api;

import com.things.link.ota.application.OtaReleaseDownloadService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** 管理端公开发布证明，不暴露独立对象定位或供应商回执。
 * @param firmwareId 固件身份
 * @param publicationId 已提交发布尝试身份
 * @param manifestBase64 精确规范UTF8清单的标准Base64
 * @param signatureBase64 标准Base64签名
 * @param publicKeySpkiBase64 标准Base64发布公钥SPKI
 * @param signatureProfile 固定签名合同
 * @param keyFingerprint 小写SHA256公钥指纹
 * @param artifactSize 十进制字节数
 * @param artifactSha256 小写SHA256完整制品摘要
 * @param releaseCreatedAt 持久发布时间
 */
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"firmwareId", "publicationId", "manifestBase64", "signatureBase64",
                "publicKeySpkiBase64", "signatureProfile", "keyFingerprint", "artifactSize",
                "artifactSha256", "releaseCreatedAt"})
public record OtaReleaseResponse(UUID firmwareId, UUID publicationId,
        @Schema(format = "byte") String manifestBase64,
        @Schema(format = "byte") String signatureBase64,
        @Schema(format = "byte") String publicKeySpkiBase64,
        @Schema(allowableValues = {"TC_OTA_ED25519_V1", "TC_OTA_ES256_P1363_V1"}) String signatureProfile,
        @Schema(pattern = "[0-9a-f]{64}") String keyFingerprint,
        @Schema(pattern = "[1-9][0-9]{0,15}") String artifactSize,
        @Schema(pattern = "[0-9a-f]{64}") String artifactSha256, Instant releaseCreatedAt) {
    /** 从已复验权威快照提取最小公开字段，禁止直接序列化内部快照。 */
    public static OtaReleaseResponse from(OtaReleaseDownloadService.Snapshot snapshot) {
        var release = snapshot.release();
        var key = snapshot.grant().binding();
        var encoder = Base64.getEncoder();
        return new OtaReleaseResponse(release.firmwareId(), release.publicationId(),
                encoder.encodeToString(release.canonicalManifest()), encoder.encodeToString(release.signature()),
                encoder.encodeToString(release.spki()), key.profile().value(), key.fingerprint(),
                Long.toString(snapshot.upload().expectedLength()), snapshot.upload().expectedSha256(), release.createdAt());
    }
}
