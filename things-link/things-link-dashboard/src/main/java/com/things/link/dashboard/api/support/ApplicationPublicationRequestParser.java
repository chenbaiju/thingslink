package com.things.link.dashboard.api.support;

import com.things.link.dashboard.api.dto.request.ApplicationPublicationRevisionRequest;
import com.things.link.dashboard.api.dto.request.PublishApplicationVersionRequest;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.Set;

/**
 * 应用发布生命周期写入信封的严格原始JSON解析器。
 *
 * <p>S12-1e2c至1e2d沿既有管理写边界在DTO绑定前拒绝BOM、非法JSON、未知或重复字段、缺失、null、
 * 类型错误、非法Unicode标量和尾随值。revision数值语义仍由发布服务裁决为应用业务错误，
 * 本解析器不得把合法字符串提前转换为Java Long。</p>
 */
@Component
public final class ApplicationPublicationRequestParser {

    /** 发布新版本信封精确且全部必填的字段集合。 */
    private static final Set<String> PUBLISH_FIELDS = Set.of(
            "expectedDraftRevision", "expectedPublicationRevision");

    /** 回滚、撤回和软删除信封只允许发布状态CAS修订号且必须出现一次。 */
    private static final Set<String> PUBLICATION_REVISION_FIELDS = Set.of("expectedPublicationRevision");

    /** UTF-8 BOM三个字节；公共严格JSON信封明确禁止它。 */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** 只读取原始token流、不启用宽松JSON特性的Jackson读取器。 */
    private final ObjectReader objectReader = JsonMapper.builder(JsonFactory.builder().build()).build().reader();

    /**
     * 解析发布新应用版本的封闭双revision信封。
     *
     * @param source HTTP请求体原始字节
     * @return 已确认外层字段闭集和字符串类型的发布请求
     */
    public PublishApplicationVersionRequest parsePublish(byte[] source) {
        requireRawInput(source);
        try (JsonParser parser = objectReader.createParser(source)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw malformed();
            }
            Set<String> seen = new HashSet<>();
            String expectedDraftRevision = null;
            String expectedPublicationRevision = null;

            JsonToken token;
            while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
                if (token != JsonToken.PROPERTY_NAME) {
                    throw malformed();
                }
                String field = parser.getString();
                requireValidUnicode(field);
                if (!PUBLISH_FIELDS.contains(field) || !seen.add(field)) {
                    // 不回显未知或重复字段，避免把攻击者原文带入响应与日志。
                    throw malformed();
                }
                String text = requireString(parser, parser.nextToken());
                if ("expectedDraftRevision".equals(field)) {
                    expectedDraftRevision = text;
                } else if ("expectedPublicationRevision".equals(field)) {
                    expectedPublicationRevision = text;
                } else {
                    // 字段闭集已经保证无法到达；保留分支防止未来扩展时静默放行。
                    throw malformed();
                }
            }
            if (!seen.equals(PUBLISH_FIELDS) || parser.nextToken() != null) {
                throw malformed();
            }
            return new PublishApplicationVersionRequest(
                    expectedDraftRevision, expectedPublicationRevision);
        } catch (BusinessException exception) {
            throw exception;
        } catch (JacksonException exception) {
            throw malformed();
        }
    }

    /**
     * 解析只推进应用发布状态的封闭publicationRevision信封。
     *
     * @param source HTTP请求体原始字节
     * @return 已确认外层字段闭集和字符串类型的发布状态请求
     */
    public ApplicationPublicationRevisionRequest parsePublicationRevision(byte[] source) {
        requireRawInput(source);
        try (JsonParser parser = objectReader.createParser(source)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw malformed();
            }
            Set<String> seen = new HashSet<>();
            String expectedPublicationRevision = null;

            JsonToken token;
            while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
                if (token != JsonToken.PROPERTY_NAME) {
                    throw malformed();
                }
                String field = parser.getString();
                requireValidUnicode(field);
                if (!PUBLICATION_REVISION_FIELDS.contains(field) || !seen.add(field)) {
                    // 三种发布状态动作共享不回显规则，避免未知或重复字段进入错误响应。
                    throw malformed();
                }
                expectedPublicationRevision = requireString(parser, parser.nextToken());
            }
            if (!seen.equals(PUBLICATION_REVISION_FIELDS) || parser.nextToken() != null) {
                throw malformed();
            }
            return new ApplicationPublicationRevisionRequest(expectedPublicationRevision);
        } catch (BusinessException exception) {
            throw exception;
        } catch (JacksonException exception) {
            throw malformed();
        }
    }

    /** 拒绝缺失、空请求或带UTF-8 BOM的外层信封。 */
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

    /** 拒绝U+0000与未配对UTF-16代理项，保留合法补充平面字符。 */
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

    /** @return 不泄露解析器消息、字段名或请求原文的统一外层错误 */
    private static BusinessException malformed() {
        return new BusinessException(CommonErrorCode.MALFORMED_REQUEST);
    }
}
