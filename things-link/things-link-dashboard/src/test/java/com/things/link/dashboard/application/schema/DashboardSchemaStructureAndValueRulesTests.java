package com.things.link.dashboard.application.schema;

import com.things.link.dashboard.infrastructure.schema.JacksonDashboardSchemaParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 看板Schema结构和值内核测试。 */
@DisplayName("看板Schema结构和值内核")
class DashboardSchemaStructureAndValueRulesTests {
    /** 测试用JSON树工厂。 */
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    /** 测试用严格原文到语义内部管线。 */
    private final DashboardSchemaValidator validator = DashboardSchemaValidator.using(new JacksonDashboardSchemaParser());
    /** 测试构造内部规则节点使用的JSON映射器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 内部管线必须先解析原文，再注入根级唯一默认值并报告有限完成范围。 */
    @Test
    @DisplayName("原文到语义管线注入根级默认值")
    void pipelineInjectsRootDefaultsAndReportsScope() {
        DashboardSchemaValidationResult result = validate("""
                {"schemaVersion":"tc.dashboard/v1","presentation":{"mode":"RESPONSIVE_GRID"},"pages":[{"id":"main","title":"主页","components":[]}]}
                """);

        assertThat(result.scope()).isEqualTo(
                DashboardSchemaValidationResult.Scope
                        .COMPLETE_INTERNAL_SCHEMA_SEMANTICS);
        assertThat(result.normalizedRoot().get("models")).isEmpty();
        assertThat(result.normalizedRoot().get("variables")).isEmpty();
        ObjectNode callerCopy = (ObjectNode) result.normalizedRoot();
        callerCopy.put("schemaVersion", "changed");
        assertThat(result.normalizedRoot().get("schemaVersion").asString()).isEqualTo("tc.dashboard/v1");
    }

    /** 内部管线必须在根对象拒绝未知字段。 */
    @Test
    @DisplayName("拒绝根对象未知字段")
    void pipelineRejectsUnknownRootField() {
        assertRejectedAt("""
                {"schemaVersion":"tc.dashboard/v1","presentation":{"mode":"RESPONSIVE_GRID"},"pages":[],"tenantId":"forbidden"}
                """, DashboardSchemaValidationException.Reason.UNKNOWN_FIELD, "$",
                "$ 包含合同未声明字段");
    }

    /** null规则递归覆盖尚未进入复杂语义校验的集合内容。 */
    @Test
    @DisplayName("递归拒绝任意null")
    void pipelineRejectsNestedNull() {
        assertRejectedAt("""
                {"schemaVersion":"tc.dashboard/v1","presentation":{"mode":"RESPONSIVE_GRID"},"pages":[{"value":null}]}
                """, DashboardSchemaValidationException.Reason.NULL_NOT_ALLOWED, "$.*[0].*",
                "$.*[0].* 不接受null");
    }

    /** 缺失字段和错误JSON类型必须使用不同稳定原因。 */
    @Test
    @DisplayName("区分缺失字段和严格类型错误")
    void pipelineDistinguishesMissingAndWrongType() {
        assertRejectedAt("{" + "\"schemaVersion\":\"tc.dashboard/v1\",\"pages\":[]}",
                DashboardSchemaValidationException.Reason.REQUIRED_FIELD_MISSING,
                "$.presentation", "$.presentation 为必填字段");
        assertRejectedAt("""
                {"schemaVersion":"tc.dashboard/v1","presentation":{"mode":"RESPONSIVE_GRID"},"pages":{},"models":{},"variables":[]}
                """, DashboardSchemaValidationException.Reason.TYPE_MISMATCH,
                "$.models", "$.models 必须是数组");
    }

