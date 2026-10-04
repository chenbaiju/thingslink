package com.things.link.simulator.application.ota.contract;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 设备侧 RFC8785 受限子集规范 JSON，只覆盖 OTA 合同实际使用的类型。
 *
 * <p><b>为什么模拟器自己实现而不复用平台实现：</b>设备侧合同必须在没有平台进程、没有平台
 * 类路径的情况下独立成立；生产代码依赖 OTA 模块会违反架构文档 10.4 的跨模块规则，也会让
 * 「设备能不能解析平台报文」变成一个只有平台在线的假设。{@code things-link-shared} 里没有
 * 通用规范 JSON 助手，因此本类是设备侧唯一实现。</p>
 *
 * <p><b>为什么与平台实现逐条对齐：</b>平台对完整规范字节计算摘要并签名，因此任何一处
 * 「排序、空白、数字或转义」差异都会让设备发出的字节在平台上摘要不符。规则固定为：
 * 只允许非负安全整数（{@code 0..9007199254740991}），禁止 {@code null} 与浮点；字段按
 * UTF-16 码元排序；字符串不做 Unicode 规范化；根容器是对象；输出无任何多余空白。</p>
 *
 * <p>输入与输出各有 64KiB 上限，单个字符串 16KiB，容器深度 32，容器/标量/字段名共享
 * 4096 个节点预算；错误统一为不携带原文的 {@link IllegalArgumentException}，避免把可疑
 * 报文内容写进日志。</p>
 */
public final class OtaCanonicalJson {

    /** 输入与规范化输出各自允许的最大字节数。 */
    private static final int MAX_BYTES = 65_536;

    /** 字段名或字符串值解码后的最大 UTF-8 字节数。 */
    private static final int MAX_STRING_BYTES = 16_384;

    /** 包含根对象的最大容器嵌套层数。 */
    private static final int MAX_DEPTH = 32;

    /** 容器、标量和字段名共享的最大节点总数。 */
    private static final int MAX_NODES = 4096;

    /** 能够精确表示且不触发指数格式的最大非负安全整数。 */
    private static final long MAX_INTEGER = 9_007_199_254_740_991L;

