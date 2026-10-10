package com.things.link.enduser.api.support;

import com.things.link.enduser.api.dto.request.UpdateAppNotificationPreferenceRequest;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.json.JsonMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** 拒绝未知、重复、缺失、类型转换和尾随内容，不能用多余字段修改身份或号码。 */
@Component
public class AppNotificationPreferenceRequestParser {
    private final JsonMapper mapper = JsonMapper.builder().build();
    /**
     * 只接受标准UTF-8 JSON及精确两个字段。
     * @param body 原始请求，最大1024字节
     * @return 类型与版本范围已验证的请求；失败统一参数错误且不回显原文
     */
    public UpdateAppNotificationPreferenceRequest parse(byte[] body) {
        if (body == null || body.length == 0 || body.length > 1024) throw invalid();
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            try (var parser = mapper.reader().createParser(json)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw invalid();
                Set<String> fields = new HashSet<>();
                Boolean enabled = null;
                String revision = null;
                JsonToken token;
                while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
                    if (token != JsonToken.PROPERTY_NAME) throw invalid();
                    String field = parser.getString();
                    if (!fields.add(field)) throw invalid();
                    token = parser.nextToken();
                    if ("appPushEnabled".equals(field)) {
                        if (token != JsonToken.VALUE_TRUE && token != JsonToken.VALUE_FALSE) throw invalid();
                        enabled = token == JsonToken.VALUE_TRUE;
                    } else if ("expectedRevision".equals(field)) {
                        if (token != JsonToken.VALUE_STRING) throw invalid();
                        revision = parser.getString();
                        if (!revision.matches("0|[1-9][0-9]{0,18}")) throw invalid();
                        Long.parseLong(revision);
                    } else throw invalid();
                }
                if (enabled == null || revision == null || parser.nextToken() != null) throw invalid();
                return new UpdateAppNotificationPreferenceRequest(enabled, revision);
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }
    /** 不向响应或日志传播用户提供的内容。 */
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
}
