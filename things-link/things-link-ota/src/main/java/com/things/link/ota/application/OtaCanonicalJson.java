package com.things.link.ota.application;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OTA manifest 的 RFC8785 受限子集，不是通用 JCS 实现。
 * 数值只允许非负安全整数；禁止 null。字段按 UTF-16 码元排序，字符串不做 Unicode 规范化。
 * 根容器深度为 1；节点预算同时计算容器、标量和字段名。调用期间不得并发修改输入。
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
    /** 能够精确表示且不触发 JCS 指数格式的最大非负安全整数。 */
    private static final long MAX_INTEGER = 9_007_199_254_740_991L;
    /** 仅使用默认严格 JSON 语法的共享解析器工厂，不启用宽松特性。 */
    private static final JsonFactory FACTORY = new JsonFactory();

    /** 严格解析 UTF-8 对象；返回深不可变副本，错误不包含输入原文或底层异常。 */
    public Map<String, Object> parseObject(byte[] utf8) {
        try {
            if (utf8 == null || utf8.length > MAX_BYTES) {
                throw invalid();
            }
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8)).toString();
            try (JsonParser parser = FACTORY.createParser(json)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    throw invalid();
                }
                Map<String, Object> result = readMap(parser, 1, new Budget());
                if (parser.nextToken() != null) {
                    throw invalid();
                }
                // 转义可扩大结果：解析成功也必须能在相同输出预算内规范化。
                writeObject(result);
                return result;
            }
        } catch (IOException | RuntimeException exception) {
            throw invalid();
        }
    }

    /** 只接收 String/Boolean/Long/Map/List，独立验证预算和循环后输出 JCS UTF-8。 */
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

    /** 读取当前对象及其字段，拒绝重复名称并创建不可变结果。 */
    private static Map<String, Object> readMap(JsonParser parser, int depth, Budget budget)
            throws IOException {
        container(depth, budget);
        Map<String, Object> map = new LinkedHashMap<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) {
                throw invalid();
            }
            String key = parser.currentName();
            budget.node();
            validateString(key);
            if (map.containsKey(key)) {
                throw invalid();
            }
            parser.nextToken();
            map.put(key, read(parser, depth + 1, budget));
        }
        return Collections.unmodifiableMap(map);
    }

    /** 读取一个受限 JSON 值，并在递归前计入容器预算。 */
    private static Object read(JsonParser parser, int depth, Budget budget) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.START_OBJECT) {
            return readMap(parser, depth, budget);
        }
        if (token == JsonToken.START_ARRAY) {
            container(depth, budget);
            List<Object> list = new ArrayList<>();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                list.add(read(parser, depth + 1, budget));
            }
            return Collections.unmodifiableList(list);
        }
        budget.node();
        if (token == JsonToken.VALUE_STRING) {
            String string = parser.getText();
            validateString(string);
            return string;
        }
        if (token == JsonToken.VALUE_TRUE || token == JsonToken.VALUE_FALSE) {
            return token == JsonToken.VALUE_TRUE;
        }
        if (token == JsonToken.VALUE_NUMBER_INT) {
            String literal = parser.getText();
            if (!literal.matches("0|[1-9][0-9]{0,15}")) {
                throw invalid();
            }
            long number = Long.parseLong(literal);
            validateInteger(number);
            return number;
        }
        throw invalid();
    }

    /** 递归验证并输出值；祖先身份集合仅拒绝循环，允许共享子树。 */
    private static void write(Object value, int depth, Budget budget, Set<Object> ancestors,
                              Output output) {
        if (value instanceof Map<?, ?> map) {
            container(depth, budget);
            if (!ancestors.add(value)) {
                throw invalid();
            }
            List<String> keys = new ArrayList<>();
            Set<String> uniqueKeys = new HashSet<>();
            for (Object key : map.keySet()) {
                budget.node();
                if (!(key instanceof String string)) {
                    throw invalid();
                }
                validateString(string);
                if (!uniqueKeys.add(string)) {
                    throw invalid();
                }
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
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
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
        for (int i = 0; i < string.length(); i++) {
            char character = string.charAt(i);
            if (Character.isHighSurrogate(character)) {
                if (++i == string.length() || !Character.isLowSurrogate(string.charAt(i))) {
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
        if (number < 0 || number > MAX_INTEGER) {
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

    /** 生成不包含原文、凭据或底层解析位置的统一外部输入错误。 */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid OTA canonical JSON");
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
}