    /** 规范化结果的500 KiB限制在默认注入后重新执行。 */
    @Test
    @DisplayName("拒绝默认注入后超过512000字节")
    void pipelineRejectsOversizedNormalizedSchema() {
        // 125个合法TEXT分别受4096码点限制，同时可累积到Schema总上限，避免用非法单字段绕过前置值规则。
        int normalizedBaseBytes = validate(normalizationBoundarySchema(0)).normalizedUtf8().length;
        String exactNormalized = normalizationBoundarySchema(512_000 - normalizedBaseBytes);
        String source = normalizationBoundarySchema(512_001 - normalizedBaseBytes);

        assertThat(validate(exactNormalized).normalizedUtf8()).hasSize(512_000);
        assertThat(source.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(512_000);
        assertRejectedAt(source, DashboardSchemaValidationException.Reason.NORMALIZED_TOO_LARGE,
                "$", "$ 注入默认值后超过512000字节上限");
    }

    /**
     * 构造五页共125个合法TEXT，并把指定ASCII字节数按单字段4096上限分配到content。
     *
     * @param contentBytes 全部TEXT正文的ASCII字节总数
     * @return 省略组件显示默认值的合法Schema原文
     */
    private static String normalizationBoundarySchema(int contentBytes) {
        if (contentBytes < 0 || contentBytes > 125 * 4096) {
            throw new IllegalArgumentException("测试正文总量超出125个TextContent容量");
        }
        StringBuilder source = new StringBuilder(
                "{\"schemaVersion\":\"tc.dashboard/v1\",\"presentation\":{\"mode\":\"RESPONSIVE_GRID\"},\"pages\":[");
        int remaining = contentBytes;
        for (int page = 0; page < 5; page++) {
            if (page > 0) source.append(',');
            source.append("{\"id\":\"page_").append(page).append("\",\"title\":\"页\",\"components\":[");
            for (int index = 0; index < 25; index++) {
                if (index > 0) source.append(',');
                int length = Math.min(remaining, 4096);
                remaining -= length;
                source.append("{\"id\":\"text_").append(page).append('_').append(index)
                        .append("\",\"kind\":\"TEXT\",\"componentVersion\":\"1.0.0\",\"layout\":{\"x\":")
                        .append(index % 24).append(",\"y\":").append(index / 24)
                        .append(",\"w\":1,\"h\":1},\"props\":{\"content\":\"")
                        .append("a".repeat(length)).append("\"},\"bindings\":{}}");
            }
            source.append("]}");
        }
        return source.append("]}").toString();
    }

    /** 严格类型工具不把字符串、0/1或浮点词法转换成目标类型。 */
    @Test
    @DisplayName("严格类型工具拒绝宽松转换")
    void strictTypesRejectCoercion() throws Exception {
        ObjectNode object = (ObjectNode) objectMapper.readTree("{\"string\":1,\"boolean\":1,\"integer\":1.0}");

        assertRuleRejected(() -> DashboardSchemaStructureRules.requireString(object, "string", "$"),
                DashboardSchemaValidationException.Reason.TYPE_MISMATCH);
        assertRuleRejected(() -> DashboardSchemaStructureRules.requireBoolean(object, "boolean", "$"),
                DashboardSchemaValidationException.Reason.TYPE_MISMATCH);
        assertRuleRejected(() -> DashboardSchemaStructureRules.requireInteger(object, "integer", "$"),
                DashboardSchemaValidationException.Reason.TYPE_MISMATCH);
    }

    /** 默认注入工具只补缺失值，不覆盖用户显式填写的合法值。 */
    @Test
    @DisplayName("默认注入保留显式值")
    void defaultsOnlyFillMissingFields() {
        ObjectNode object = NODES.objectNode().put("theme", "DARK");

        DashboardSchemaStructureRules.putDefault(object, "theme", "LIGHT");
        DashboardSchemaStructureRules.putDefault(object, "columns", 24);
        DashboardSchemaStructureRules.putDefault(object, "enabled", true);
        DashboardSchemaStructureRules.defaultArray(object, "items");

        assertThat(object.get("theme").asString()).isEqualTo("DARK");
        assertThat(object.get("columns").asInt()).isEqualTo(24);
        assertThat(object.get("enabled").asBoolean()).isTrue();
        assertThat(object.get("items")).isEmpty();
    }

    /** LocalKey和PropertyKey分别执行小写本地标识及顶层属性语法。 */
    @Test
    @DisplayName("校验LocalKey和PropertyKey边界")
    void validatesLocalAndPropertyKeys() {
        assertThat(DashboardSchemaValueRules.localKey(text("a_" + "1".repeat(62)), "$.key")).hasSize(64);
        assertThat(DashboardSchemaValueRules.propertyKey(text("Temperature-1"), "$.propertyKey"))
                .isEqualTo("Temperature-1");
        assertValueRejected(() -> DashboardSchemaValueRules.localKey(text("Upper"), "$.key"));
        assertValueRejected(() -> DashboardSchemaValueRules.localKey(text("a".repeat(65)), "$.key"));
        assertValueRejected(() -> DashboardSchemaValueRules.propertyKey(text("payload.temperature"), "$.propertyKey"));
    }

    /** UUID和SHA-256只接受合同冻结的小写规范文本。 */
    @Test
    @DisplayName("校验UUID和SHA256规范文本")
    void validatesUuidAndSha256() {
        assertThat(DashboardSchemaValueRules.uuid(text("123e4567-e89b-12d3-a456-426614174000"), "$.id"))
                .isEqualTo("123e4567-e89b-12d3-a456-426614174000");
        assertThat(DashboardSchemaValueRules.sha256(text("a".repeat(64)), "$.digest")).hasSize(64);
        assertValueRejected(() -> DashboardSchemaValueRules.uuid(text("123E4567-e89b-12d3-a456-426614174000"), "$.id"));
        assertValueRejected(() -> DashboardSchemaValueRules.sha256(text("A".repeat(64)), "$.digest"));
    }

    /** SemVer只允许三段、无前导零且每段不超过65535。 */
    @ParameterizedTest(name = "拒绝SemVer {0}")
    @ValueSource(strings = {"1.0", "01.0.0", "1.0.0-alpha", "65536.0.0"})
    @DisplayName("拒绝不受支持SemVer")
    void rejectsInvalidSemVer(String version) {
        assertValueRejected(() -> DashboardSchemaValueRules.semVer(text(version), "$.version"));
    }

    /** Title按Unicode码点计数、保留补充字符并拒绝空白和控制字符。 */
    @Test
    @DisplayName("校验Title Unicode边界")
    void validatesTitleBoundaries() {
        assertThat(DashboardSchemaValueRules.title(text("😀".repeat(80)), "$.title")).hasSize(160);
        assertValueRejected(() -> DashboardSchemaValueRules.title(text("😀".repeat(81)), "$.title"));
        assertValueRejected(() -> DashboardSchemaValueRules.title(text(" \t"), "$.title"));
        assertValueRejected(() -> DashboardSchemaValueRules.title(text("标题\u0085"), "$.title"));
    }

    /** ShortText允许空值但限制256码点并拒绝C0/C1。 */
    @Test
    @DisplayName("校验ShortText边界")
    void validatesShortTextBoundaries() {
        assertThat(DashboardSchemaValueRules.shortText(text(""), "$.alt")).isEmpty();
        assertThat(DashboardSchemaValueRules.shortText(text("文".repeat(256)), "$.alt")).hasSize(256);
        assertValueRejected(() -> DashboardSchemaValueRules.shortText(text("文".repeat(257)), "$.alt"));
        assertValueRejected(() -> DashboardSchemaValueRules.shortText(text("a\tb"), "$.alt"));
    }

    /** TextContent允许TAB/LF，拒绝其他控制字符并限制4096码点。 */
    @Test
    @DisplayName("校验TextContent边界")
    void validatesTextContentBoundaries() {
        assertThat(DashboardSchemaValueRules.textContent(text("a\tb\nc"), "$.content")).isEqualTo("a\tb\nc");
        assertThat(DashboardSchemaValueRules.textContent(text("😀".repeat(4096)), "$.content").codePointCount(0, 8192))
                .isEqualTo(4096);
        assertValueRejected(() -> DashboardSchemaValueRules.textContent(text("x\ry"), "$.content"));
        assertValueRejected(() -> DashboardSchemaValueRules.textContent(text("a".repeat(4097)), "$.content"));
    }

    /** ConfigNumber接受0及边界，拒绝绝对值和十五位有效数字越界。 */
    @Test
    @DisplayName("校验ConfigNumber范围与有效位")
    void validatesConfigNumberBoundaries() {
        assertThat(DashboardSchemaValueRules.configNumber(number("0"), "$.number")).isEqualByComparingTo("0");
        assertThat(DashboardSchemaValueRules.configNumber(number("1e-12"), "$.number")).isEqualByComparingTo("1e-12");
        assertThat(DashboardSchemaValueRules.configNumber(number("1e12"), "$.number")).isEqualByComparingTo("1e12");
        assertNumberRejected(() -> DashboardSchemaValueRules.configNumber(number("1e-13"), "$.number"));
        assertNumberRejected(() -> DashboardSchemaValueRules.configNumber(number("1000000000001"), "$.number"));
        assertNumberRejected(() -> DashboardSchemaValueRules.configNumber(number("1.234567890123456"), "$.number"));
    }

    /** 结构化校验异常保留旧构造兼容，并以独立字段暴露精确路径。 */
    @Test
    @DisplayName("校验异常构造器保持兼容")
    void validationExceptionConstructorsRemainCompatible() {
        DashboardSchemaValidationException legacy = new DashboardSchemaValidationException(
                DashboardSchemaValidationException.Reason.INVALID_VALUE, "旧消息");
        DashboardSchemaValidationException structured = new DashboardSchemaValidationException(
                DashboardSchemaValidationException.Reason.INVALID_NUMBER, "$.number", "超出范围");

        assertThat(legacy.path()).isEqualTo("$");
        assertThat(legacy.getMessage()).isEqualTo("旧消息");
        assertThat(structured.path()).isEqualTo("$.number");
        assertThat(structured.getMessage()).isEqualTo("$.number 超出范围");
    }

    /** 原文解析必须保留十进制精度，不能先经double舍入后让越界ConfigNumber变成合法值。 */
    @Test
    @DisplayName("解析链保留ConfigNumber十进制精度")
    void parserPreservesConfigNumberDecimalPrecision() {
        assertParsedNumberRejected("1.0000000000000001");
        assertParsedNumberRejected("1000000000000.00001");
        assertParsedNumberRejected("0.00000000000099999999999999999");

        assertThat(parsedNumber("1e-12").decimalValue()).isEqualByComparingTo("1e-12");
        assertThat(parsedNumber("1e12").decimalValue()).isEqualByComparingTo("1e12");
        assertThat(parsedNumber("1.0").toString()).isEqualTo("1.0");
    }

    /** 通过内部管线校验UTF-8 Schema文本。 */
    private DashboardSchemaValidationResult validate(String source) {
        return validator.validateAndNormalize(source.getBytes(StandardCharsets.UTF_8));
    }

    /** 断言内部语义管线按指定原因拒绝。 */
    private void assertRejected(String source, DashboardSchemaValidationException.Reason reason) {
        assertRuleRejected(() -> validate(source), reason);
    }

    /** 断言内部语义管线同时返回稳定原因、结构化路径和兼容消息。 */
    private void assertRejectedAt(
            String source,
            DashboardSchemaValidationException.Reason reason,
            String expectedPath,
            String expectedMessage) {
        assertThatThrownBy(() -> validate(source))
                .isInstanceOfSatisfying(DashboardSchemaValidationException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(reason);
                    assertThat(exception.path()).isEqualTo(expectedPath);
                    assertThat(exception.getMessage()).isEqualTo(expectedMessage);
                });
    }

