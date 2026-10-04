package com.things.link.dashboard.infrastructure.schema;

import com.things.link.dashboard.application.DashboardSchemaContractVersion;
import com.things.link.dashboard.application.schema.DashboardSchemaParseException;
import com.things.link.dashboard.application.schema.DashboardSchemaParser;
import com.things.link.dashboard.application.schema.ParsedDashboardSchema;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于Jackson流解析器的看板Schema原文解析实现。
 *
 * <p>S12-0b1第2.1节的重复键、数字词法和Unicode规则必须在JSON树覆盖原文信息之前完成；
 * 本实现先逐token审计，再建立供后续业务切片消费的树。</p>
 */
@Component
public final class JacksonDashboardSchemaParser implements DashboardSchemaParser {
    /** 完整解析前允许接收的最大UTF-8字节数。 */
    public static final int MAX_RAW_BYTES = 512_000;
    /** 根对象计为第一层时允许的最大对象或数组深度。 */
    public static final int MAX_DEPTH = 16;
    /** 单个JSON数字原始词法允许的最大ASCII字节数。 */
    public static final int MAX_NUMBER_TOKEN_BYTES = 64;
    /** UTF-8 BOM的首字节。 */
    private static final int UTF8_BOM_FIRST = 0xEF;
    /** UTF-8 BOM的第二字节。 */
    private static final int UTF8_BOM_SECOND = 0xBB;
    /** UTF-8 BOM的第三字节。 */
    private static final int UTF8_BOM_THIRD = 0xBF;
    /** 提取科学计数法指数部分，底数词法仍交给严格JSON解析器校验。 */
    private static final Pattern EXPONENT = Pattern.compile("[eE]([+-]?)([0-9]+)$");
    /**
     * 允许出现在结构化路径中的合同字段名。
     *
     * <p>未登记字段统一折叠为固定占位，防止攻击者用控制字符或超长字段污染日志与错误响应。</p>
     */
    private static final Set<String> KNOWN_PATH_FIELDS = Set.of(
            "schemaVersion", "presentation", "models", "variables", "pages", "mode", "theme", "columns",
            "rowHeight", "gap", "width", "height", "scaleMode", "key", "versionId", "digestAlgorithm",
            "digest", "profile", "type", "title", "required", "modelKey", "defaultDeviceId", "maxItems",
            "defaultDeviceIds", "defaultPreset", "allowedPresets", "options", "defaultValue", "value", "label",
            "id", "components", "kind", "componentVersion", "layout", "props", "bindings", "x", "y", "w",
            "h", "content", "align", "size", "tone", "text", "resourceId", "resourceDigest", "alt", "fit",
            "precision", "unitMode", "showLastOnlineAt", "status", "min", "max", "showLegend", "series",
            "device", "propertyKey", "timeRangeVariableKey", "granularity", "aggregation", "rowLimit",
            "initialExpandDepth", "pageSize", "showClearedAt", "alarms", "devices", "conditionStates",
            "ackStates", "severities", "placeholder", "directory", "variableKey", "source");
    /** JSON读取器既创建流解析器，也只在原文token全部通过之后创建JSON树。 */
    private final ObjectReader objectReader;

    /** 创建使用严格Jackson JSON默认值的解析器。 */
    public JacksonDashboardSchemaParser() {
        // 将Jackson的数字长度保护提升到原文总上限，避免其默认1000位先于本合同的64字节稳定原因抛错；
        // inspectNumber仍在数值建树前执行真正的合同上限，因此不会让超长数字进入任意精度转换。
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNumberLength(MAX_RAW_BYTES)
                .build();
        // 默认工厂不启用非标准数字等宽松扩展，非法词法必须在原文阶段直接失败。
        JsonFactory jsonFactory = JsonFactory.builder().streamReadConstraints(constraints).build();
        // ConfigNumber必须按原始十进制精度判断；先转double会把越界值舍入回合法边界。
        this.objectReader = JsonMapper.builder(jsonFactory)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build()
                .reader();
    }

