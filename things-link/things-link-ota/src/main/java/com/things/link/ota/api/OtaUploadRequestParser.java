package com.things.link.ota.api;

import com.things.link.ota.application.OtaCanonicalJson;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 上传信封严格解析，不接受重复/未知字段、隐式转换及存储地址。 */
@Component
public class OtaUploadRequestParser {
    /** 复用严格UTF8和重复键保护。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();
    /** 只接收整数长度和小写SHA256。 */
    public CreateRequest create(byte[] body) {
        Map<String, Object> fields = fields(body, Set.of("expectedLength", "expectedSha256"));
        if (!(fields.get("expectedLength") instanceof Long length)
                || !(fields.get("expectedSha256") instanceof String sha)) throw malformed();
        if (length < 1 || length > 67108864 || !sha.matches("[0-9a-f]{64}")) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        }
        return new CreateRequest(length, sha);
    }
    /** 取消修订不允许数值型隐式转换。 */
    public CancelRequest cancel(byte[] body) {
        Object value = fields(body, Set.of("expectedRevision")).get("expectedRevision");
        if (!(value instanceof String revision)) throw malformed();
        if (!revision.matches("0|[1-9][0-9]{0,18}")) throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        try { Long.parseLong(revision); }
        catch (NumberFormatException exception) { throw new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
        return new CancelRequest(revision);
    }
    /** 结构失败不回显原始JSON或字段值。 */
    private Map<String, Object> fields(byte[] body, Set<String> expected) {
        try {
            Map<String, Object> result = json.parseObject(body);
            if (!result.keySet().equals(expected)) throw new IllegalArgumentException();
            return result;
        } catch (IllegalArgumentException exception) { throw malformed(); }
    }
    /** 稳定结构错误。 */
    private static BusinessException malformed() { return new BusinessException(CommonErrorCode.MALFORMED_REQUEST); }
    /** @param expectedLength 完整字节数 @param expectedSha256 小写SHA256 */
    @Schema(name = "OtaUploadCreateRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record CreateRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "67108864") long expectedLength,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[0-9a-f]{64}$") String expectedSha256) { }
    /** @param expectedRevision 当前非负long修订字符串 */
    @Schema(name = "OtaUploadCancelRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record CancelRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^(0|[1-9][0-9]{0,18})$") String expectedRevision) { }
}
