package com.things.link.simulator.application.ota.contract;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 设备侧合同的严格字段校验工具，语义逐条对齐平台 {@code OtaTrustBundleCodec}/{@code OtaConfirmationJson}。
 *
 * <p><b>为什么必须严格：</b>OTA 报文里每个字段都会参与平台对完整规范字节的摘要、签名或安全裁决。
 * 如果设备侧把「字符串数字」当整数、把大写 UUID 当合法、或接受缺失字段，就可能发出一份平台
 * 解不开的报文，或者更糟——把平台显式拒绝的歧义事实当成已上报。因此这里所有读取都失败关闭：
 * 类型不符、越界、非规范表示一律抛出不携带原文的 {@link IllegalArgumentException}。</p>
 */
final class OtaContractFields {

    /** 跨语言安全整数上限（RFC8785 可精确表示的整数边界）。 */
    static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    /** 摘要字段的完整小写十六进制形态。 */
    static final String SHA256_PATTERN = "[0-9a-f]{64}";

    /** 工具类不允许实例化。 */
    private OtaContractFields() {
    }

    /**
     * 校验字段集合恰好等于闭集：缺失与未知同时被拒绝。
     *
     * @param map 已解析字段
     * @param names 允许的字段名闭集
     */
    static void closed(Map<String, Object> map, Set<String> names) {
        if (!map.keySet().equals(names)) {
            throw invalid();
        }
    }

    /**
     * 读取严格字符串并做完整正则匹配。
     *
     * @param value 待校验值
     * @param pattern 必须完整匹配的正则
     * @return 校验通过的字符串
     */
    static String text(Object value, String pattern) {
        if (!(value instanceof String string) || !string.matches(pattern)) {
            throw invalid();
        }
        return string;
    }

    /**
     * 读取严格字符串字段并做完整正则匹配。
     *
     * @param map 字段映射
     * @param key 字段名
     * @param pattern 必须完整匹配的正则
     * @return 校验通过的字符串
     */
    static String text(Map<String, Object> map, String key, String pattern) {
        return text(map.get(key), pattern);
    }

    /**
     * 读取安全整数；只接受受限 JSON 解析器产出的 {@link Long}，不接受字符串或浮点。
     *
     * @param value 待校验值
     * @param min 下界（含）
     * @param max 上界（含）
     * @return 校验通过的整数
     */
    static long integer(Object value, long min, long max) {
        if (!(value instanceof Long number) || number < min || number > max) {
            throw invalid();
        }
        return number;
    }

    /**
     * 读取安全整数字段。
     *
     * @param map 字段映射
     * @param key 字段名
     * @param min 下界（含）
     * @param max 上界（含）
     * @return 校验通过的整数
     */
    static long integer(Map<String, Object> map, String key, long min, long max) {
        return integer(map.get(key), min, max);
    }

    /**
     * 读取安全整数字段并窄化为 {@code int}。
     *
     * @param map 字段映射
     * @param key 字段名
     * @param min 下界（含）
     * @param max 上界（含）
     * @return 校验通过的 {@code int}
     */
    static int intValue(Map<String, Object> map, String key, long min, long max) {
        return (int) integer(map, key, min, max);
    }

    /**
     * 读取固定枚举文本，不接受未知扩展。
     *
     * @param map 字段映射
     * @param key 字段名
     * @param allowed 允许的字面量闭集
     * @return 校验通过的枚举文本
     */
    static String literal(Map<String, Object> map, String key, Set<String> allowed) {
        if (!(map.get(key) instanceof String text) || !allowed.contains(text)) {
            throw invalid();
        }
        return text;
    }

    /**
     * 读取真正的 JSON 布尔值，不把字符串或数字转换为布尔。
     *
     * @param map 字段映射
     * @param key 字段名
     * @return 校验通过的布尔值
     */
    static boolean flag(Map<String, Object> map, String key) {
        if (!(map.get(key) instanceof Boolean value)) {
            throw invalid();
        }
        return value;
    }

    /**
     * 读取完整小写十六进制摘要字段。
     *
     * @param map 字段映射
     * @param key 字段名
     * @return 校验通过的摘要文本
     */
    static String hex(Map<String, Object> map, String key) {
        return text(map, key, SHA256_PATTERN);
    }

    /**
     * 读取标准小写规范 UUID。
     *
     * @param value 待校验值
     * @return 校验通过的 UUID
     */
    static UUID uuid(Object value) {
        if (!(value instanceof String string)) {
            throw invalid();
        }
        try {
            UUID uuid = UUID.fromString(string);
            if (!uuid.toString().equals(string)) {
                throw invalid();
            }
            return uuid;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    /**
     * 读取标准小写规范 UUID 字段。
     *
     * @param map 字段映射
     * @param key 字段名
     * @return 校验通过的 UUID
     */
    static UUID uuid(Map<String, Object> map, String key) {
        return uuid(map.get(key));
    }

    /**
     * 把值收窄为字符串键映射。
     *
     * @param value 待校验值
     * @return 字符串键映射
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)
                || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw invalid();
        }
        return (Map<String, Object>) map;
    }

    /**
     * 读取标准规范 Base64（含必要填充），并限制解码后的最大字节数。
     *
     * @param value 待校验值
     * @param maxBytes 解码后允许的最大字节数
     * @return 解码后的字节
     */
    static byte[] base64(Object value, int maxBytes) {
        if (!(value instanceof String string) || string.length() > ((maxBytes + 2) / 3) * 4) {
            throw invalid();
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(string);
            if (bytes.length == 0 || bytes.length > maxBytes
                    || !Base64.getEncoder().encodeToString(bytes).equals(string)) {
                throw invalid();
            }
            return bytes;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    /**
     * 读取零或一项的数组；空数组有效，空引用或多于一项无效。
     *
     * @param value 待校验值
     * @return 元素列表
     */
    static List<?> items(Object value) {
        if (!(value instanceof List<?> values) || values.size() > 1) {
            throw invalid();
        }
        return values;
    }

    /**
     * 计算完整规范字节的 SHA-256。
     *
     * @param bytes 待摘要字节
     * @return 小写十六进制摘要
     */
    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行时不提供 SHA-256", exception);
        }
    }

    /**
     * 拒绝非规范输入字节。
     *
     * <p>平台对报文重新规范化后再解析，因此宽松地接受乱序/空白/重复转义不会立刻报错；设备侧
     * 则要求在解析时输入就等于规范字节，否则同一逻辑事实存在多种可签名字节，摘要与签名边界
     * 就不唯一。这条比平台解码器更严格，是有意的失败关闭方向。</p>
     *
     * @param input 收到的原始字节
     * @param canonical 重新规范化后的字节
     */
    static void requireCanonical(byte[] input, byte[] canonical) {
        if (!Arrays.equals(input, canonical)) {
            throw invalid();
        }
    }

    /** 生成不携带报文原文的统一合同错误。 */
    static IllegalArgumentException invalid() {
        return new IllegalArgumentException("OTA设备侧合同不合法");
    }
}
