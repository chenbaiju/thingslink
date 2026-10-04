package com.things.link.dashboard.api.support;

import com.things.link.dashboard.api.dto.request.CreateApplicationRequest;
import com.things.link.dashboard.api.dto.request.RenameApplicationRequest;
import com.things.link.dashboard.api.dto.request.SaveApplicationDraftRequest;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 应用管理写入信封的严格原始JSON解析器。
 *
 * <p>S12-1d2要求在普通DTO绑定覆盖证据前拒绝未知字段、转义解码后重复键、
 * 非对象根、类型错误与尾随值。创建及草稿保存的content仅在外层确认为对象并定位边界，
 * 内部原始字节原样交给已有草稿合同校验器，不先转换为{@code JsonNode}。</p>
 */
@Component
public final class ApplicationManagementRequestParser {

    /** 创建信封的两个必填字段。 */
    private static final Set<String> CREATE_FIELDS = Set.of("managementName", "content");

    /** 改名信封的唯一必填字段。 */
    private static final Set<String> RENAME_FIELDS = Set.of("managementName");

    /** 草稿保存信封的两个必填字段。 */
    private static final Set<String> SAVE_DRAFT_FIELDS = Set.of("expectedRevision", "content");

    /** UTF-8 BOM三个字节；公共字段语法明确禁止它。 */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** 仅使用token流保留原始字节位置的JSON读取器。 */
    private final ObjectReader objectReader = JsonMapper.builder(JsonFactory.builder().build()).build().reader();

    /**
     * 解析封闭的应用创建信封，保留content原始字节。
     *
     * @param source HTTP请求体原始字节
     * @return 已确认外层形状的创建请求
     */
    public CreateApplicationRequest parseCreate(byte[] source) {
        ParsedEnvelope envelope = parse(source, EnvelopeKind.CREATE);
        return new CreateApplicationRequest(envelope.managementName(), envelope.content());
    }

    /**
     * 解析封闭的应用改名信封。
     *
     * @param source HTTP请求体原始字节
     * @return 已确认外层形状的改名请求
     */
    public RenameApplicationRequest parseRename(byte[] source) {
        ParsedEnvelope envelope = parse(source, EnvelopeKind.RENAME);
        return new RenameApplicationRequest(envelope.managementName());
    }

    /**
     * 解析封闭的草稿保存信封，保留content原始字节。
     *
     * @param source HTTP请求体原始字节
     * @return 已确认外层形状的草稿保存请求
     */
    public SaveApplicationDraftRequest parseSaveDraft(byte[] source) {
        ParsedEnvelope envelope = parse(source, EnvelopeKind.SAVE_DRAFT);
        return new SaveApplicationDraftRequest(envelope.expectedRevision(), envelope.content());
    }

    /** 以同一有限状态机解析三种封闭顶层信封。 */
    private ParsedEnvelope parse(byte[] source, EnvelopeKind kind) {
        requireRawInput(source);
        try (JsonParser parser = objectReader.createParser(source)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw malformed();
            }
            Set<String> seen = new HashSet<>();
            String managementName = null;
            String expectedRevision = null;
            byte[] content = null;

            JsonToken token;
            while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
                if (token != JsonToken.PROPERTY_NAME) {
                    throw malformed();
                }
                String field = parser.getString();
                requireValidUnicode(field);
                if (!kind.fields().contains(field) || !seen.add(field)) {
                    // 不回显未知或重复字段，避免把攻击者原文进入响应与日志。
                    throw malformed();
                }
                JsonToken value = parser.nextToken();
                if (value == null || value == JsonToken.VALUE_NULL) {
                    throw malformed();
                }
                if ("managementName".equals(field)) {
                    managementName = requireString(parser, value);
                } else if ("expectedRevision".equals(field)) {
                    expectedRevision = requireString(parser, value);
                } else if ("content".equals(field)) {
                    content = extractObjectBytes(parser, value, source);
                } else {
                    // fields()已经保证无法到达；保留防御分支防止扩展字段时静默放行。
                    throw malformed();
                }
            }
            if (!seen.equals(kind.fields()) || parser.nextToken() != null) {
                throw malformed();
            }
            return new ParsedEnvelope(managementName, expectedRevision, content);
        } catch (BusinessException exception) {
            throw exception;
        } catch (JacksonException exception) {
            throw malformed();
        }
    }

    /** 拒绝缺失、空请求或带BOM的外层信封。 */
    private static void requireRawInput(byte[] source) {
        if (source == null || source.length == 0) {
            throw malformed();
        }
        if (source.length >= UTF8_BOM.length
                && source[0] == UTF8_BOM[0] && source[1] == UTF8_BOM[1] && source[2] == UTF8_BOM[2]) {
            throw malformed();
        }
    }

    /** 要求当前信封标量是非null字符串并审计Unicode标量。 */
    private static String requireString(JsonParser parser, JsonToken token) throws JacksonException {
        if (token != JsonToken.VALUE_STRING) {
            throw malformed();
        }
        String value = parser.getString();
        requireValidUnicode(value);
        return value;
    }

    /** 截取content对象的精确原始字节范围，不重新序列化其内容。 */
    private static byte[] extractObjectBytes(JsonParser parser, JsonToken token, byte[] source)
            throws JacksonException {
        if (token != JsonToken.START_OBJECT) {
            throw malformed();
        }
        long start = parser.currentTokenLocation().getByteOffset();
        parser.skipChildren();
        long end = parser.currentLocation().getByteOffset();
        if (start < 0 || end <= start || end > source.length || start > Integer.MAX_VALUE || end > Integer.MAX_VALUE) {
            throw new IllegalStateException("Jackson未提供content原始字节边界");
        }
        return Arrays.copyOfRange(source, (int) start, (int) end);
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

    /** 构造不泄露解析器、字段名或请求原文的统一错误。 */
    private static BusinessException malformed() {
        return new BusinessException(CommonErrorCode.MALFORMED_REQUEST);
    }

    /** 三种封闭信封及其精确必填字段。 */
    private enum EnvelopeKind {
        /** 应用创建信封。 */
        CREATE(CREATE_FIELDS),
        /** 应用改名信封。 */
        RENAME(RENAME_FIELDS),
        /** 草稿保存信封。 */
        SAVE_DRAFT(SAVE_DRAFT_FIELDS);

        /** 本信封允许且必须出现的字段。 */
        private final Set<String> fields;

        /**
         * 创建信封种类。
         *
         * @param fields 允许且必填的字段集
         */
        EnvelopeKind(Set<String> fields) {
            this.fields = fields;
        }

        /**
         * 返回不可变字段闭集。
         *
         * @return 本信封允许且必填的字段
         */
        Set<String> fields() {
            return fields;
        }
    }

    /**
     * 通用解析中间结果；不属于HTTP公开合同。
     *
     * @param managementName 创建或改名信封字符串；草稿保存信封为空
     * @param expectedRevision 草稿修订号字符串；创建和改名信封为空
     * @param content 创建或草稿保存的content原始字节；改名信封为空
     */
    private record ParsedEnvelope(String managementName, String expectedRevision, byte[] content) {
    }
}
