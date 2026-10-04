package com.things.link.project.api.dto.request;

import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** 不允许Jackson把JSON数字自动转换成字符串，避免客户端早已损失精度。 */
public final class CommercialIntegerDeserializer extends ValueDeserializer<String> {
    @Override public String deserialize(JsonParser parser,DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_STRING))
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER,"数量及版本必须使用JSON字符串");
        return parser.getString();
    }
}
