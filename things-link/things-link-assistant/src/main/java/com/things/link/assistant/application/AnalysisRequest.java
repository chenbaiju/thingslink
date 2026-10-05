package com.things.link.assistant.application;

import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;

/** 固定模板请求身份，属性顺序参与摘要；绝不保留证据值。 */
@io.swagger.v3.oas.annotations.media.Schema(name="AssistantAnalysisRequest",requiredProperties={"deviceId","expectedModelVersionId","propertyKeys","template"},
        additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE)
public record AnalysisRequest(UUID deviceId, UUID expectedModelVersionId,
        @io.swagger.v3.oas.annotations.media.ArraySchema(minItems=1,maxItems=10,uniqueItems=true,
                schema=@io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[A-Za-z0-9_-]{1,64}"))
        List<String> propertyKeys, PreparedModelEvidence.Template template) {
    public AnalysisRequest {
        if (deviceId == null || expectedModelVersionId == null || template == null || propertyKeys == null
                || propertyKeys.isEmpty() || propertyKeys.size() > 10
                || propertyKeys.stream().anyMatch(k -> k == null || !k.matches("[A-Za-z0-9_-]{1,64}"))
                || new HashSet<>(propertyKeys).size() != propertyKeys.size()) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        }
        propertyKeys = List.copyOf(propertyKeys);
    }
    @Override public String toString() { return "AnalysisRequest[REDACTED]"; }
}
