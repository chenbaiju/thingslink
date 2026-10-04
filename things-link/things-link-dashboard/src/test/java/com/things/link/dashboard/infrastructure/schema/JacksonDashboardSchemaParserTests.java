package com.things.link.dashboard.infrastructure.schema;

import com.things.link.dashboard.application.DashboardSchemaContractVersion;
import com.things.link.dashboard.application.schema.DashboardSchemaParseException;
import com.things.link.dashboard.application.schema.ParsedDashboardSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Jackson看板Schema原文解析测试。
 *
 * <p>测试聚焦S12-0b1第2.1节在建树前不可恢复的原文事实；组件字段、默认值和引用规则
 * 由后续业务校验切片覆盖。</p>
 */
@DisplayName("Jackson看板Schema原文解析")
class JacksonDashboardSchemaParserTests {
    /** 被测严格原文解析器。 */
    private final JacksonDashboardSchemaParser parser = new JacksonDashboardSchemaParser();

    /** 合法根对象应识别冻结版本，并隔离返回的可变JSON树。 */
    @Test
    @DisplayName("识别V1并隔离解析树")
    void recognizesVersionAndIsolatesParsedTree() {
        ParsedDashboardSchema parsed = parse("{\"schemaVersion\":\"tc.dashboard/v1\",\"text\":\"😀\"}");

        assertThat(parsed.contractVersion()).isEqualTo(DashboardSchemaContractVersion.V1);
        ObjectNode externalCopy = (ObjectNode) parsed.root();
        externalCopy.put("schemaVersion", "changed");
        assertThat(parsed.root().get("schemaVersion").asString()).isEqualTo("tc.dashboard/v1");
    }

    /** 512000字节边界必须在完整解析前按原始字节精确执行。 */
    @Test
    @DisplayName("接受恰好512000字节并拒绝多一个字节")
    void enforcesRawByteLimitBeforeParsing() {
        byte[] minimum = validSchema();
        byte[] exactLimit = Arrays.copyOf(minimum, JacksonDashboardSchemaParser.MAX_RAW_BYTES);
        Arrays.fill(exactLimit, minimum.length, exactLimit.length, (byte) ' ');
        byte[] overLimit = Arrays.copyOf(exactLimit, exactLimit.length + 1);
        overLimit[overLimit.length - 1] = ' ';

        assertThat(parser.parse(exactLimit).contractVersion()).isEqualTo(DashboardSchemaContractVersion.V1);
        assertRejected(overLimit, DashboardSchemaParseException.Reason.RAW_TOO_LARGE);
    }

    /** REPORT解码策略必须拒绝Jackson可能以替换字符处理的畸形UTF-8。 */
    @Test
    @DisplayName("拒绝畸形UTF-8")
    void rejectsMalformedUtf8() {
        assertRejected(new byte[]{(byte) 0xC3, 0x28}, DashboardSchemaParseException.Reason.INVALID_UTF8);
    }

