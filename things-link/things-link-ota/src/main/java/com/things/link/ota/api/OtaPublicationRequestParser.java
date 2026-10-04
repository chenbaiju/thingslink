package com.things.link.ota.api;

import com.things.link.ota.application.OtaCanonicalJson;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 发布尝试闭集信封，完整manifest由领域codec继续验证身份和字段合同。 */
@Component
public class OtaPublicationRequestParser {
    /** 外层与manifest共享64KiB/深度/节点预算，重复键不能被DTO吞掉。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();
    /** 原文严格解析后仅传递服务器可验证的规范manifest字节。 */
    public CreateRequest parse(byte[] body) {
        Map<String, Object> fields;
        try { fields = json.parseObject(body); }
        catch (IllegalArgumentException failure) { throw malformed(); }
        if (!fields.keySet().equals(Set.of("expectedRevision", "uploadSessionId", "manifest"))
                || !(fields.get("expectedRevision") instanceof String revision)
                || !(fields.get("uploadSessionId") instanceof String upload)
                || !(fields.get("manifest") instanceof Map<?, ?> manifest)) throw malformed();
        if (!revision.matches("0|[1-9][0-9]{0,18}")
                || !upload.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw invalid();
        try { Long.parseLong(revision); }
        catch (NumberFormatException failure) { throw invalid(); }
        @SuppressWarnings("unchecked") Map<String, Object> object = (Map<String, Object>) manifest;
        return new CreateRequest(revision, UUID.fromString(upload), json.writeObject(object));
    }
    /** 信封语法/字段类型错误不回显正文。 */
    private static BusinessException malformed() { return new BusinessException(CommonErrorCode.MALFORMED_REQUEST); }
    /** 参数范围错误。 */
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
    /** @param expectedRevision 固件预期修订 @param uploadSessionId 已验真上传 @param manifest 规范manifest */
    public record CreateRequest(String expectedRevision, UUID uploadSessionId, byte[] manifest) {
        /** 不持有外部可变数组。 */ public CreateRequest { manifest = manifest.clone(); }
        /** 返回防御副本。 */ @Override public byte[] manifest() { return manifest.clone(); }
    }
    /** @param expectedRevision 固件预期修订 @param uploadSessionId 已验真上传身份 @param manifest 完整发布声明 */
    @Schema(name = "OtaPublicationCreateRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"expectedRevision", "uploadSessionId", "manifest"})
    public record CreateBody(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "0|[1-9][0-9]{0,18}") String expectedRevision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID uploadSessionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OtaPublicationManifestBody manifest) { }
}
