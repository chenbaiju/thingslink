package com.things.link.ota.application;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 受限 JCS 的固定互操作向量、不可变性及资源失败边界测试。 */
class OtaCanonicalJsonTests {
    /** 每个测试独立使用的规范化入口。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();

    /** 使用固定黄金字节验证 UTF-16 排序、转义、数组顺序及安全整数上界。 */
    @Test
    void matchesIndependentUtf16SortingAndEscapingGoldenVector() {
        // RFC8785 3.2.3 的排序字符，固定期望同时区分 UTF-8 与 UTF-16 排序。
        String input = "{\"דּ\":7,\"😀\":6,\"€\":5,\"ö\":4,\"\u0080\":3,\"1\":2,\"\\r\":1}";
        String golden = "{\"\\r\":1,\"1\":2,\"\u0080\":3,\"ö\":4,\"€\":5,\"😀\":6,\"דּ\":7}";
        assertEquals(golden, canonical(input));
        String escaped = "{\"s\":\"\\u0000\\u000f\\b\\t\\n\\f\\r\\\"\\\\/é  😀\"}";
        assertEquals(escaped, canonical(escaped));
        assertEquals("{\"a\":9007199254740991,\"z\":[true,0,false,{\"a\":2,\"b\":1}]}",
                canonical("{\"z\":[true,0,false,{\"b\":1,\"a\":2}],\"a\":9007199254740991}"));
    }

    /** 验证字符串不规范化，并禁止修改任何层级的解析结果。 */
    @Test
    void preservesDistinctUnicodeSpellingsAndReturnsDeepImmutableCopies() {
        Map<String, Object> parsed = json.parseObject(bytes("{\"é\":[{\"é\":\"é\"}]}"));
        assertEquals("{\"é\":[{\"é\":\"é\"}]}", text(json.writeObject(parsed)));
        assertThrows(UnsupportedOperationException.class, () -> parsed.put("a", 0L));
        List<?> list = (List<?>) parsed.get("é");
        assertThrows(UnsupportedOperationException.class, list::clear);
        assertThrows(UnsupportedOperationException.class, () -> ((Map<?, ?>) list.getFirst()).clear());
    }

    /** 验证受限类型和严格 JSON 语法失败关闭。 */
    @Test
    void rejectsNonSubsetNumbersDuplicatesNullAndPermissiveSyntax() {
        for (String invalid : List.of("[]", "null", "true", "{}{}", "{\"a\":null}",
                "{\"a\":1,\"\\u0061\":2}", "{\"a\":{\"x\":1,\"x\":2}}",
                "{\"x\":-0}", "{\"x\":-1}", "{\"x\":1.0}", "{\"x\":1e0}",
                "{\"x\":9007199254740992}", "{\"x\":18446744073709551616}",
                "{\"x\":01}", "{\"x\":+1}", "{\"x\":NaN}", "{\"x\":true,}",
                "{'x':1}", "{/*comment*/}", "{\"x\":}", "{", "{\"x\":[")) {
            assertRejected(bytes(invalid));
        }
    }

    /** 验证畸形编码和代理码元被拒绝且异常不泄露原文。 */
    @Test
    void rejectsMalformedUtf8BomAndUnpairedSurrogatesWithoutLeakingInput() {
        assertRejected(new byte[] {'{', '"', 'x', '"', ':', '"', (byte) 0xc0, (byte) 0xaf, '"', '}'});
        assertRejected(bytes("\ufeff{}"));
        assertRejected(bytes("{\"x\":\"\\ud800\"}"));
        assertRejected(bytes("{\"\\udc00\":0}"));
        assertThrows(IllegalArgumentException.class, () -> json.writeObject(Map.of("x", "\ud800")));
        assertThrows(IllegalArgumentException.class, () -> json.writeObject(Map.of("\udc00", 0L)));
    }

    /** 验证输入和输出独立字节上限及控制字符转义膨胀。 */
    @Test
    void enforcesInputAndCanonicalOutputByteLimitsIncludingExpansion() {
        assertEquals(Map.of(), json.parseObject(bytes("{}" + " ".repeat(65_534))));
        assertRejected(bytes("{}" + " ".repeat(65_535)));
        Map<String, Object> exact = new LinkedHashMap<>();
        exact.put("a", "x".repeat(16_384));
        exact.put("b", "x".repeat(16_384));
        exact.put("c", "x".repeat(16_384));
        exact.put("d", "x".repeat(16_355));
        assertEquals(65_536, json.writeObject(exact).length);
        exact.put("d", "x".repeat(16_356));
        assertThrows(IllegalArgumentException.class, () -> json.writeObject(exact));
        assertThrows(IllegalArgumentException.class,
                () -> json.writeObject(Map.of("x", "\u0001".repeat(11_000))));
    }