    /** UTF-8 BOM即使能被底层解析器识别也不得进入合同解析。 */
    @Test
    @DisplayName("拒绝UTF-8 BOM")
    void rejectsUtf8Bom() {
        byte[] schema = validSchema();
        byte[] withBom = new byte[schema.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(schema, 0, withBom, 3, schema.length);

        assertRejected(withBom, DashboardSchemaParseException.Reason.BOM_NOT_ALLOWED);
    }

    /** 根对象结束后的第二个JSON值不能被readTree静默忽略。 */
    @Test
    @DisplayName("拒绝尾随额外JSON值")
    void rejectsTrailingJsonValue() {
        assertRejected(bytes("{\"schemaVersion\":\"tc.dashboard/v1\"} []"),
                DashboardSchemaParseException.Reason.TRAILING_VALUE);
    }

    /** 属性名必须在JSON转义解码后判重，且任意嵌套对象均执行同一规则。 */
    @Test
    @DisplayName("拒绝任意层级解码后重复属性名")
    void rejectsDecodedDuplicatePropertyNamesAtAnyDepth() {
        assertRejected(bytes("{\"schemaVersion\":\"tc.dashboard/v1\",\"nested\":{\"kind\":1,\"\\u006bind\":2}}"),
                DashboardSchemaParseException.Reason.DUPLICATE_KEY);
    }

    /** 严格JSON读取模式不得接受非标准数字扩展或残缺词法。 */
    @ParameterizedTest(name = "拒绝非法数字 {0}")
    @ValueSource(strings = {"01", "1.", ".1", "+1", "NaN", "Infinity", "1e"})
    @DisplayName("拒绝非法JSON数字")
    void rejectsInvalidJsonNumbers(String number) {
        assertRejected(bytes(schemaWithNumber(number)), DashboardSchemaParseException.Reason.INVALID_JSON);
    }

    /** 数字token按原始ASCII词法计数，64字节合法而65字节拒绝。 */
    @Test
    @DisplayName("数字token最大64字节")
    void enforcesNumberTokenLength() {
        String sixtyFourDigits = "1".repeat(64);
        String sixtyFiveDigits = "1".repeat(65);
        String beyondJacksonDefault = "1".repeat(1_001);

        assertThat(parse(schemaWithNumber(sixtyFourDigits)).root().get("number").isNumber()).isTrue();
        assertRejected(bytes(schemaWithNumber(sixtyFiveDigits)),
                DashboardSchemaParseException.Reason.NUMBER_TOO_LONG);
        assertRejected(bytes(schemaWithNumber(beyondJacksonDefault)),
                DashboardSchemaParseException.Reason.NUMBER_TOO_LONG);
    }

    /** 指数仅接受无多余前导零且绝对值不超过12的一至两位数字。 */
    @ParameterizedTest(name = "接受指数数字 {0}")
    @ValueSource(strings = {"1e0", "1e+9", "1E-12", "1.25e12"})
    @DisplayName("接受合同范围内指数")
    void acceptsBoundedExponent(String number) {
        assertThat(parse(schemaWithNumber(number)).root().get("number").isNumber()).isTrue();
    }

    /** 指数的位数、前导零和绝对值任一越界都必须在数值转换前拒绝。 */
    @ParameterizedTest(name = "拒绝指数数字 {0}")
    @ValueSource(strings = {"1e00", "1e01", "1e013", "1e13", "1e-13", "1e999999999"})
    @DisplayName("拒绝越界指数")
    void rejectsInvalidExponent(String number) {
        assertRejected(bytes(schemaWithNumber(number)), DashboardSchemaParseException.Reason.INVALID_EXPONENT);
    }

    /** 解码后的字符串和属性名都不得包含U+0000或孤立代理项。 */
    @ParameterizedTest(name = "拒绝Unicode原文 {0}")
    @ValueSource(strings = {
            "{\"schemaVersion\":\"tc.dashboard/v1\",\"value\":\"\\u0000\"}",
            "{\"schemaVersion\":\"tc.dashboard/v1\",\"value\":\"\\uD800\"}",
            "{\"schemaVersion\":\"tc.dashboard/v1\",\"\\uDC00\":true}"
    })
    @DisplayName("拒绝非法Unicode标量")
    void rejectsForbiddenUnicode(String schema) {
        assertRejected(bytes(schema), DashboardSchemaParseException.Reason.INVALID_UNICODE);
    }

    /** 根对象为第一层，每进入一个对象或数组增加一层，最大允许16层。 */
    @Test
    @DisplayName("接受16层并拒绝17层嵌套")
    void enforcesMaximumDepth() {
        assertThat(parse(nestedSchema(15)).contractVersion()).isEqualTo(DashboardSchemaContractVersion.V1);
        assertRejected(bytes(nestedSchema(16)), DashboardSchemaParseException.Reason.DEPTH_EXCEEDED);
    }

    /** 原文解析阶段只接受对象根，不让数组或标量进入后续业务校验。 */
    @ParameterizedTest(name = "拒绝非对象根 {0}")
    @ValueSource(strings = {"[]", "null", "true", "1", "\"text\""})
    @DisplayName("拒绝非对象根")
    void rejectsNonObjectRoot(String schema) {
        assertRejected(bytes(schema), DashboardSchemaParseException.Reason.ROOT_MUST_BE_OBJECT);
    }

    /** 根对象必须声明字符串类型的schemaVersion。 */
    @ParameterizedTest(name = "拒绝缺失或非字符串版本 {0}")
    @ValueSource(strings = {"{}", "{\"schemaVersion\":null}", "{\"schemaVersion\":1}"})
    @DisplayName("拒绝缺失或非字符串版本")
    void rejectsMissingOrNonStringVersion(String schema) {
        assertRejected(bytes(schema), DashboardSchemaParseException.Reason.VERSION_REQUIRED);
    }

    /** 未登记版本不得降级为当前版本解释。 */
    @Test
    @DisplayName("拒绝未登记版本")
    void rejectsUnsupportedVersion() {
        assertRejected(bytes("{\"schemaVersion\":\"tc.dashboard/v2\"}"),
                DashboardSchemaParseException.Reason.VERSION_UNSUPPORTED);
    }

    /** 原文拒绝必须同时给出稳定安全路径，且未知字段名不得进入结构化结果或既有消息。 */
    @Test
    @DisplayName("报告安全结构化路径并保持既有消息")
    void reportsSafeStructuredPathsWithoutChangingMessages() {
        assertRejectedAt(null, DashboardSchemaParseException.Reason.INVALID_JSON, "$", "看板Schema原文不能为空");
        assertRejectedAt(new byte[JacksonDashboardSchemaParser.MAX_RAW_BYTES + 1],
                DashboardSchemaParseException.Reason.RAW_TOO_LARGE,
                "$", "看板Schema原文超过512000字节上限");
        assertRejectedAt(new byte[]{(byte) 0xC3, 0x28}, DashboardSchemaParseException.Reason.INVALID_UTF8,
                "$", "看板Schema原文不是合法UTF-8");
        assertRejectedAt(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'},
                DashboardSchemaParseException.Reason.BOM_NOT_ALLOWED,
                "$", "看板Schema不得包含UTF-8 BOM");
        assertRejectedAt(bytes("[]"), DashboardSchemaParseException.Reason.ROOT_MUST_BE_OBJECT,
                "$", "看板Schema根值必须是JSON对象");
        assertRejectedAt(bytes("{\"schemaVersion\":\"tc.dashboard/v1\"} []"),
                DashboardSchemaParseException.Reason.TRAILING_VALUE,
                "$", "看板Schema根对象后不得包含额外JSON值");
        assertRejectedAt(bytes("{}"), DashboardSchemaParseException.Reason.VERSION_REQUIRED,
                "$.schemaVersion", "看板Schema必须声明字符串类型的schemaVersion");
        assertRejectedAt(bytes("{\"schemaVersion\":\"tc.dashboard/v2\"}"),
                DashboardSchemaParseException.Reason.VERSION_UNSUPPORTED,
                "$.schemaVersion", "看板Schema版本未登记");
        assertRejectedAt(bytes("{\"schemaVersion\":\"tc.dashboard/v1\",\"pages\":[{\"title\":\"\\u0000\"}]}"),
                DashboardSchemaParseException.Reason.INVALID_UNICODE,
                "$.pages[0].title", "看板Schema字符串不得包含U+0000");
        assertRejectedAt(bytes("{\"schemaVersion\":\"tc.dashboard/v1\",\"secret-name\":\"\\u0000\"}"),
                DashboardSchemaParseException.Reason.INVALID_UNICODE,
                "$[\"<unknown>\"]", "看板Schema字符串不得包含U+0000");
        assertRejectedAt(bytes("{\"schemaVersion\":\"tc.dashboard/v1\",\"secret-name\":1e13}"),
                DashboardSchemaParseException.Reason.INVALID_EXPONENT,
                "$[\"<unknown>\"]", "看板Schema数字指数必须为无多余前导零且绝对值不超过12的一至两位整数");
        assertRejectedAt(bytes(schemaWithNumber("1".repeat(65))),
                DashboardSchemaParseException.Reason.NUMBER_TOO_LONG,
                "$[\"<unknown>\"]", "看板Schema数字词法超过64字节上限");
        assertRejectedAt(bytes("{\"schemaVersion\":\"tc.dashboard/v1\",\"nested\":{\"kind\":1,\"\\u006bind\":2}}"),
                DashboardSchemaParseException.Reason.DUPLICATE_KEY,
                "$[\"<unknown>\"].kind", "看板Schema同一对象包含重复属性名");
        assertRejectedAt(bytes("{\"schemaVersion\":}"), DashboardSchemaParseException.Reason.INVALID_JSON,
                "$.schemaVersion", "看板Schema不是合法JSON");

        String depthPath = "$.value" + "[0]".repeat(15);
        assertRejectedAt(bytes(nestedSchema(16)), DashboardSchemaParseException.Reason.DEPTH_EXCEEDED,
                depthPath, "看板Schema对象或数组嵌套超过16层");
    }

    /** 旧构造器继续返回根路径，显式路径构造器不得把路径拼进既有消息。 */
    @Test
    @DisplayName("解析异常构造器保持兼容")
    void parseExceptionConstructorsRemainCompatible() {
        DashboardSchemaParseException legacy = new DashboardSchemaParseException(
                DashboardSchemaParseException.Reason.INVALID_JSON, "旧消息");
        DashboardSchemaParseException legacyNullCause = new DashboardSchemaParseException(
                DashboardSchemaParseException.Reason.INVALID_JSON, "旧消息", null);
        DashboardSchemaParseException structured = DashboardSchemaParseException.atPath(
                DashboardSchemaParseException.Reason.INVALID_JSON, "$.pages[0]", "旧消息");

        assertThat(legacy.path()).isEqualTo("$");
        assertThat(legacy.getMessage()).isEqualTo("旧消息");
        assertThat(legacyNullCause.path()).isEqualTo("$");
        assertThat(legacyNullCause.getMessage()).isEqualTo("旧消息");
        assertThat(legacyNullCause.getCause()).isNull();
        assertThat(structured.path()).isEqualTo("$.pages[0]");
        assertThat(structured.getMessage()).isEqualTo("旧消息");
    }

    /** 数组结构错误必须区分分隔符位置与尚未完成懒解码的元素位置，嵌套数组遵循相同规则。 */
    @Test
    @DisplayName("区分数组分隔符错误和元素解码错误路径")
    void distinguishesArraySeparatorAndLazyValueFailurePaths() {
        assertInvalidJsonAt(schemaWithPages("[0 1]"), "$.pages");
        assertInvalidJsonAt(schemaWithPages("[0"), "$.pages");
        assertInvalidJsonAt(schemaWithPages("[\"\\q\"]"), "$.pages[0]");
        assertInvalidJsonAt(schemaWithPages("[0,]"), "$.pages[1]");
        assertInvalidJsonAt(schemaWithPages("[0,,1]"), "$.pages[1]");
        assertInvalidJsonAt(schemaWithPages("[0 true]"), "$.pages");
        assertInvalidJsonAt(schemaWithPages("[0,truee]"), "$.pages");

        assertInvalidJsonAt(schemaWithPages("[[0 1]]"), "$.pages[0]");
        assertInvalidJsonAt(schemaWithPages("[[0"), "$.pages[0]");
        assertInvalidJsonAt(schemaWithPages("[[\"\\q\"]]"), "$.pages[0][0]");
        assertInvalidJsonAt(schemaWithPages("[[0,]]"), "$.pages[0][1]");
        assertInvalidJsonAt(schemaWithPages("[[0,,1]]"), "$.pages[0][1]");
        assertInvalidJsonAt(schemaWithPages("[[0 true]]"), "$.pages[0]");
        assertInvalidJsonAt(schemaWithPages("[[0,truee]]"), "$.pages[0]");
    }

    /** 逗号后尚未形成值的各类词法错误都必须归下一元素，且嵌套数组保持同一规则。 */
    @Test
    @DisplayName("逗号后未形成值的错误定位下一元素")
    void locatesPreValueLexicalFailuresAtNextArrayElement() {
        assertInvalidJsonAt(schemaWithPages("[0,tru]"), "$.pages[1]");
        assertInvalidJsonAt(schemaWithPages("[0,x]"), "$.pages[1]");
        assertInvalidJsonAt(schemaWithPages("[0,01]"), "$.pages[1]");
        assertInvalidJsonAt(schemaWithPages("[0,1e]"), "$.pages[1]");
        assertInvalidJsonAt(schemaWithPages("[0,//comment\n1]"), "$.pages[1]");
        assertInvalidJsonAt(schemaWithPages("[0,\u00a01]"), "$.pages[1]");

        assertInvalidJsonAt(schemaWithPages("[[0,tru]]"), "$.pages[0][1]");
        assertInvalidJsonAt(schemaWithPages("[[0,x]]"), "$.pages[0][1]");
        assertInvalidJsonAt(schemaWithPages("[[0,01]]"), "$.pages[0][1]");
        assertInvalidJsonAt(schemaWithPages("[[0,1e]]"), "$.pages[0][1]");
        assertInvalidJsonAt(schemaWithPages("[[0,//comment\n1]]"), "$.pages[0][1]");
        assertInvalidJsonAt(schemaWithPages("[[0,\u00a01]]"), "$.pages[0][1]");
    }

    /** 首元素固定值已完成后出现多余字符时，TS先完成该值，因此错误归数组容器。 */
    @Test
    @DisplayName("首元素固定值完成后的错误定位数组容器")
    void locatesCompletedFirstLiteralFailureAtArrayContainer() {
        assertInvalidJsonAt(schemaWithPages("[truex]"), "$.pages");
        assertInvalidJsonAt(schemaWithPages("[falsex]"), "$.pages");
        assertInvalidJsonAt(schemaWithPages("[nullx]"), "$.pages");

        assertInvalidJsonAt(schemaWithPages("[[truex]]"), "$.pages[0]");
        assertInvalidJsonAt(schemaWithPages("[[falsex]]"), "$.pages[0]");
        assertInvalidJsonAt(schemaWithPages("[[nullx]]"), "$.pages[0]");
    }

    /** 子对象首属性尚未形成token时只能定位本对象，且未知父字段必须保持脱敏占位。 */
    @Test
    @DisplayName("对象首属性词法错误定位当前安全对象")
    void locatesInvalidFirstPropertyAtSafeCurrentObject() {
        assertInvalidJsonAt(schemaWithPages("[{x}]"), "$.pages[0]");
        assertInvalidJsonAt(
                "{\"schemaVersion\":\"tc.dashboard/v1\",\"secret-name\":{x}}",
                "$[\"<unknown>\"]");
        assertInvalidJsonAt("{\"schemaVersion\":\"tc.dashboard/v1\",\"pages\":}", "$.pages");
        assertInvalidJsonAt("{\"schemaVersion\":\"tc.dashboard/v1\",\"pages\":{\"a\" [0]}}",
                "$.pages[\"<unknown>\"]");
        assertInvalidJsonAt("{\"schemaVersion\":\"tc.dashboard/v1\",\"pages\":{0]}", "$.pages");
        assertInvalidJsonAt("{\"schemaVersion\":\"tc.dashboard/v1\",\"pages\":0]}", "$");
        assertInvalidJsonAt(
                "{\"schemaVersion\":\"tc.dashboard/v1\",\"pages\":{\"a\":0,false]}}",
                "$.pages");
    }

    /** 高代理项后的第二转义必须区分语义代理错误与JSON十六进制词法错误。 */
    @Test
    @DisplayName("区分高代理项配对错误和Unicode转义词法错误")
    void distinguishesSurrogatePairingFromUnicodeEscapeSyntax() {
        assertRejectedAt(bytes(schemaWithPages("[\"\\uD800x\"]")),
                DashboardSchemaParseException.Reason.INVALID_UNICODE,
                "$.pages[0]", "看板Schema字符串包含未配对UTF-16代理项");
        assertInvalidJsonAt(schemaWithPages("[\"\\uD800\\uZZZZ\"]"), "$.pages[0]");
        assertInvalidJsonAt(schemaWithPages("[\"\\uD800\\u12\"]"), "$.pages[0]");
        assertRejectedAt(bytes(schemaWithPages("[\"\\uD800\\u0041\"]")),
                DashboardSchemaParseException.Reason.INVALID_UNICODE,
                "$.pages[0]", "看板Schema字符串包含未配对UTF-16代理项");
        assertRejectedAt(bytes(schemaWithPages("[\"\\uD800\\\"\"]")),
                DashboardSchemaParseException.Reason.INVALID_UNICODE,
                "$.pages[0]", "看板Schema字符串包含未配对UTF-16代理项");
        for (int hexDigits = 0; hexDigits <= 3; hexDigits++) {
            assertInvalidJsonAt(unterminatedSecondUnicodeEscape(hexDigits), "$.pages[0]");
        }
    }

    /** 使用UTF-8解析合法Schema文本。 */
    private ParsedDashboardSchema parse(String schema) {
        return parser.parse(bytes(schema));
    }

    /** 构造只包含合同版本的最小合法Schema原文。 */
    private static byte[] validSchema() {
        return bytes("{\"schemaVersion\":\"tc.dashboard/v1\"}");
    }

    /** 构造包含待测数字token的合法对象外壳。 */
    private static String schemaWithNumber(String number) {
        return "{\"schemaVersion\":\"tc.dashboard/v1\",\"number\":" + number + "}";
    }

    /** 构造包含待测pages原文的根对象，不要求其通过后续语义校验。 */
    private static String schemaWithPages(String pages) {
        return "{\"schemaVersion\":\"tc.dashboard/v1\",\"pages\":" + pages + "}";
    }

    /** 构造第二个Unicode转义在EOF处缺少十六进制位的字符串。 */
    private static String unterminatedSecondUnicodeEscape(int hexDigits) {
        return "{\"schemaVersion\":\"tc.dashboard/v1\",\"pages\":[\"\\uD800\\u"
                + "0".repeat(hexDigits);
    }

    /** 构造根对象加指定数组层数的Schema，根本身计一层。 */
    private static String nestedSchema(int arrayLayers) {
        return "{\"schemaVersion\":\"tc.dashboard/v1\",\"value\":"
                + "[".repeat(arrayLayers) + "0" + "]".repeat(arrayLayers) + "}";
    }

    /** 将测试JSON按合同要求编码为UTF-8原文字节。 */
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** 断言输入以指定稳定原因被拒绝。 */
    private void assertRejected(byte[] source, DashboardSchemaParseException.Reason expectedReason) {
        assertThatThrownBy(() -> parser.parse(source))
                .isInstanceOfSatisfying(DashboardSchemaParseException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(expectedReason));
    }

    /** 断言输入以指定稳定原因、安全路径和原消息被拒绝。 */
    private void assertRejectedAt(
            byte[] source,
            DashboardSchemaParseException.Reason expectedReason,
            String expectedPath,
            String expectedMessage) {
        assertThatThrownBy(() -> parser.parse(source))
                .isInstanceOfSatisfying(DashboardSchemaParseException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(expectedReason);
                    assertThat(exception.path()).isEqualTo(expectedPath);
                    assertThat(exception.getMessage()).isEqualTo(expectedMessage);
                    assertThat(exception.path()).doesNotContain("secret-name");
                });
    }

    /** 断言非法JSON结构在指定安全路径失败。 */
    private void assertInvalidJsonAt(String source, String expectedPath) {
        assertRejectedAt(bytes(source), DashboardSchemaParseException.Reason.INVALID_JSON,
                expectedPath, "看板Schema不是合法JSON");
    }
}
