package com.things.link.ota.api;

import com.things.link.ota.application.OtaCanonicalJson;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import java.util.List;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 信任导入封闭信封，嵌套bundle仍共享严格JSON预算和重复键检查。 */
@Component
public class OtaTrustRequestParser {
    /** 与根签名输入一致的规范JSON子集。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();
    /** 结构错误统一拒绝，不回显签名或原始bundle。 */
    public ImportRequest parse(byte[] body) {
        Map<String, Object> fields;
        try { fields = json.parseObject(body); }
        catch (IllegalArgumentException failure) { throw malformed(); }
        if (!fields.keySet().equals(Set.of("expectedRevision", "bundle", "signature"))
                || !(fields.get("expectedRevision") instanceof String revision)
                || !(fields.get("bundle") instanceof Map<?, ?> bundle)
                || !(fields.get("signature") instanceof String signature)) throw malformed();
        if (!revision.matches("0|[1-9][0-9]{0,18}")) throw invalid();
        try { Long.parseLong(revision); }
        catch (NumberFormatException failure) { throw invalid(); }
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(signature); }
        catch (IllegalArgumentException failure) { throw invalid(); }
        if (decoded.length != 64 || !Base64.getEncoder().encodeToString(decoded).equals(signature)) throw invalid();
        // 严格解析器仅返回字符串键Map，不经普通DTO再次丢失重复字段。
        @SuppressWarnings("unchecked") Map<String, Object> object = (Map<String, Object>) bundle;
        return new ImportRequest(revision, json.writeObject(object), decoded);
    }
    /** 格式与字段类型错误。 */
    private static BusinessException malformed() { return new BusinessException(CommonErrorCode.MALFORMED_REQUEST); }
    /** 合法JSON但参数超出合同。 */
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
    /**
     * 已解析信封仅在当前请求传递，字节数组防御复制。
     * @param expectedRevision 期望修订
     * @param bundle 规范化根签名输入对象
     * @param signature 原始64字节签名
     */
    public record ImportRequest(String expectedRevision, byte[] bundle, byte[] signature) {
        /** 保护当前请求内容不被外部数组修改。 */
        public ImportRequest { bundle = bundle.clone(); signature = signature.clone(); }
        /** 返回独立规范字节。 */ @Override public byte[] bundle() { return bundle.clone(); }
        /** 返回独立签名字节。 */ @Override public byte[] signature() { return signature.clone(); }
    }
    /**
     * OpenAPI信封结构；bundle内部字段由独立根签名codec验证。
     * @param expectedRevision 当前十进制修订
     * @param bundle 根签名bundle对象
     * @param signature 标准Base64原始签名
     */
    @Schema(name = "OtaTrustImportRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ImportBody(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "0|[1-9][0-9]{0,18}") String expectedRevision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BundleBody bundle,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 88, maxLength = 88) String signature) { }
    /**
     * 根签名bundle完整文档结构，运行时仍由严格原文codec处理。
     * @param contractVersion 固定合同
     * @param trustDomain 根配置绑定域
     * @param bundleVersion 单调安全整数版本
     * @param keys 完整键列表，历史键不可省略
     */
    @Schema(name = "OtaTrustBundleBody", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"contractVersion", "trustDomain", "bundleVersion", "keys"})
    public record BundleBody(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"tc-ota-trust-bundle/v1"})
            String contractVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
            String trustDomain,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "9007199254740991")
            long bundleVersion,
            @ArraySchema(minItems = 1, maxItems = 64, schema = @Schema(implementation = KeyBody.class),
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED)) List<KeyBody> keys) { }
    /**
     * 发布键的七字段闭集；时间为UTC epoch秒，SPKI只接受严格公钥。
     * @param keyVersion 不可变键标识
     * @param signatureProfile 冻结签名Profile
     * @param spki 标准Base64 SPKI DER
     * @param fingerprint 完整小写SHA256
     * @param state 四态生命周期
     * @param notBefore 包含的有效期起点
     * @param notAfter 不包含的有效期终点且严格大于起点
     */
    @Schema(name = "OtaTrustKeyBody", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record KeyBody(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[A-Za-z0-9._:/-]{1,256}") String keyVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"TC_OTA_ED25519_V1", "TC_OTA_ES256_P1363_V1"}) String signatureProfile,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 172,
                    description = "标准带填充Base64的严格SPKI DER，解码最多128字节") String spki,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[0-9a-f]{64}") String fingerprint,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"PREPARED", "ACTIVE", "VERIFY_ONLY", "REVOKED"}) String state,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "253402300799") long notBefore,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "253402300799",
                    description = "UTC epoch秒，严格大于notBefore") long notAfter) { }

}