    /** 断言内部规则按指定稳定原因拒绝。 */
    private static void assertRuleRejected(ThrowingOperation operation, DashboardSchemaValidationException.Reason reason) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(DashboardSchemaValidationException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(reason));
    }

    /** 断言普通原子值规则拒绝。 */
    private static void assertValueRejected(ThrowingOperation operation) {
        assertRuleRejected(operation, DashboardSchemaValidationException.Reason.INVALID_VALUE);
    }

    /** 断言ConfigNumber规则拒绝。 */
    private static void assertNumberRejected(ThrowingOperation operation) {
        assertRuleRejected(operation, DashboardSchemaValidationException.Reason.INVALID_NUMBER);
    }

    /** 断言严格解析后的真实数字节点仍被ConfigNumber规则拒绝。 */
    private static void assertParsedNumberRejected(String value) {
        assertNumberRejected(() -> DashboardSchemaValueRules.configNumber(parsedNumber(value), "$.number"));
    }

    /** 通过生产原文解析器取得待校验数字节点。 */
    private static JsonNode parsedNumber(String value) {
        ParsedDashboardSchema parsed = new JacksonDashboardSchemaParser().parse(("{\"schemaVersion\":"
                + "\"tc.dashboard/v1\",\"number\":" + value + "}").getBytes(StandardCharsets.UTF_8));
        return parsed.root().get("number");
    }

    /** 创建字符串节点。 */
    private static JsonNode text(String value) {
        return NODES.stringNode(value);
    }

    /** 创建保留十进制词义的数字节点。 */
    private static JsonNode number(String value) {
        return NODES.numberNode(new BigDecimal(value));
    }

    /** 允许测试lambda统一表示可能抛异常的规则调用。 */
    @FunctionalInterface
    private interface ThrowingOperation {
        /** 执行待断言规则。 */
        void run() throws Exception;
    }
}
