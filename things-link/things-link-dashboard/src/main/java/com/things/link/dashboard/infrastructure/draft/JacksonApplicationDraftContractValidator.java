package com.things.link.dashboard.infrastructure.draft;

import com.things.link.dashboard.application.draft.ApplicationDraftContractValidator;
import com.things.link.dashboard.application.draft.ApplicationDraftContractViolation;
import com.things.link.dashboard.application.draft.ValidatedApplicationDraft;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 使用Jackson token流校验应用草稿原文，再按S12-0b3a封闭结构建立安全JSON树。
 *
 * <p>重复键、非法UTF-8和代理项必须在建树覆盖原文证据前拒绝；业务字段校验完成后才返回防御副本。
 * PostgreSQL规范文本长度按jsonb输出的冒号/逗号空格和字符串转义计数，合法草稿不含数值，避免以
 * Jackson紧凑序列化冒充数据库规范文本。</p>
 */
@Component
public final class JacksonApplicationDraftContractValidator implements ApplicationDraftContractValidator {
    /** content原文与PostgreSQL jsonb规范文本各自允许的最大UTF-8字节数。 */
    public static final int MAXIMUM_BYTES = 65_536;
    /** 根content对象计为第一层时允许的最大对象或数组深度。 */
    public static final int MAXIMUM_DEPTH = 8;
    /** 单份草稿允许的精确Dashboard引用上限。 */
    public static final int MAXIMUM_DASHBOARD_REFERENCES = 5;
    /** 当前登记的应用草稿格式。 */
    private static final String FORMAT_VERSION = "tc.application/v1";
    /** 规范小写UUID文本。 */
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    /** 无前导零且不带扩展的三段SemVer。 */
    private static final Pattern SEMVER = Pattern.compile(
            "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)");
    /** Revision只能是0或无前导零正十进制字符串。 */
    private static final Pattern REVISION = Pattern.compile("0|[1-9][0-9]{0,18}");
    /** content顶层字段闭集。 */
    private static final Set<String> CONTENT_FIELDS = Set.of(
            "formatVersion", "displayName", "hostCompatibility", "dashboardRefs", "entryDashboardId");
    /** HostRange字段闭集。 */
    private static final Set<String> HOST_RANGE_FIELDS = Set.of("minInclusive", "maxExclusive");
    /** 草稿Dashboard引用字段闭集。 */
    private static final Set<String> DASHBOARD_REFERENCE_FIELDS = Set.of(
            "dashboardId", "dashboardVersionId", "title");
    /** UTF-8 BOM三个字节。 */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    /** token审计和建树共用严格JSON工厂。 */
    private final ObjectReader objectReader = JsonMapper.builder(JsonFactory.builder().build()).build().reader();

    /** {@inheritDoc} */
    @Override
    public ValidatedApplicationDraft validate(String expectedRevision, byte[] source) {
        byte[] snapshot = snapshotRawInput(source);
        long revision = requireRevision(expectedRevision);
        requireValidUtf8(snapshot);
        inspectTokens(snapshot);
        ObjectNode content = readRoot(snapshot);
        if (postgresJsonbTextUtf8Length(content) > MAXIMUM_BYTES) {
            throw reject(ApplicationDraftContractViolation.Reason.NORMALIZED_TOO_LARGE,
                    "$", "应用草稿PostgreSQL规范文本超过65536字节");
        }
        validateContent(content);
        return new ValidatedApplicationDraft(revision, content);
    }

    /** 冻结输入并在解析前执行精确原文字节与BOM限制。 */
    private static byte[] snapshotRawInput(byte[] source) {
        if (source == null) {
            throw reject(ApplicationDraftContractViolation.Reason.INVALID_JSON, "$", "应用草稿原文不能为空");
        }
        if (source.length > MAXIMUM_BYTES) {
            throw reject(ApplicationDraftContractViolation.Reason.RAW_TOO_LARGE,
                    "$", "应用草稿原文超过65536字节");
        }
        byte[] snapshot = source.clone();
        if (snapshot.length >= UTF8_BOM.length
                && snapshot[0] == UTF8_BOM[0] && snapshot[1] == UTF8_BOM[1] && snapshot[2] == UTF8_BOM[2]) {
            throw reject(ApplicationDraftContractViolation.Reason.BOM_NOT_ALLOWED,
                    "$", "应用草稿不得包含UTF-8 BOM");
        }
        return snapshot;
    }