    /** {@inheritDoc} */
    @Override
    public ParsedDashboardSchema parse(byte[] source) {
        byte[] snapshot = snapshotRawInput(source);
        // 先使用REPORT解码器证明输入是严格UTF-8；Jackson不得用替换字符掩盖非法字节。
        requireValidUtf8(snapshot);
        inspectTokens(snapshot);
        try {
            JsonNode root = objectReader.readTree(snapshot);
            JsonNode versionNode = root.get("schemaVersion");
            if (versionNode == null || !versionNode.isString()) {
                throw reject(DashboardSchemaParseException.Reason.VERSION_REQUIRED,
                        "$.schemaVersion", "看板Schema必须声明字符串类型的schemaVersion");
            }
            if (!DashboardSchemaContractVersion.V1.value().equals(versionNode.asString())) {
                throw reject(DashboardSchemaParseException.Reason.VERSION_UNSUPPORTED,
                        "$.schemaVersion", "看板Schema版本未登记");
            }
            return new ParserResult(DashboardSchemaContractVersion.V1, root);
        } catch (DashboardSchemaParseException exception) {
            throw exception;
        } catch (JacksonException exception) {
            // token审计和建树使用同一工厂；到达此分支表示底层解析器仍发现了结构错误。
            throw reject(DashboardSchemaParseException.Reason.INVALID_JSON, "$", "看板Schema不是合法JSON", exception);
        }
    }

    /** 在任何内容检查前冻结调用方输入，并在同一快照上执行字节上限与BOM检查。 */
    private static byte[] snapshotRawInput(byte[] source) {
        if (source == null) {
            throw reject(DashboardSchemaParseException.Reason.INVALID_JSON, "$", "看板Schema原文不能为空");
        }
        if (source.length > MAX_RAW_BYTES) {
            throw reject(DashboardSchemaParseException.Reason.RAW_TOO_LARGE,
                    "$", "看板Schema原文超过512000字节上限");
        }
        // 长度是数组固有属性；复制后所有内容检查都只消费快照，关闭检查与建树之间的竞态。
        byte[] snapshot = source.clone();
        if (snapshot.length >= 3
                && Byte.toUnsignedInt(snapshot[0]) == UTF8_BOM_FIRST
                && Byte.toUnsignedInt(snapshot[1]) == UTF8_BOM_SECOND
                && Byte.toUnsignedInt(snapshot[2]) == UTF8_BOM_THIRD) {
            throw reject(DashboardSchemaParseException.Reason.BOM_NOT_ALLOWED, "$", "看板Schema不得包含UTF-8 BOM");
        }
        return snapshot;
    }

