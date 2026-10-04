package com.things.link.ota.api;

import com.things.link.ota.application.OtaCanonicalJson;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 在普通DTO绑定前拒绝重复字段、隐式类型转换、未知字段和空值。 */
@Component
public class OtaFirmwareRequestParser {
    /** 只使用受限解析器的严格编码和重复键边界，不将此正文视为发布manifest。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();
    /** 解析精确三个字符串字段，再将UUID业务语法错误映射为10001。 */
    public CreateRequest create(byte[] body) {
        Map<String, Object> fields = fields(body, Set.of("deviceTypeId", "thingModelVersionId", "firmwareVersion"));
        return new CreateRequest(uuid((String) fields.get("deviceTypeId")),
                uuid((String) fields.get("thingModelVersionId")), (String) fields.get("firmwareVersion"));
    }
    /** 只读取修订字符串，合法范围由领域入口判断。 */
    public CancelRequest cancel(byte[] body) {
        return new CancelRequest((String) fields(body, Set.of("expectedRevision")).get("expectedRevision"));
    }
    /** 所有信封结构错误统一10002，不回显字段或JSON原文。 */
    private Map<String, Object> fields(byte[] body, Set<String> expected) {
        try {
            Map<String, Object> result = json.parseObject(body);
            if (!result.keySet().equals(expected) || result.values().stream().anyMatch(v -> !(v instanceof String))) {
                throw new IllegalArgumentException();
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.MALFORMED_REQUEST);
        }
    }
    /** UUID采用标准文本，拒绝JDK可接受的缩短组表示。 */
    private static UUID uuid(String value) {
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        }
        return UUID.fromString(value);
    }
    /**
     * 创建信封的公开结构。
     * @param deviceTypeId 精确设备类型ID
     * @param thingModelVersionId 精确模型版本ID
     * @param firmwareVersion 原样展示版本
     */
    @Schema(name = "OtaFirmwareCreateRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record CreateRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceTypeId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID thingModelVersionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 128) String firmwareVersion) { }
    /**
     * 取消信封的公开结构。
     * @param expectedRevision 非负十进制字符串
     */
    @Schema(name = "OtaFirmwareCancelRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record CancelRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "0|[1-9][0-9]{0,18}")
            String expectedRevision) { }
}