    /**
     * 严格解析 UTF-8 对象；只接受本类子集内的值。
     *
     * @param utf8 RFC8785 受限子集编码的 UTF-8 字节
     * @return 深不可变的字段映射，顺序与输入一致
     * @throws IllegalArgumentException 输入为空、超限、非对象、含 {@code null}/浮点/重复字段时
     */
    public Map<String, Object> parseObject(byte[] utf8) {
        try {
            if (utf8 == null || utf8.length == 0 || utf8.length > MAX_BYTES) {
                throw invalid();
            }
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8)).toString();
            Reader reader = new Reader(json);
            Budget budget = new Budget();
            reader.skipWhitespace();
            Map<String, Object> result = readObject(reader, 1, budget);
            reader.skipWhitespace();
            if (!reader.atEnd()) {
                throw invalid();
            }
            // 转义可以扩大结果：解析成功也必须能在同样的输出预算内重新规范化。
            writeObject(result);
            return result;
        } catch (RuntimeException | CharacterCodingException exception) {
            throw invalid();
        }
    }

    /**
     * 把受限值树编码为规范 UTF-8 字节。
     *
     * @param object 仅含 String/Boolean/Long/Map/List 的对象
     * @return 无空白、字段已排序的规范字节
     * @throws IllegalArgumentException 出现 {@code null}、浮点、越界整数、循环引用或超限时
     */
    public byte[] writeObject(Map<String, Object> object) {
        try {
            if (object == null) {
                throw invalid();
            }
            Output output = new Output();
            write(object, 1, new Budget(), Collections.newSetFromMap(new IdentityHashMap<>()), output);
            return output.bytes.toByteArray();
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    /** 生成不含原文、凭据或底层解析位置的统一外部输入错误。 */
    static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid OTA canonical JSON");
    }

    /** 读取当前对象；重复字段名与未知结构一律失败。 */
    private static Map<String, Object> readObject(Reader reader, int depth, Budget budget) {
        container(depth, budget);
        reader.expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        reader.skipWhitespace();
        if (reader.peek() == '}') {
            reader.advance();
            return Collections.unmodifiableMap(map);
        }
        while (true) {
            reader.skipWhitespace();
            String key = reader.readString();
            budget.node();
            validateString(key);
            if (map.containsKey(key)) {
                throw invalid();
            }
            reader.skipWhitespace();
            reader.expect(':');
            reader.skipWhitespace();
            map.put(key, reader.readValue(depth + 1, budget));
            reader.skipWhitespace();
            char separator = reader.peek();
            if (separator == ',') {
                reader.advance();
                continue;
            }
            if (separator == '}') {
                reader.advance();
                return Collections.unmodifiableMap(map);
            }
            throw invalid();
        }
    }

    /** 递归验证并输出值；祖先身份集合只拒绝循环，允许共享子树。 */
    private static void write(Object value, int depth, Budget budget, Set<Object> ancestors, Output output) {
        if (value instanceof Map<?, ?> map) {
            container(depth, budget);
            if (!ancestors.add(value)) {
                throw invalid();
            }
            List<String> keys = new ArrayList<>();
            Set<String> uniqueKeys = new HashSet<>();
            for (Object key : map.keySet()) {
                budget.node();
                if (!(key instanceof String string) || !uniqueKeys.add(string)) {
                    throw invalid();
                }
                validateString(string);
                keys.add(string);
            }
            keys.sort(String::compareTo);
            output.add("{");
            boolean first = true;
            for (String key : keys) {
                if (!first) {
                    output.add(",");
                }
                first = false;
                quote(key, output);
                output.add(":");
                write(map.get(key), depth + 1, budget, ancestors, output);
            }
            output.add("}");
            ancestors.remove(value);
        } else if (value instanceof List<?> list) {
            container(depth, budget);
            if (!ancestors.add(value)) {
                throw invalid();
            }
            output.add("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    output.add(",");
                }
                first = false;
                write(item, depth + 1, budget, ancestors, output);
            }
            output.add("]");
            ancestors.remove(value);
        } else {
            budget.node();
            if (value instanceof String string) {
                validateString(string);
                quote(string, output);
            } else if (value instanceof Boolean bool) {
                output.add(bool.toString());
            } else if (value instanceof Long number) {
                validateInteger(number);
                output.add(number.toString());
            } else {
                throw invalid();
            }
        }
    }

    /** 按 RFC8785 字符串规则转义，保持合法 Unicode 的原始码元序列。 */
    private static void quote(String value, Output output) {
        StringBuilder quoted = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\b' -> quoted.append("\\b");
                case '\t' -> quoted.append("\\t");
                case '\n' -> quoted.append("\\n");
                case '\f' -> quoted.append("\\f");
                case '\r' -> quoted.append("\\r");
                default -> {
                    if (character < 0x20) {
                        quoted.append("\\u00").append("0123456789abcdef".charAt(character >> 4))
                                .append("0123456789abcdef".charAt(character & 15));
                    } else {
                        quoted.append(character);
                    }
                }
            }
        }
        output.add(quoted.append('"').toString());
    }

    /** 拒绝孤立代理码元及超出 UTF-8 字节预算的字符串。 */
    private static void validateString(String string) {
        if (string.length() > MAX_STRING_BYTES) {
            throw invalid();
        }
        for (int index = 0; index < string.length(); index++) {
            char character = string.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index == string.length() || !Character.isLowSurrogate(string.charAt(index))) {
                    throw invalid();
                }
            } else if (Character.isLowSurrogate(character)) {
                throw invalid();
            }
        }
        if (string.getBytes(StandardCharsets.UTF_8).length > MAX_STRING_BYTES) {
            throw invalid();
        }
    }

    /** 拒绝负数和不能保证精确互操作的整数。 */
    private static void validateInteger(long number) {
        if (number < 0L || number > MAX_INTEGER) {
            throw invalid();
        }
    }

    /** 检查容器嵌套深度并将该容器计入节点总量。 */
    private static void container(int depth, Budget budget) {
        if (depth > MAX_DEPTH) {
            throw invalid();
        }
        budget.node();
    }

    /** 每次解析或输出独享的节点预算，避免调用之间共享计数。 */
    private static final class Budget {

        /** 当前调用已经访问的节点总数。 */
        private int nodes;

        /** 消耗一个节点，超限立即失败。 */
        void node() {
            if (++nodes > MAX_NODES) {
                throw invalid();
            }
        }
    }

    /** 在写入前检查字节预算的规范化结果缓冲区。 */
    private static final class Output {

        /** 只保存预算内的 UTF-8 输出字节。 */
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /** 编码并检查剩余字节预算后追加片段。 */
        void add(String value) {
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            if (encoded.length > MAX_BYTES - bytes.size()) {
                throw invalid();
            }
            bytes.writeBytes(encoded);
        }
    }

    /** 严格受限 JSON 词法读取器；不启用任何宽松特性。 */
    private static final class Reader {

        /** 已严格解码的输入文本。 */
        private final String json;

        /** 下一个待读码元位置。 */
        private int index;

        /**
         * @param json 已按 UTF-8 严格解码的报文文本
         */
        Reader(String json) {
            this.json = json;
        }

        /** @return 是否已经读到文本末尾 */
        boolean atEnd() {
            return index >= json.length();
        }

        /** 跳过 JSON 允许的四种无意义空白。 */
        void skipWhitespace() {
            while (index < json.length()) {
                char character = json.charAt(index);
                if (character == ' ' || character == '\t' || character == '\n' || character == '\r') {
                    index++;
                } else {
                    return;
                }
            }
        }

        /**
         * @return 当前码元，不前进
         * @throws IllegalArgumentException 已经读到末尾
         */
        char peek() {
            if (index >= json.length()) {
                throw invalid();
            }
            return json.charAt(index);
        }

        /**
         * 断言并消费一个固定码元。
         *
         * @param expected 期望码元
         */
        void expect(char expected) {
            if (peek() != expected) {
                throw invalid();
            }
            index++;
        }

        /** 前进一个码元。 */
        void advance() {
            index++;
        }

        /**
         * 读取一个受限 JSON 值。
         *
         * @param depth 该值作为容器时的嵌套深度
         * @param budget 共享节点预算
         * @return 只可能是不可变 Map/List/String/Boolean/Long
         */
        Object readValue(int depth, Budget budget) {
            char character = peek();
            if (character == '{') {
                return readObject(this, depth, budget);
            }
            if (character == '[') {
                return readArray(depth, budget);
            }
            if (character == '"') {
                budget.node();
                String string = readString();
                validateString(string);
                return string;
            }
            if (json.startsWith("true", index)) {
                index += 4;
                budget.node();
                return Boolean.TRUE;
            }
            if (json.startsWith("false", index)) {
                index += 5;
                budget.node();
                return Boolean.FALSE;
            }
            if (character >= '0' && character <= '9') {
                budget.node();
                int start = index;
                while (index < json.length() && json.charAt(index) >= '0' && json.charAt(index) <= '9') {
                    index++;
                }
                String literal = json.substring(start, index);
                if (!literal.matches("0|[1-9][0-9]{0,15}")) {
                    throw invalid();
                }
                long number = Long.parseLong(literal);
                validateInteger(number);
                return number;
            }
            // null、负数、浮点和任何其他字面量都在这里失败关闭。
            throw invalid();
        }

        /** 读取一个受限数组；元素本身走同一套值校验。 */
        private List<Object> readArray(int depth, Budget budget) {
            container(depth, budget);
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                advance();
                return Collections.unmodifiableList(list);
            }
            while (true) {
                skipWhitespace();
                list.add(readValue(depth + 1, budget));
                skipWhitespace();
                char separator = peek();
                if (separator == ',') {
                    advance();
                    continue;
                }
                if (separator == ']') {
                    advance();
                    return Collections.unmodifiableList(list);
                }
                throw invalid();
            }
        }

        /**
         * 读取一个 JSON 字符串，处理全部合法转义并拒绝未转义控制字符。
         *
         * @return 解码后的 Java 字符串（可能含合法代理对）
         */
        String readString() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (true) {
                if (index >= json.length()) {
                    throw invalid();
                }
                char character = json.charAt(index++);
                if (character == '"') {
                    return result.toString();
                }
                if (character == '\\') {
                    if (index >= json.length()) {
                        throw invalid();
                    }
                    char escape = json.charAt(index++);
                    switch (escape) {
                        case '"' -> result.append('"');
                        case '\\' -> result.append('\\');
                        case '/' -> result.append('/');
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'u' -> result.append(readUnicodeEscape());
                        default -> throw invalid();
                    }
                } else if (character < 0x20) {
                    // 未转义控制字符不是合法 JSON；宽松接受会让不同实现产生不同规范字节。
                    throw invalid();
                } else {
                    result.append(character);
                }
            }
        }

        /** 读取 {@code \\uXXXX} 的四个十六进制位，不接受任何其他写法。 */
        private char readUnicodeEscape() {
            if (index + 4 > json.length()) {
                throw invalid();
            }
            int value = 0;
            for (int position = 0; position < 4; position++) {
                char digit = json.charAt(index + position);
                int decoded = Character.digit(digit, 16);
                if (decoded < 0) {
                    throw invalid();
                }
                value = (value << 4) | decoded;
            }
            index += 4;
            return (char) value;
        }
    }
}