    /** 使用REPORT策略拒绝所有畸形或不可映射的UTF-8输入。 */
    private static void requireValidUtf8(byte[] source) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(source));
        } catch (CharacterCodingException exception) {
            throw reject(DashboardSchemaParseException.Reason.INVALID_UTF8,
                    "$", "看板Schema原文不是合法UTF-8", exception);
        }
    }

    /** 在信息仍保留于token流时检查嵌套、重复键、字符串和数字词法。 */
    private void inspectTokens(byte[] source) {
        String failurePath = "$";
        try (JsonParser parser = objectReader.createParser(source)) {
            JsonToken first = parser.nextToken();
            if (first != JsonToken.START_OBJECT) {
                throw reject(DashboardSchemaParseException.Reason.ROOT_MUST_BE_OBJECT,
                        "$", "看板Schema根值必须是JSON对象");
            }
            Deque<ContainerPathFrame> frames = new ArrayDeque<>();
            int depth = 0;
            boolean rootClosed = false;
            JsonToken token = first;
            do {
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    String valuePath = consumeValuePath(frames);
                    depth++;
                    if (depth > MAX_DEPTH) {
                        throw reject(DashboardSchemaParseException.Reason.DEPTH_EXCEEDED,
                                valuePath, "看板Schema对象或数组嵌套超过16层");
                    }
                    frames.push(token == JsonToken.START_OBJECT
                            ? ContainerPathFrame.object(valuePath)
                            : ContainerPathFrame.array(valuePath, parser.currentLocation().getByteOffset()));
                    failurePath = valuePath;
                } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    String completedPath = frames.isEmpty() ? "$" : frames.pop().path();
                    depth--;
                    rootClosed = depth == 0;
                    markValueCompleted(frames, parser.currentLocation().getByteOffset());
                    failurePath = frames.isEmpty() ? completedPath : frames.peek().path();
                } else if (token == JsonToken.PROPERTY_NAME) {
                    ContainerPathFrame objectFrame = frames.peek();
                    // 属性名可能在getString时才完成转义解码，先锁定所属对象，避免沿用前一个值路径。
                    failurePath = objectFrame.path();
                    inspectPropertyName(parser.getString(), objectFrame);
                    failurePath = objectFrame.pendingPropertyPath();
                } else if (token == JsonToken.VALUE_STRING) {
                    String valuePath = consumeValuePath(frames);
                    // Jackson可能延迟解码字符串；调用getString前必须锁定本值路径。
                    failurePath = valuePath;
                    requireValidUnicode(parser.getString(), valuePath);
                    markValueCompleted(frames, parser.currentLocation().getByteOffset());
                    failurePath = frames.isEmpty() ? "$" : frames.peek().path();
                } else if (token.isNumeric()) {
                    String valuePath = consumeValuePath(frames);
                    // 数字文本同样可能延迟物化，先锁定本值路径再读取词法。
                    failurePath = valuePath;
                    inspectNumber(parser.getString(), valuePath);
                    markValueCompleted(frames, parser.currentLocation().getByteOffset());
                    failurePath = frames.isEmpty() ? "$" : frames.peek().path();
                } else if (token.isScalarValue()) {
                    consumeValuePath(frames);
                    markValueCompleted(frames, parser.currentLocation().getByteOffset());
                    failurePath = frames.isEmpty() ? "$" : frames.peek().path();
                }
                try {
                    token = parser.nextToken();
                } catch (JacksonException exception) {
                    String path = parserFailurePath(
                            parser, frames, failurePath, source, parser.currentLocation().getByteOffset());
                    if (containsInvalidUnicodeEscape(source, exception)) {
                        throw reject(DashboardSchemaParseException.Reason.INVALID_UNICODE,
                                path, "看板Schema字符串包含未配对UTF-16代理项", exception);
                    }
                    throw reject(DashboardSchemaParseException.Reason.INVALID_JSON,
                            path, "看板Schema不是合法JSON", exception);
                }
            } while (!rootClosed && token != null);
            if (!rootClosed) {
                throw reject(DashboardSchemaParseException.Reason.INVALID_JSON,
                        failurePath, "看板Schema JSON结构未闭合");
            }
            if (token != null) {
                throw reject(DashboardSchemaParseException.Reason.TRAILING_VALUE,
                        "$", "看板Schema根对象后不得包含额外JSON值");
            }
        } catch (DashboardSchemaParseException exception) {
            throw exception;
        } catch (JacksonException exception) {
            if (containsInvalidUnicodeEscape(source, exception)) {
                throw reject(DashboardSchemaParseException.Reason.INVALID_UNICODE,
                        failurePath, "看板Schema字符串包含未配对UTF-16代理项", exception);
            }
            throw reject(DashboardSchemaParseException.Reason.INVALID_JSON,
                    failurePath, "看板Schema不是合法JSON", exception);
        }
    }

    /** 解码属性名后检查Unicode并在当前对象域中判重。 */
    private static void inspectPropertyName(String name, ContainerPathFrame frame) {
        requireValidUnicode(name, frame.path());
        String propertyPath = appendProperty(frame.path(), name);
        if (!frame.objectKeys().add(name)) {
            throw reject(DashboardSchemaParseException.Reason.DUPLICATE_KEY,
                    propertyPath, "看板Schema同一对象包含重复属性名");
        }
        frame.pendingPropertyPath(propertyPath);
    }

    /** 拒绝U+0000及任何未成对UTF-16代理项，同时保留合法补充平面字符。 */
    private static void requireValidUnicode(String value, String path) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\0') {
                throw reject(DashboardSchemaParseException.Reason.INVALID_UNICODE,
                        path, "看板Schema字符串不得包含U+0000");
            }
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw reject(DashboardSchemaParseException.Reason.INVALID_UNICODE,
                            path, "看板Schema字符串包含未配对UTF-16代理项");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw reject(DashboardSchemaParseException.Reason.INVALID_UNICODE,
                        path, "看板Schema字符串包含未配对UTF-16代理项");
            }
        }
    }

    /** 在转换数值前检查原始ASCII token长度及有界指数语法。 */
    private static void inspectNumber(String token, String path) {
        if (token.length() > MAX_NUMBER_TOKEN_BYTES) {
            throw reject(DashboardSchemaParseException.Reason.NUMBER_TOO_LONG,
                    path, "看板Schema数字词法超过64字节上限");
        }
        Matcher matcher = EXPONENT.matcher(token);
        if (!matcher.find()) {
            return;
        }
        String digits = matcher.group(2);
        if (digits.length() > 2 || digits.length() > 1 && digits.charAt(0) == '0'
                || Integer.parseInt(digits) > 12) {
            throw reject(DashboardSchemaParseException.Reason.INVALID_EXPONENT,
                    path, "看板Schema数字指数必须为无多余前导零且绝对值不超过12的一至两位整数");
        }
    }

    /** 为已知字段生成点路径，并把未知字段折叠为固定安全占位。 */
    private static String appendProperty(String parent, String name) {
        return KNOWN_PATH_FIELDS.contains(name) ? parent + "." + name : parent + "[\"<unknown>\"]";
    }

    /** 在token首次出现时消费它在父容器中的路径，保证数组索引与属性路径只推进一次。 */
    private static String consumeValuePath(Deque<ContainerPathFrame> frames) {
        if (frames.isEmpty()) return "$";
        return frames.peek().consumeValuePath();
    }

    /** 从安全帧和Jackson已消费字节边界恢复尚未形成token的结构错误位置。 */
    private static String parserFailurePath(
            JsonParser parser,
            Deque<ContainerPathFrame> frames,
            String fallback,
            byte[] source,
            long consumedByteOffset) {
        if (frames.isEmpty()) return fallback;
        ContainerPathFrame frame = frames.peek();
        if (frame.objectKeys != null) {
            if (frame.pendingPropertyPath != null) return frame.pendingPropertyPath;
            // 只有PROPERTY_NAME仍在当前token时才恢复字段；已完成值后的错误必须归当前对象。
            if (parser.currentToken() != JsonToken.PROPERTY_NAME) return frame.path;
            // streamReadContext只返回当前对象字段；parser.currentName会继承父字段并造成重复追加。
            String currentName = parser.streamReadContext().currentName();
            return currentName == null ? frame.path : appendProperty(frame.path, currentName);
        }
        return frame.failurePath(source, consumedByteOffset);
    }

    /** 记录父数组的完整值结束字节；对象字段路径不需要分隔符状态。 */
    private static void markValueCompleted(Deque<ContainerPathFrame> frames, long endByteOffset) {
        if (!frames.isEmpty()) frames.peek().markValueCompleted(endByteOffset);
    }

    /**
     * 用异常字节位置之前的原始转义判断代理项错误，避免绑定Jackson异常消息文本。
     *
     * <p>只检查Jackson已消费到的位置，因此更晚出现的代理项不会抢占更早的JSON结构错误。</p>
     */
    private static boolean containsInvalidUnicodeEscape(byte[] source, JacksonException exception) {
        long byteOffset = exception.getLocation() == null ? -1 : exception.getLocation().getByteOffset();
        int limit = byteOffset < 0 ? source.length : (int) Math.min(source.length, byteOffset + 1);
        boolean inString = false;
        for (int index = 0; index < limit; index++) {
            int current = Byte.toUnsignedInt(source[index]);
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (!inString || current != '\\' || index + 1 >= limit) continue;
            int escape = Byte.toUnsignedInt(source[++index]);
            if (escape != 'u' || index + 4 >= limit) continue;
            int scalar = hexadecimalCodeUnit(source, index + 1);
            if (scalar < 0) continue;
            index += 4;
            if (Character.isLowSurrogate((char) scalar)) return true;
            if (!Character.isHighSurrogate((char) scalar)) continue;
            int lowEscapeStart = index + 1;
            if (lowEscapeStart + 1 >= source.length
                    || source[lowEscapeStart] != '\\' || source[lowEscapeStart + 1] != 'u') return true;
            int low = hexadecimalCodeUnit(source, lowEscapeStart + 2);
            // 第二个转义存在但词法不完整或含非十六进制字符时属于JSON词法错误。
            if (low < 0) return false;
            if (!Character.isLowSurrogate((char) low)) return true;
            index = lowEscapeStart + 5;
        }
        return false;
    }

    /** 把四个ASCII十六进制数字转换为UTF-16码元，非法词法返回负数交由Jackson分类。 */
    private static int hexadecimalCodeUnit(byte[] source, int offset) {
        if (offset + 4 > source.length) return -1;
        int value = 0;
        for (int index = offset; index < offset + 4; index++) {
            int digit = Character.digit((char) Byte.toUnsignedInt(source[index]), 16);
            if (digit < 0) return -1;
            value = value * 16 + digit;
        }
        return value;
    }

    /** 创建稳定原因的解析拒绝异常。 */
    private static DashboardSchemaParseException reject(
            DashboardSchemaParseException.Reason reason, String path, String message) {
        return DashboardSchemaParseException.atPath(reason, path, message);
    }

    /** 创建保留底层原因的解析拒绝异常。 */
    private static DashboardSchemaParseException reject(
            DashboardSchemaParseException.Reason reason, String path, String message, Throwable cause) {
        return new DashboardSchemaParseException(reason, path, message, cause);
    }

    /**
     * token流中的容器路径帧，同时保存对象待消费属性或数组下一个元素位置。
     *
     * <p>对象字段的原始名称只留在判重集合；对外路径只保存白名单字段或固定占位。</p>
     */
    private static final class ContainerPathFrame {
        /** 当前容器自身的安全路径。 */
        private final String path;
        /** 对象字段判重集合；数组帧为null。 */
        private final Set<String> objectKeys;
        /** 数组下一个待消费元素下标。 */
        private int nextArrayIndex;
        /** 数组最近一个完整元素后的字节位置；尚无完整元素时为负数。 */
        private long completedArrayValueEndOffset = -1;
        /** 数组左方括号后的内容起始字节；对象帧为负数。 */
        private final long arrayContentStartOffset;
        /** 对象当前属性对应的安全值路径。 */
        private String pendingPropertyPath;

        /** 创建对象或数组路径帧。 */
        private ContainerPathFrame(String path, Set<String> objectKeys, long arrayContentStartOffset) {
            this.path = path;
            this.objectKeys = objectKeys;
            this.arrayContentStartOffset = arrayContentStartOffset;
        }

        /** 创建对象帧并启用当前对象域判重。 */
        private static ContainerPathFrame object(String path) {
            return new ContainerPathFrame(path, new HashSet<>(), -1);
        }

        /** 创建数组帧并从零开始分配元素下标。 */
        private static ContainerPathFrame array(String path, long contentStartOffset) {
            return new ContainerPathFrame(path, null, contentStartOffset);
        }

        /** 返回当前容器路径。 */
        private String path() {
            return path;
        }

        /** 返回对象字段判重集合；仅对象帧调用。 */
        private Set<String> objectKeys() {
            if (objectKeys == null) throw new IllegalStateException("数组帧没有对象字段集合");
            return objectKeys;
        }

        /** 返回尚未消费的对象属性路径。 */
        private String pendingPropertyPath() {
            return pendingPropertyPath == null ? path : pendingPropertyPath;
        }

        /** 登记尚未消费的对象属性路径。 */
        private void pendingPropertyPath(String value) {
            if (objectKeys == null) throw new IllegalStateException("数组帧不能登记属性路径");
            pendingPropertyPath = value;
        }

        /** 消费一个直接子值路径，并推进对象或数组的内部位置。 */
        private String consumeValuePath() {
            if (objectKeys != null) {
                if (pendingPropertyPath == null) throw new IllegalStateException("对象值缺少属性路径");
                String value = pendingPropertyPath;
                pendingPropertyPath = null;
                return value;
            }
            String value = path + "[" + nextArrayIndex++ + "]";
            return value;
        }

        /** 记录数组值结束边界；对象帧无需追踪逗号。 */
        private void markValueCompleted(long endByteOffset) {
            if (objectKeys == null) completedArrayValueEndOffset = endByteOffset;
        }

        /**
         * 依据完整值后的原文字节区分缺失下一元素与错误分隔符或错误token。
         *
         * <p>逗号后的词法错误属于下一元素；只有true、false或null已完整形成后出现多余字符时，
         * TS真值才会先完成该元素并把后续分隔符错误归数组容器。</p>
         */
        private String failurePath(byte[] source, long consumedByteOffset) {
            if (objectKeys != null) throw new IllegalStateException("对象帧没有数组失败路径");
            int limit = consumedByteOffset < 0
                    ? source.length : (int) Math.min(source.length, consumedByteOffset);
            if (completedArrayValueEndOffset < 0) {
                int firstValueStart = (int) Math.min(source.length, arrayContentStartOffset);
                while (firstValueStart < limit && isJsonWhitespace(source[firstValueStart])) firstValueStart++;
                return hasCompletedLiteral(source, firstValueStart, limit) ? path : nextElementPath();
            }
            int cursor = (int) Math.min(source.length, completedArrayValueEndOffset);
            while (cursor < limit && isJsonWhitespace(source[cursor])) cursor++;
            if (cursor >= limit || source[cursor] != ',') return path;
            cursor++;
            while (cursor < limit && isJsonWhitespace(source[cursor])) cursor++;
            return hasCompletedLiteral(source, cursor, limit) ? path : nextElementPath();
        }

        /** 返回当前数组下一元素的安全路径。 */
        private String nextElementPath() {
            return path + "[" + nextArrayIndex + "]";
        }
    }

    /** JSON只允许四种ASCII空白，本地扫描不得接受更宽松的Unicode空白。 */
    private static boolean isJsonWhitespace(byte value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n';
    }

    /** 判断失败词法前是否已经完整形成TS会先接受的固定值。 */
    private static boolean hasCompletedLiteral(byte[] source, int offset, int limit) {
        return startsWithAscii(source, offset, limit, "true")
                || startsWithAscii(source, offset, limit, "false")
                || startsWithAscii(source, offset, limit, "null");
    }

    /** 在已消费边界内比较ASCII固定词法，不读取异常之后的原文。 */
    private static boolean startsWithAscii(byte[] source, int offset, int limit, String expected) {
        if (offset + expected.length() > limit) return false;
        for (int index = 0; index < expected.length(); index++) {
            if (source[offset + index] != expected.charAt(index)) return false;
        }
        return true;
    }

    /** 只能由通过全部原文守卫的Jackson解析流程创建的隔离结果。 */
    private static final class ParserResult implements ParsedDashboardSchema {
        /** 已识别的合同版本。 */
        private final DashboardSchemaContractVersion contractVersion;
        /** 供语义管线读取的隔离JSON树。 */
        private final JsonNode root;

        /**
         * 创建Jackson解析器私有结果，关闭公开构造绕过路径。
         *
         * @param contractVersion 已识别合同版本
         * @param root 已解析JSON根
         */
        private ParserResult(DashboardSchemaContractVersion contractVersion, JsonNode root) {
            this.contractVersion = Objects.requireNonNull(contractVersion, "contractVersion");
            this.root = Objects.requireNonNull(root, "root").deepCopy();
        }

        /** {@inheritDoc} */
        @Override
        public DashboardSchemaContractVersion contractVersion() {
            return contractVersion;
        }

        /** {@inheritDoc} */
        @Override
        public JsonNode root() {
            return root.deepCopy();
        }
    }
}
