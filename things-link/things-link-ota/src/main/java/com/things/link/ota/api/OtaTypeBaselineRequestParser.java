package com.things.link.ota.api;

import com.things.link.ota.application.OtaCanonicalJson;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 登记只接受修订，不允许客户端自选根、能力、来源标识或制造版本。 */
@Component
public class OtaTypeBaselineRequestParser {
    /** 严格UTF8、重复键和有界JSON保护。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();

    /** 闭集单字段信封不能被宽松DTO绑定吞掉能力字段。 */
    public Registration parse(byte[] body) {
        Map<String, Object> fields;
        try { fields = json.parseObject(body); }
        catch (IllegalArgumentException failure) { throw malformed(); }
        if (!fields.keySet().equals(Set.of("expectedRevision"))
                || !(fields.get("expectedRevision") instanceof String revision)) throw malformed();
        return new Registration(revision);
    }

    /** 结构错误不携带用户正文。 */
    private static BusinessException malformed() { return new BusinessException(CommonErrorCode.MALFORMED_REQUEST); }

    /**
     * 受控基线登记的唯一客户端选择。
     * @param expectedRevision 规范非负long字符串，首次为0
     */
    @Schema(name = "OtaTypeBaselineRegistration", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"expectedRevision"})
    public record Registration(@Schema(pattern = "0|[1-9][0-9]{0,18}") String expectedRevision) { }
}
