package com.things.link.ota.api;

import com.things.link.ota.application.OtaSignatureProfile;
import com.things.link.ota.application.OtaTypeBaselineCodec;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import java.util.List;
import java.util.UUID;

/** 强类型公开能力基线，仅表示受控声明而非设备实机资格。
 * @param contractVersion 固定合同
 * @param tenantId 真实租户
 * @param projectId 所属项目
 * @param deviceTypeId 设备类型
 * @param productKey 产品标识
 * @param baselineVersion 受控基线版本
 * @param trustDomain 信任域
 * @param rootFingerprint 离线根指纹
 * @param hardware 硬件范围
 * @param bootloader 引导版本范围
 * @param signatureProfiles 签名Profile集合
 * @param maximumArtifactBytes 最大固件字节
 * @param availableRamBytes 可用RAM字节
 * @param availableFlashBytes 可用Flash字节
 * @param supportsAbSlots AB槽支持
 * @param supportsRangeDownload Range支持
 * @param supportsResumeDownload 断点续传支持
 * @param protectedSecurityCounterBits 受保护计数器位数
 * @param compressionAlgorithms 压缩算法
 * @param deltaModes 差分方式
 * @param propertyProfile 完整属性Profile
 * @param evidenceReference 受控来源索引
 */
@Schema(name = "OtaTypeBaselineBody", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record OtaTypeBaselineBody(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"tc-ota-type-baseline/v1"}) String contractVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID tenantId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID projectId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID deviceTypeId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}") String productKey,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "9007199254740991") long baselineVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String trustDomain,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[0-9a-f]{64}") String rootFingerprint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Hardware hardware,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Bootloader bootloader,
        @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED), minItems = 1, maxItems = 2, uniqueItems = true, schema = @Schema(type = "string", allowableValues = {"TC_OTA_ED25519_V1", "TC_OTA_ES256_P1363_V1"})) List<String> signatureProfiles,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "67108864") long maximumArtifactBytes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991") long availableRamBytes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991") long availableFlashBytes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean supportsAbSlots,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean supportsRangeDownload,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean supportsResumeDownload,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "53") int protectedSecurityCounterBits,
        @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED), minItems = 1, maxItems = 1, schema = @Schema(type = "string", allowableValues = {"NONE"})) List<String> compressionAlgorithms,
        @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED), minItems = 1, maxItems = 1, schema = @Schema(type = "string", allowableValues = {"NONE"})) List<String> deltaModes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"TC_PROPERTY_COMPOSITE_V1"}) String propertyProfile,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 256) String evidenceReference) {
    /** 复制公开列表，避免响应构造后能力漂移。 */
    public OtaTypeBaselineBody { signatureProfiles = List.copyOf(signatureProfiles); compressionAlgorithms = List.copyOf(compressionAlgorithms); deltaModes = List.copyOf(deltaModes); }
    /** 明确逐字段投影，不生成Map类型开放对象。 */
    public static OtaTypeBaselineBody from(OtaTypeBaselineCodec.Decoded decoded) {
        var v = decoded.value();
        return new OtaTypeBaselineBody(v.contractVersion(), v.tenantId(), v.projectId(), v.deviceTypeId(), v.productKey(),
                v.baselineVersion(), v.trustDomain(), v.rootFingerprint(),
                new Hardware(v.hardware().model(), v.hardware().boardRevisionMin(), v.hardware().boardRevisionMax()),
                new Bootloader(v.bootloader().minimumVersion(), v.bootloader().maximumVersion()),
                v.signatureProfiles().stream().map(OtaSignatureProfile::value).toList(), v.maximumArtifactBytes(),
                v.availableRamBytes(), v.availableFlashBytes(), v.supportsAbSlots(), v.supportsRangeDownload(),
                v.supportsResumeDownload(), v.protectedSecurityCounterBits(), v.compressionAlgorithms(), v.deltaModes(),
                v.propertyProfile(), v.evidenceReference());
    }
    /** 硬件范围强类型合同。
     * @param model 型号
     * @param boardRevisionMin 最小板级序号
     * @param boardRevisionMax 最大板级序号
     */
    @Schema(name = "OtaTypeBaselineHardware", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Hardware(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String model,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991") long boardRevisionMin,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991") long boardRevisionMax) { }
    /** bootloader范围强类型合同。
     * @param minimumVersion 最低版本
     * @param maximumVersion 最高版本
     */
    @Schema(name = "OtaTypeBaselineBootloader", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Bootloader(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "(0|[1-9][0-9]{0,9})\\.(0|[1-9][0-9]{0,9})\\.(0|[1-9][0-9]{0,9})") String minimumVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "(0|[1-9][0-9]{0,9})\\.(0|[1-9][0-9]{0,9})\\.(0|[1-9][0-9]{0,9})") String maximumVersion) { }
}