    /** 使用REPORT策略拒绝畸形或不可映射UTF-8字节。 */
    private static void requireValidUtf8(byte[] source) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(source));
        } catch (CharacterCodingException exception) {
            throw reject(ApplicationDraftContractViolation.Reason.INVALID_UTF8,
                    "$", "应用草稿原文不是合法UTF-8", exception);
        }
    }

    /** 在建树前检查根对象、嵌套深度、重复键和Unicode标量。 */
    private void inspectTokens(byte[] source) {
        try (JsonParser parser = objectReader.createParser(source)) {
            JsonToken token = parser.nextToken();
            if (token != JsonToken.START_OBJECT) {
                throw reject(ApplicationDraftContractViolation.Reason.ROOT_MUST_BE_OBJECT,
                        "$", "应用草稿根值必须是对象");
            }
            Deque<ContainerFrame> frames = new ArrayDeque<>();
            int depth = 0;
            while (token != null) {
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    depth++;
                    if (depth > MAXIMUM_DEPTH) {
                        throw reject(ApplicationDraftContractViolation.Reason.DEPTH_EXCEEDED,
                                "$", "应用草稿对象或数组嵌套超过8层");
                    }
                    frames.push(token == JsonToken.START_OBJECT ? ContainerFrame.object() : ContainerFrame.array());
                } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    frames.pop();
                    depth--;
                    if (depth == 0) break;
                } else if (token == JsonToken.PROPERTY_NAME) {
                    String name = parser.getString();
                    requireValidUnicode(name);
                    if (!frames.peek().register(name)) {
                        throw reject(ApplicationDraftContractViolation.Reason.DUPLICATE_KEY,
                                "$", "应用草稿同一对象包含重复字段");
                    }
                } else if (token == JsonToken.VALUE_STRING) {
                    requireValidUnicode(parser.getString());
                }
                token = parser.nextToken();
            }
            if (depth != 0) {
                throw reject(ApplicationDraftContractViolation.Reason.INVALID_JSON,
                        "$", "应用草稿JSON结构未闭合");
            }
            if (parser.nextToken() != null) {
                throw reject(ApplicationDraftContractViolation.Reason.TRAILING_VALUE,
                        "$", "应用草稿根对象后不得包含额外JSON值");
            }
        } catch (ApplicationDraftContractViolation exception) {
            throw exception;
        } catch (JacksonException exception) {
            throw reject(ApplicationDraftContractViolation.Reason.INVALID_JSON,
                    "$", "应用草稿不是合法JSON", exception);
        }
    }

    /** 拒绝U+0000和未配对UTF-16代理项，保留合法补充平面字符。 */
    private static void requireValidUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\0') {
                throw reject(ApplicationDraftContractViolation.Reason.INVALID_UNICODE,
                        "$", "应用草稿字符串或字段名不得包含U+0000");
            }
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw reject(ApplicationDraftContractViolation.Reason.INVALID_UNICODE,
                            "$", "应用草稿字符串或字段名包含未配对代理项");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw reject(ApplicationDraftContractViolation.Reason.INVALID_UNICODE,
                        "$", "应用草稿字符串或字段名包含未配对代理项");
            }
        }
    }

    /** 在原文审计成功后建立对象根，不接受Jackson静默忽略尾随内容。 */
    private ObjectNode readRoot(byte[] source) {
        try {
            return (ObjectNode) objectReader.readTree(source);
        } catch (JacksonException exception) {
            throw reject(ApplicationDraftContractViolation.Reason.INVALID_JSON,
                    "$", "应用草稿不是合法JSON", exception);
        }
    }

    /** 校验content封闭字段、原子值和内部引用。 */
    private static void validateContent(ObjectNode content) {
        requireExactFields(content, "$", CONTENT_FIELDS);
        String formatVersion = requireString(content, "formatVersion", "$");
        if (!FORMAT_VERSION.equals(formatVersion)) {
            throw invalid("$.formatVersion", "应用草稿格式版本未登记");
        }
        requireTitle(content, "displayName", "$");
        validateHostRange(requireObject(content, "hostCompatibility", "$"));
        ArrayNode references = requireArray(content, "dashboardRefs", "$");
        if (references.size() > MAXIMUM_DASHBOARD_REFERENCES) {
            throw reject(ApplicationDraftContractViolation.Reason.INVALID_COLLECTION,
                    "$.dashboardRefs", "应用草稿最多引用5个Dashboard");
        }
        Set<String> dashboardIds = new HashSet<>();
        for (int index = 0; index < references.size(); index++) {
            String path = "$.dashboardRefs[" + index + "]";
            JsonNode value = references.get(index);
            if (value == null || value.isNull()) {
                throw reject(ApplicationDraftContractViolation.Reason.NULL_NOT_ALLOWED, path, "Dashboard引用不得为null");
            }
            if (!value.isObject()) {
                throw reject(ApplicationDraftContractViolation.Reason.TYPE_MISMATCH, path, "Dashboard引用必须是对象");
            }
            ObjectNode reference = (ObjectNode) value;
            requireExactFields(reference, path, DASHBOARD_REFERENCE_FIELDS);
            String dashboardId = requireUuid(reference, "dashboardId", path);
            requireUuid(reference, "dashboardVersionId", path);
            requireTitle(reference, "title", path);
            if (!dashboardIds.add(dashboardId)) {
                throw reject(ApplicationDraftContractViolation.Reason.INVALID_COLLECTION,
                        path + ".dashboardId", "稳定dashboardId不得重复");
            }
        }
        JsonNode entry = content.get("entryDashboardId");
        if (entry == null) {
            throw reject(ApplicationDraftContractViolation.Reason.REQUIRED_FIELD_MISSING,
                    "$.entryDashboardId", "entryDashboardId为必填字段");
        }
        if (!entry.isNull()) {
            String entryId = requireUuidNode(entry, "$.entryDashboardId");
            if (!dashboardIds.contains(entryId)) {
                throw reject(ApplicationDraftContractViolation.Reason.INVALID_REFERENCE,
                        "$.entryDashboardId", "入口Dashboard必须命中草稿引用");
            }
        } else if (!references.isEmpty()) {
            throw reject(ApplicationDraftContractViolation.Reason.INVALID_REFERENCE,
                    "$.entryDashboardId", "非空Dashboard引用必须指定入口Dashboard");
        }
    }

    /** 校验有限宿主范围的封闭字段、SemVer及严格递增关系。 */
    private static void validateHostRange(ObjectNode range) {
        String path = "$.hostCompatibility";
        requireExactFields(range, path, HOST_RANGE_FIELDS);
        int[] minimum = requireSemVer(range, "minInclusive", path);
        int[] maximum = requireSemVer(range, "maxExclusive", path);
        for (int index = 0; index < minimum.length; index++) {
            if (minimum[index] < maximum[index]) return;
            if (minimum[index] > maximum[index]) break;
        }
        throw invalid(path, "minInclusive必须严格小于maxExclusive");
    }

    /** 要求对象只包含允许字段，并分别报告未知、缺失和null。 */
    private static void requireExactFields(ObjectNode object, String path, Set<String> fields) {
        object.propertyNames().forEach(name -> {
            if (!fields.contains(name)) {
                throw reject(ApplicationDraftContractViolation.Reason.UNKNOWN_FIELD,
                        path + ".[\"<unknown>\"]", "对象包含合同未声明字段");
            }
        });
        for (String field : fields) {
            JsonNode value = object.get(field);
            if (value == null) {
                throw reject(ApplicationDraftContractViolation.Reason.REQUIRED_FIELD_MISSING,
                        path + "." + field, "缺少必填字段");
            }
            if (value.isNull() && !"entryDashboardId".equals(field)) {
                throw reject(ApplicationDraftContractViolation.Reason.NULL_NOT_ALLOWED,
                        path + "." + field, "字段不接受null");
            }
        }
    }

    /** 从对象读取严格字符串字段。 */
    private static String requireString(ObjectNode object, String field, String path) {
        JsonNode value = object.get(field);
        if (value == null) {
            throw reject(ApplicationDraftContractViolation.Reason.REQUIRED_FIELD_MISSING,
                    path + "." + field, "缺少必填字段");
        }
        if (value.isNull()) {
            throw reject(ApplicationDraftContractViolation.Reason.NULL_NOT_ALLOWED,
                    path + "." + field, "字段不接受null");
        }
        if (!value.isString()) {
            throw reject(ApplicationDraftContractViolation.Reason.TYPE_MISMATCH,
                    path + "." + field, "字段必须是字符串");
        }
        return value.asString();
    }

    /** 从对象读取严格对象字段。 */
    private static ObjectNode requireObject(ObjectNode object, String field, String path) {
        JsonNode value = object.get(field);
        if (value == null) {
            throw reject(ApplicationDraftContractViolation.Reason.REQUIRED_FIELD_MISSING,
                    path + "." + field, "缺少必填字段");
        }
        if (value.isNull()) {
            throw reject(ApplicationDraftContractViolation.Reason.NULL_NOT_ALLOWED,
                    path + "." + field, "字段不接受null");
        }
        if (!value.isObject()) {
            throw reject(ApplicationDraftContractViolation.Reason.TYPE_MISMATCH,
                    path + "." + field, "字段必须是对象");
        }
        return (ObjectNode) value;
    }

    /** 从对象读取严格数组字段。 */
    private static ArrayNode requireArray(ObjectNode object, String field, String path) {
        JsonNode value = object.get(field);
        if (value == null) {
            throw reject(ApplicationDraftContractViolation.Reason.REQUIRED_FIELD_MISSING,
                    path + "." + field, "缺少必填字段");
        }
        if (value.isNull()) {
            throw reject(ApplicationDraftContractViolation.Reason.NULL_NOT_ALLOWED,
                    path + "." + field, "字段不接受null");
        }
        if (!value.isArray()) {
            throw reject(ApplicationDraftContractViolation.Reason.TYPE_MISMATCH,
                    path + "." + field, "字段必须是数组");
        }
        return (ArrayNode) value;
    }

    /** 校验Revision十进制字符串并安全转换为long。 */
    private static long requireRevision(String value) {
        if (value == null || !REVISION.matcher(value).matches()) {
            throw invalid("$.expectedRevision", "expectedRevision必须是规范十进制字符串");
        }
        try {
            // Long上限本身是合法持久事实；目标不存在与revision耗尽必须由仓储同一语句原子分类。
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw invalid("$.expectedRevision", "expectedRevision不得超过Long.MAX_VALUE");
        }
    }

    /** 校验Title码点、非空白和C0/C1控制字符边界。 */
    private static String requireTitle(ObjectNode object, String field, String path) {
        String title = requireString(object, field, path);
        int codePoints = title.codePointCount(0, title.length());
        // Java isWhitespace刻意排除NBSP等空格分隔符；Title的“非空白”合同必须同时覆盖Unicode Space_Separator。
        boolean onlyWhitespace = title.codePoints().allMatch(codePoint ->
                Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint));
        boolean containsControl = title.codePoints().anyMatch(codePoint ->
                codePoint <= 0x1F || codePoint >= 0x7F && codePoint <= 0x9F);
        if (codePoints < 1 || codePoints > 80 || onlyWhitespace || containsControl) {
            throw invalid(path + "." + field, "Title必须是1至80码点的非空白无控制字符文本");
        }
        return title;
    }

    /** 从对象读取并校验规范小写UUID。 */
    private static String requireUuid(ObjectNode object, String field, String path) {
        return requireUuidNode(object.get(field), path + "." + field);
    }

    /** 校验UUID字符串节点的规范文本和真实语法。 */
    private static String requireUuidNode(JsonNode value, String path) {
        if (value == null) {
            throw reject(ApplicationDraftContractViolation.Reason.REQUIRED_FIELD_MISSING, path, "缺少必填UUID");
        }
        if (value.isNull()) {
            throw reject(ApplicationDraftContractViolation.Reason.NULL_NOT_ALLOWED, path, "UUID不得为null");
        }
        if (!value.isString()) {
            throw reject(ApplicationDraftContractViolation.Reason.TYPE_MISMATCH, path, "UUID必须是字符串");
        }
        String text = value.asString();
        try {
            if (!UUID_TEXT.matcher(text).matches() || !UUID.fromString(text).toString().equals(text)) {
                throw new IllegalArgumentException("UUID文本不规范");
            }
        } catch (IllegalArgumentException exception) {
            throw invalid(path, "UUID必须使用规范小写文本");
        }
        return text;
    }

    /** 从对象读取并校验受限SemVer，返回三段整数用于范围比较。 */
    private static int[] requireSemVer(ObjectNode object, String field, String path) {
        String text = requireString(object, field, path);
        Matcher matcher = SEMVER.matcher(text);
        if (!matcher.matches()) {
            throw invalid(path + "." + field, "SemVer必须是无前导零的major.minor.patch");
        }
        int[] segments = new int[3];
        for (int index = 0; index < segments.length; index++) {
            String segment = matcher.group(index + 1);
            if (segment.length() > 5 || Integer.parseInt(segment) > 65_535) {
                throw invalid(path + "." + field, "SemVer每段不得超过65535");
            }
            segments[index] = Integer.parseInt(segment);
        }
        return segments;
    }

    /**
     * 计算PostgreSQL jsonb输出文本的UTF-8字节数。
     *
     * <p>对象键顺序不会改变总字节数；jsonb在冒号和逗号后各输出一个ASCII空格，字符串按JSON规则
     * 转义引号、反斜线和C0控制字符。合法应用草稿没有数字字段，其他标量仅用于在结构拒绝前保持总量保护。</p>
     */
    private static long postgresJsonbTextUtf8Length(JsonNode node) {
        if (node.isObject()) {
            long length = 2;
            int[] count = {0};
            for (var property : node.properties()) {
                if (count[0]++ > 0) length += 2;
                length += quotedUtf8Length(property.getKey()) + 2 + postgresJsonbTextUtf8Length(property.getValue());
            }
            return length;
        }
        if (node.isArray()) {
            long length = 2;
            for (int index = 0; index < node.size(); index++) {
                if (index > 0) length += 2;
                length += postgresJsonbTextUtf8Length(node.get(index));
            }
            return length;
        }
        if (node.isString()) return quotedUtf8Length(node.asString());
        if (node.isNull()) return 4;
        return node.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /** 计算PostgreSQL JSON字符串输出含引号和必要转义后的UTF-8字节数。 */
    private static long quotedUtf8Length(String value) {
        long length = 2;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '"' || codePoint == '\\' || codePoint == '\b' || codePoint == '\f'
                    || codePoint == '\n' || codePoint == '\r' || codePoint == '\t') {
                length += 2;
            } else if (codePoint <= 0x1F) {
                length += 6;
            } else if (codePoint <= 0x7F) {
                length++;
            } else if (codePoint <= 0x7FF) {
                length += 2;
            } else if (codePoint <= 0xFFFF) {
                length += 3;
            } else {
                length += 4;
            }
        }
        return length;
    }

    /** 创建无底层原因的稳定合同拒绝。 */
    private static ApplicationDraftContractViolation reject(
            ApplicationDraftContractViolation.Reason reason, String path, String message) {
        return new ApplicationDraftContractViolation(reason, path, message);
    }

    /** 创建保留底层原因的稳定合同拒绝。 */
    private static ApplicationDraftContractViolation reject(
            ApplicationDraftContractViolation.Reason reason, String path, String message, Throwable cause) {
        return new ApplicationDraftContractViolation(reason, path, message, cause);
    }

    /** 创建标量或范围无效的稳定拒绝。 */
    private static ApplicationDraftContractViolation invalid(String path, String message) {
        return reject(ApplicationDraftContractViolation.Reason.INVALID_VALUE, path, message);
    }

    /** token流容器帧；对象帧保存解码后字段集合，数组帧只维持类型。 */
    private static final class ContainerFrame {
        /** 对象字段集合；数组帧为null。 */
        private final Set<String> fields;

        /** 创建指定类型的容器帧。 */
        private ContainerFrame(Set<String> fields) {
            this.fields = fields;
        }

        /** @return 启用字段判重的对象帧 */
        private static ContainerFrame object() {
            return new ContainerFrame(new HashSet<>());
        }

        /** @return 不启用字段判重的数组帧 */
        private static ContainerFrame array() {
            return new ContainerFrame(null);
        }

        /**
         * 登记对象字段；数组收到属性名表示解析器状态异常。
         *
         * @param field 解码后的字段名
         * @return 首次出现时为true
         */
        private boolean register(String field) {
            return Objects.requireNonNull(fields, "数组不能登记字段").add(field);
        }
    }
}
