package com.things.link.support.json;

import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

/** ADR0152 canonical值：对象键排序、数组保序、数值等价；科学计数法避免巨大指数展开耗尽内存。 */
public final class AutomationCanonicalJson {
    private static final tools.jackson.databind.json.JsonMapper MAPPER = tools.jackson.databind.json.JsonMapper.builder().build();
    private AutomationCanonicalJson() { }
    public static String digest(JsonNode value) { return digestBytes(canonical(value).getBytes(StandardCharsets.UTF_8)); }
    public static String digestBytes(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    public static String canonical(JsonNode value) {
        if (value == null) throw new IllegalArgumentException("缺少JSON值");
        if (value.isObject()) {
            var sorted = new TreeMap<String,JsonNode>();
            value.properties().forEach(e -> sorted.put(e.getKey(), e.getValue()));
            var result = new StringBuilder("{");
            for (var entry : sorted.entrySet()) {
                if (result.length() > 1) result.append(',');
                result.append(MAPPER.writeValueAsString(entry.getKey())).append(':').append(canonical(entry.getValue()));
            }
            return result.append('}').toString();
        }
        if (value.isArray()) {
            var result = new StringBuilder("[");
            for (var child : value) {
                if (result.length() > 1) result.append(',');
                result.append(canonical(child));
            }
            return result.append(']').toString();
        }
        if (value.isNumber()) {
            BigDecimal number;
            try { number = new BigDecimal(value.asString()).stripTrailingZeros(); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("非有限JSON数字", ex); }
            return number.signum() == 0 ? "0" : number.toString();
        }
        if (value.isString() || value.isBoolean() || value.isNull()) return value.toString();
        throw new IllegalArgumentException("不支持的JSON类型");
    }
}
