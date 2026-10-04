package com.things.link.dashboard.application.schema;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** S12-0b1第2.1节和第2.1表的内部共享原子值规则。 */
final class DashboardSchemaValueRules {
    /** LocalKey只允许小写ASCII业务标识。 */
    private static final Pattern LOCAL_KEY = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    /** PropertyKey沿用物模型顶层属性键安全字符集。 */
    private static final Pattern PROPERTY_KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** UUID必须使用规范小写8-4-4-4-12文本。 */
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    /** SHA-256摘要不允许算法前缀或大写字符。 */
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    /** SemVer仅允许三个无前导零十进制段。 */
    private static final Pattern SEMVER = Pattern.compile("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)");
    /** ConfigNumber非零绝对值下限。 */
    private static final BigDecimal CONFIG_MIN_ABSOLUTE = new BigDecimal("1e-12");
    /** ConfigNumber绝对值上限。 */
    private static final BigDecimal CONFIG_MAX_ABSOLUTE = new BigDecimal("1e12");

    /** 工具类不允许实例化。 */
    private DashboardSchemaValueRules() {
    }

    /**
     * 校验LocalKey。
     *
     * @param value 待校验字符串节点
     * @param path 稳定JSON路径
     * @return 原始字符串
     */
    static String localKey(JsonNode value, String path) {
        return patternedString(value, path, LOCAL_KEY, "LocalKey");
    }

    /**
     * 校验顶层PropertyKey，不允许点或数组路径。
     *
     * @param value 待校验字符串节点
     * @param path 稳定JSON路径
     * @return 原始字符串
     */
    static String propertyKey(JsonNode value, String path) {
        return patternedString(value, path, PROPERTY_KEY, "PropertyKey");
    }

    /**
     * 校验规范小写UUID。
     *
     * @param value 待校验字符串节点
     * @param path 稳定JSON路径
     * @return 原始UUID文本
     */
    static String uuid(JsonNode value, String path) {
        String text = patternedString(value, path, UUID_TEXT, "规范小写UUID");
        try {
            UUID.fromString(text);
        } catch (IllegalArgumentException exception) {
            throw invalid(path, "不是有效UUID");
        }
        return text;
    }

    /**
     * 校验小写SHA-256摘要。
     *
     * @param value 待校验字符串节点
     * @param path 稳定JSON路径
     * @return 摘要文本
     */
    static String sha256(JsonNode value, String path) {
        return patternedString(value, path, SHA256, "64位小写SHA-256");
    }

    /**
     * 校验受限SemVer且每段不超过65535。
     *
     * @param value 待校验字符串节点
     * @param path 稳定JSON路径
     * @return 版本文本
     */
    static String semVer(JsonNode value, String path) {
        String text = requireString(value, path);
        Matcher matcher = SEMVER.matcher(text);
        if (!matcher.matches()) throw invalid(path, "必须是无前导零的major.minor.patch");
        for (int group = 1; group <= 3; group++) {
            String segment = matcher.group(group);
            if (segment.length() > 5 || Integer.parseInt(segment) > 65_535) {
                throw invalid(path, "SemVer每段不得超过65535");
            }
        }
        return text;
    }

    /**
     * 校验Title的码点、非空白和C0/C1约束，不改变原文。
     *
     * @param value 待校验字符串节点
     * @param path 稳定JSON路径
     * @return 原始标题
     */
    static String title(JsonNode value, String path) {
        String text = requireString(value, path);
        int codePoints = text.codePointCount(0, text.length());
        if (codePoints < 1 || codePoints > 80 || text.isBlank() || containsControl(text, false)) {
            throw invalid(path, "Title必须为1至80码点的非空白无控制字符文本");
        }
        return text;
    }

    /**
     * 校验ShortText的码点与C0/C1约束，允许空字符串。
     *
     * @param value 待校验字符串节点
     * @param path 稳定JSON路径
     * @return 原始短文本
     */
    static String shortText(JsonNode value, String path) {
        String text = requireString(value, path);
        if (text.codePointCount(0, text.length()) > 256 || containsControl(text, false)) {
            throw invalid(path, "ShortText最多256码点且不得含控制字符");
        }
        return text;
    }

    /**
     * 校验TextContent的码点和控制字符白名单，保留TAB与LF。
     *
     * @param value 待校验字符串节点
     * @param path 稳定JSON路径
     * @return 原始正文
     */
    static String textContent(JsonNode value, String path) {
        String text = requireString(value, path);
        if (text.codePointCount(0, text.length()) > 4096 || containsControl(text, true)) {
            throw invalid(path, "TextContent最多4096码点且控制字符只允许TAB/LF");
        }
        return text;
    }

    /**
     * 校验ConfigNumber范围和去尾零后的十五位有效数字。
     *
     * @param value 待校验数字节点
     * @param path 稳定JSON路径
     * @return 未改写精度的十进制值
     */
    static BigDecimal configNumber(JsonNode value, String path) {
        if (value == null || !value.isNumber()) {
            throw new DashboardSchemaValidationException(DashboardSchemaValidationException.Reason.TYPE_MISMATCH,
                    path, "必须是JSON number");
        }
        BigDecimal decimal = value.decimalValue();
        BigDecimal absolute = decimal.abs();
        if (decimal.signum() != 0 && (absolute.compareTo(CONFIG_MIN_ABSOLUTE) < 0
                || absolute.compareTo(CONFIG_MAX_ABSOLUTE) > 0)) {
            throw invalidNumber(path, "绝对值必须为0或位于10^-12至10^12");
        }
        if (decimal.stripTrailingZeros().precision() > 15) {
            throw invalidNumber(path, "去十进制尾零后不得超过15位有效数字");
        }
        return decimal;
    }

    /** 要求值为字符串节点。 */
    private static String requireString(JsonNode value, String path) {
        if (value == null || !value.isString()) {
            throw new DashboardSchemaValidationException(DashboardSchemaValidationException.Reason.TYPE_MISMATCH,
                    path, "必须是字符串");
        }
        return value.asString();
    }

    /** 要求字符串满足指定ASCII语法。 */
    private static String patternedString(JsonNode value, String path, Pattern pattern, String description) {
        String text = requireString(value, path);
        if (!pattern.matcher(text).matches()) throw invalid(path, "必须符合" + description);
        return text;
    }

    /** 判断是否含禁止的C0/C1控制字符。 */
    private static boolean containsControl(String value, boolean allowTabAndLf) {
        return value.codePoints().anyMatch(codePoint -> (codePoint <= 0x1F || codePoint >= 0x7F && codePoint <= 0x9F)
                && !(allowTabAndLf && (codePoint == '\t' || codePoint == '\n')));
    }

    /** 创建普通原子值异常。 */
    private static DashboardSchemaValidationException invalid(String path, String detail) {
        return new DashboardSchemaValidationException(DashboardSchemaValidationException.Reason.INVALID_VALUE,
                path, detail);
    }

    /** 创建ConfigNumber异常。 */
    private static DashboardSchemaValidationException invalidNumber(String path, String detail) {
        return new DashboardSchemaValidationException(DashboardSchemaValidationException.Reason.INVALID_NUMBER,
                path, detail);
    }
}
