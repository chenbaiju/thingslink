package com.things.link.ota.api;

import com.things.link.ota.application.OtaCanonicalJson;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 固件软终态严格二字段信封，不经宽松DTO绑定丢失重复字段。 */
@Component
public class OtaFirmwareLifecycleRequestParser {
    /** 共用有界JSON和严格UTF8解码。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();
    /** 只接受精确字段与类型，语义校验由应用入口执行。 */
    public Change parse(byte[] body) {
        Map<String, Object> fields;
        try { fields = json.parseObject(body); }
        catch (IllegalArgumentException failure) { throw malformed(); }
        if (!fields.keySet().equals(Set.of("expectedRevision", "reason"))
                || !(fields.get("expectedRevision") instanceof String revision)
                || !(fields.get("reason") instanceof String reason)) throw malformed();
        return new Change(revision, reason);
    }
    /** 固定结构错误不携带原文。 */
    private static BusinessException malformed() { return new BusinessException(CommonErrorCode.MALFORMED_REQUEST); }
    /**
     * 退役/撤销共用完整请求合同。
     * @param expectedRevision 当前规范非负long修订字符串
     * @param reason 不被自动trim的明确原因
     */
    @Schema(name = "OtaFirmwareLifecycleChange", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Change(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "0|[1-9][0-9]{0,18}") String expectedRevision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 512) String reason) { }
}