    /** 验证字段名与值均按 UTF-8 字节数计量字符串预算。 */
    @Test
    void enforcesStringUtf8LimitForKeysAndValues() {
        assertEquals("x".repeat(16_384), json.parseObject(bytes("{\"a\":\"" + "x".repeat(16_384) + "\"}")).get("a"));
        assertRejected(bytes("{\"a\":\"" + "x".repeat(16_385) + "\"}"));
        assertThrows(IllegalArgumentException.class, () -> json.writeObject(Map.of("é".repeat(8193), 0L)));
        assertThrows(IllegalArgumentException.class, () -> json.writeObject(Map.of("x", "é".repeat(8193))));
        assertEquals(16_392, json.writeObject(Map.of("x", "é".repeat(8192))).length);
    }

    /** 验证解析与写入共享一致的容器深度和节点计数边界。 */
    @Test
    void enforcesContainerDepthAndNodeLimitsOnBothPaths() {
        String exact = "{\"x\":" + "[".repeat(31) + "0" + "]".repeat(31) + "}";
        assertEquals(exact, canonical(exact));
        assertRejected(bytes("{\"x\":" + "[".repeat(32) + "0" + "]".repeat(32) + "}"));
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < 4093; i++) {
            items.add(true);
        }
        Map<String, Object> map = Map.of("x", items);
        assertEquals(map, json.parseObject(json.writeObject(map)));
        items.add(true);
        assertThrows(IllegalArgumentException.class, () -> json.writeObject(map));
        assertRejected(bytes("{\"x\":[" + "true,".repeat(4093) + "true]}"));
        Object nested = 0L;
        for (int i = 0; i < 32; i++) {
            nested = List.of(nested);
        }
        Map<String, Object> tooDeep = Map.of("x", nested);
        assertThrows(IllegalArgumentException.class, () -> json.writeObject(tooDeep));
    }

    /** 验证写入拒绝非法类型、循环及重复字段，同时允许复用不可变子树。 */
    @Test
    void rejectsUnsupportedWriteValuesCyclesAndNullButAllowsSharedSubtrees() {
        for (Object value : List.of(1, 1.0, new java.math.BigInteger("1"), -1L,
                9_007_199_254_740_992L, new Object())) {
            assertThrows(IllegalArgumentException.class, () -> json.writeObject(Map.of("x", value)));
        }
        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("x", cycle);
        assertThrows(IllegalArgumentException.class, () -> json.writeObject(cycle));
        List<Object> listCycle = new ArrayList<>();
        listCycle.add(listCycle);
        assertThrows(IllegalArgumentException.class, () -> json.writeObject(Map.of("x", listCycle)));
        cycle.put("x", null);
        assertThrows(IllegalArgumentException.class, () -> json.writeObject(cycle));
        assertThrows(IllegalArgumentException.class, () -> json.writeObject(null));
        assertRejected(null);
        List<Object> shared = List.of(0L);
        assertEquals("{\"a\":[0],\"b\":[0]}", text(json.writeObject(Map.of("a", shared, "b", shared))));
        Map<String, Object> identityKeys = new java.util.IdentityHashMap<>();
        identityKeys.put(new String("x"), 0L);
        identityKeys.put(new String("x"), 1L);
        assertThrows(IllegalArgumentException.class, () -> json.writeObject(identityKeys));
    }

    /** 解析并规范化测试文本，仅供黄金向量比对。 */
    private String canonical(String input) {
        return text(json.writeObject(json.parseObject(bytes(input))));
    }

    /** 验证统一异常类型、固定错误消息及无底层异常链。 */
    private void assertRejected(byte[] input) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> json.parseObject(input));
        assertEquals("Invalid OTA canonical JSON", error.getMessage());
        assertNull(error.getCause());
    }

    /** 将合法测试文本编码为 UTF-8。 */
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** 将规范化结果解码为便于断言的 UTF-8 文本。 */
    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
