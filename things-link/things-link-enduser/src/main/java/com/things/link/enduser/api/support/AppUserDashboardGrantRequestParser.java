package com.things.link.enduser.api.support;

import com.things.link.enduser.api.dto.request.UpdateAppUserDashboardGrantRequest;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * 终端用户看板授权更新信封的严格原始JSON解析器。
 *
 * <p>S12-2a3b要求在DTO绑定前拒绝BOM、非法UTF-8、非法JSON、未知或重复字段、缺失、null、
 * 类型错误、非法Unicode标量和尾随值。合法字符串保持原样交给管理服务裁决60026/60027。</p>
 */
@Component
public final class AppUserDashboardGrantRequestParser {

    /** 更新信封精确且全部必填的字段闭集。 */
    private static final Set<String> UPDATE_FIELDS = Set.of("expectedRevision", "status");

    /** UTF-8 BOM会制造客户端与服务端摘要/语义差异，因此严格信封明确拒绝。 */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** 仅读取标准JSON token，不启用注释、单引号或尾随逗号等宽松特性。 */
    private final ObjectReader objectReader = JsonMapper.builder(JsonFactory.builder().build()).build().reader();

    /**
     * 解析授权更新的封闭双字符串信封。
     *
     * @param source HTTP请求体原始字节
     * @return 仅确认词法、字段闭集与字符串类型的请求
     */
    public UpdateAppUserDashboardGrantRequest parseUpdate(byte[] source) {
        requireRawInput(source);
        String utf8 = decodeUtf8(source);
        try (JsonParser parser = objectReader.createParser(utf8)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw malformed();
            }
            Set<String> seen = new HashSet<>();
            String expectedRevision = null;
            String status = null;
            JsonToken token;
            while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
                if (token != JsonToken.PROPERTY_NAME) {
                    throw malformed();
                }
                String field = parser.getString();
                requireValidUnicode(field);
                if (!UPDATE_FIELDS.contains(field) || !seen.add(field)) {
                    // 不回显未知或重复字段，避免攻击者原文进入错误响应或日志。
                    throw malformed();
                }
                String value = requireString(parser, parser.nextToken());
                if ("expectedRevision".equals(field)) {
                    expectedRevision = value;
                } else if ("status".equals(field)) {
                    status = value;
                } else {
                    // 字段闭集保证不可到达；保留失败分支防止未来扩展时静默放行。
                    throw malformed();
                }
            }
            if (!seen.equals(UPDATE_FIELDS) || parser.nextToken() != null) {
                throw malformed();
            }
            return new UpdateAppUserDashboardGrantRequest(expectedRevision, status);
        } catch (BusinessException exception) {
            throw exception;
        } catch (JacksonException exception) {
            throw malformed();
        }
    }

    /**
     * 以REPORT模式显式解码UTF-8，关闭Jackson对UTF-16/UTF-32字节流的自动探测。
     *
     * @param source 已通过空值与BOM检查的原始字节
     * @return 严格UTF-8文本
     */
    private static String decodeUtf8(byte[] source) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(source))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw malformed();
        }
    }

    /** 拒绝缺失、空请求或带UTF-8 BOM的原始信封。 */
    private static void requireRawInput(byte[] source) {
        if (source == null || source.length == 0) {
            throw malformed();
        }
        if (source.length >= UTF8_BOM.length
                && source[0] == UTF8_BOM[0]
                && source[1] == UTF8_BOM[1]
                && source[2] == UTF8_BOM[2]) {
            throw malformed();
        }
    }

    /** 要求当前值是非null字符串并拒绝非法Unicode标量。 */
    private static String requireString(JsonParser parser, JsonToken token) throws JacksonException {
        if (token != JsonToken.VALUE_STRING) {
            throw malformed();
        }
        String value = parser.getString();
        requireValidUnicode(value);
        return value;
    }

    /** 拒绝U+0000与未配对UTF-16代理项，同时保留合法补充平面字符。 */
    private static void requireValidUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\0') {
                throw malformed();
            }
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw malformed();
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw malformed();
            }
        }
    }

    /** @return 不泄露解析器消息、字段名或原始正文的统一10002错误 */
    private static BusinessException malformed() {
        return new BusinessException(CommonErrorCode.MALFORMED_REQUEST);
    }
}
