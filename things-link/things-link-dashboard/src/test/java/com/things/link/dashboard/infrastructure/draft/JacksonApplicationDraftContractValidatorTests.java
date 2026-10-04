package com.things.link.dashboard.infrastructure.draft;

import com.things.link.dashboard.application.draft.ApplicationDraftContractViolation;
import com.things.link.dashboard.application.draft.ValidatedApplicationDraft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S12-1a2应用草稿原文字节与封闭业务合同的低层单元测试。 */
@DisplayName("应用草稿原文字节合同")
class JacksonApplicationDraftContractValidatorTests {
    /** 被测无状态应用草稿合同校验器。 */
    private final JacksonApplicationDraftContractValidator validator =
            new JacksonApplicationDraftContractValidator();

    /** 合法revision和精确引用应形成隔离结果，调用方改写副本不能污染已校验内容。 */
    @Test
    @DisplayName("接受完整草稿并隔离返回树")
    void acceptsCompleteDraftAndIsolatesReturnedTree() {
        String firstId = uuid(1);
        String secondId = uuid(2);
        ValidatedApplicationDraft result = validator.validate("9223372036854775806", bytes(content(
                reference(firstId, uuid(11), "总览") + "," + reference(secondId, uuid(12), "告警"), firstId)));

        assertThat(result.expectedRevision()).isEqualTo(Long.MAX_VALUE - 1);
        ((ObjectNode) result.content()).put("displayName", "外部改写");
        assertThat(result.content().get("displayName").asString()).isEqualTo("测试应用");
    }

    /** 原始content按字节精确接受64KiB并在解析前拒绝多一个字节。 */
    @Test
    @DisplayName("执行原文64KiB精确边界")
    void enforcesExactRawByteBoundary() {
        byte[] minimum = bytes(content("", null));
        byte[] exact = Arrays.copyOf(minimum, JacksonApplicationDraftContractValidator.MAXIMUM_BYTES);
        Arrays.fill(exact, minimum.length, exact.length, (byte) ' ');
        byte[] over = Arrays.copyOf(exact, exact.length + 1);
        over[over.length - 1] = ' ';

        assertThat(validator.validate("0", exact).expectedRevision()).isZero();
        assertRejected("0", over, ApplicationDraftContractViolation.Reason.RAW_TOO_LARGE);
    }

    /** 紧凑原文可小于上限，但PostgreSQL jsonb分隔空格后的规范文本仍须独立拒绝。 */
    @Test
    @DisplayName("独立执行PostgreSQL规范文本64KiB边界")
    void enforcesPostgresNormalizedByteBoundary() {
        String prefix = "{\"formatVersion\":\"tc.application/v1\",\"displayName\":\"";
        String suffix = "\",\"hostCompatibility\":{\"minInclusive\":\"1.0.0\",\"maxExclusive\":\"2.0.0\"},"
                + "\"dashboardRefs\":[],\"entryDashboardId\":null}";
        int padding = JacksonApplicationDraftContractValidator.MAXIMUM_BYTES
                - bytes(prefix + suffix).length;
        byte[] compactAtRawLimit = bytes(prefix + "a".repeat(padding) + suffix);

        assertThat(compactAtRawLimit).hasSize(JacksonApplicationDraftContractValidator.MAXIMUM_BYTES);
        assertRejected("0", compactAtRawLimit,
                ApplicationDraftContractViolation.Reason.NORMALIZED_TOO_LARGE);
    }

    /** UTF-8、Unicode标量、重复键和最大深度必须在普通JSON树吞掉证据前拒绝。 */
    @Test
    @DisplayName("拒绝不可恢复的原文问题")
    void rejectsIrrecoverableRawInputProblems() {
        assertRejected("0", new byte[]{(byte) 0xC3, 0x28},
                ApplicationDraftContractViolation.Reason.INVALID_UTF8);
        assertRejected("0", bytes(content("", null).replace("测试应用", "\\u0000")),
                ApplicationDraftContractViolation.Reason.INVALID_UNICODE);
        assertThatThrownBy(() -> validator.validate("0", bytes(content("", null)
                .replace("测试应用", "\\uD800"))))
                .isInstanceOf(ApplicationDraftContractViolation.class);
        assertRejected("0", bytes(content("", null).replace(
                        "\"displayName\":\"测试应用\"",
                        "\"displayName\":\"测试应用\",\"\\u0064isplayName\":\"重复\"")),
                ApplicationDraftContractViolation.Reason.DUPLICATE_KEY);
        assertRejected("0", bytes("{\"value\":" + "[".repeat(8) + "0" + "]".repeat(8) + "}"),
                ApplicationDraftContractViolation.Reason.DEPTH_EXCEEDED);
    }

    /** 顶层和嵌套对象均为字段闭集，缺失、未知与非法null不能互相折叠。 */
    @Test
    @DisplayName("区分未知缺失与null字段")
    void distinguishesUnknownMissingAndNullFields() {
        assertRejected("0", bytes(content("", null).replace(
                        "\"formatVersion\":\"tc.application/v1\",", "")),
                ApplicationDraftContractViolation.Reason.REQUIRED_FIELD_MISSING);
        assertRejected("0", bytes(content("", null).replace(
                        "\"displayName\":\"测试应用\"", "\"displayName\":null")),
                ApplicationDraftContractViolation.Reason.NULL_NOT_ALLOWED);
        assertRejected("0", bytes(content("", null).replace(
                        "\"dashboardRefs\":[]", "\"dashboardRefs\":[],\"secret\":true")),
                ApplicationDraftContractViolation.Reason.UNKNOWN_FIELD);
        assertRejected("0", bytes(content("", null).replace(
                        "\"maxExclusive\":\"2.0.0\"",
                        "\"maxExclusive\":\"2.0.0\",\"wildcard\":\"*\"")),
                ApplicationDraftContractViolation.Reason.UNKNOWN_FIELD);
    }

    /** Revision必须保持字符串十进制词法且不能越过Long上限。 */
    @ParameterizedTest(name = "拒绝revision {0}")
    @ValueSource(strings = {"", "00", "01", "+1", "-1", "1.0", "1e1", "9223372036854775808"})
    @DisplayName("拒绝非法Revision")
    void rejectsInvalidRevision(String revision) {
        assertRejected(revision, bytes(content("", null)),
                ApplicationDraftContractViolation.Reason.INVALID_VALUE);
    }

    /** Long最大值仍是合法调用方事实，是否存在及是否耗尽必须留给仓储原子分类。 */
    @Test
    @DisplayName("接受Long最大Revision供仓储分类")
    void acceptsMaximumRevisionForAtomicPersistenceClassification() {
        ValidatedApplicationDraft result = validator.validate(
                Long.toString(Long.MAX_VALUE), bytes(content("", null)));

        assertThat(result.expectedRevision()).isEqualTo(Long.MAX_VALUE);
    }

    /** Title、规范UUID和有限HostRange必须按精确语法与数值顺序校验。 */
    @Test
    @DisplayName("拒绝非法Title UUID与HostRange")
    void rejectsInvalidScalarContracts() {
        assertRejected("0", bytes(content("", null).replace("测试应用", "   ")),
                ApplicationDraftContractViolation.Reason.INVALID_VALUE);
        assertRejected("0", bytes(content("", null).replace("测试应用", "\u00a0")),
                ApplicationDraftContractViolation.Reason.INVALID_VALUE);
        String uppercaseUuid = "AAAAAAAA-0000-0000-0000-000000000001";
        assertRejected("0", bytes(content(reference(
                        uppercaseUuid, uuid(11), "总览"), uppercaseUuid)),
                ApplicationDraftContractViolation.Reason.INVALID_VALUE);
        assertRejected("0", bytes(content("", null).replace("1.0.0", "01.0.0")),
                ApplicationDraftContractViolation.Reason.INVALID_VALUE);
        assertRejected("0", bytes(content("", null).replace("2.0.0", "1.0.0")),
                ApplicationDraftContractViolation.Reason.INVALID_VALUE);
    }

    /** 引用数量、稳定ID唯一性和入口命中必须一起保持应用导航确定性。 */
    @Test
    @DisplayName("拒绝越界重复或未命中的Dashboard引用")
    void rejectsInvalidDashboardReferenceCollections() {
        String repeated = reference(uuid(1), uuid(11), "总览");
        assertRejected("0", bytes(content(String.join(",", repeated, repeated), uuid(1))),
                ApplicationDraftContractViolation.Reason.INVALID_COLLECTION);
        String six = String.join(",", reference(uuid(1), uuid(11), "一"),
                reference(uuid(2), uuid(12), "二"), reference(uuid(3), uuid(13), "三"),
                reference(uuid(4), uuid(14), "四"), reference(uuid(5), uuid(15), "五"),
                reference(uuid(6), uuid(16), "六"));
        assertRejected("0", bytes(content(six, uuid(1))),
                ApplicationDraftContractViolation.Reason.INVALID_COLLECTION);
        assertRejected("0", bytes(content(repeated, uuid(2))),
                ApplicationDraftContractViolation.Reason.INVALID_REFERENCE);
        assertRejected("0", bytes(content(repeated, null)),
                ApplicationDraftContractViolation.Reason.INVALID_REFERENCE);
    }

    /** 构造完整草稿content；空引用时入口固定为null。 */
    private static String content(String references, String entryDashboardId) {
        String entry = entryDashboardId == null ? "null" : "\"" + entryDashboardId + "\"";
        return "{\"formatVersion\":\"tc.application/v1\",\"displayName\":\"测试应用\","
                + "\"hostCompatibility\":{\"minInclusive\":\"1.0.0\",\"maxExclusive\":\"2.0.0\"},"
                + "\"dashboardRefs\":[" + references + "],\"entryDashboardId\":" + entry + "}";
    }

    /** 构造一个精确草稿Dashboard引用。 */
    private static String reference(String dashboardId, String versionId, String title) {
        return "{\"dashboardId\":\"" + dashboardId + "\",\"dashboardVersionId\":\""
                + versionId + "\",\"title\":\"" + title + "\"}";
    }

    /** 构造确定、规范的小写UUID。 */
    private static String uuid(int value) {
        return "00000000-0000-0000-0000-%012d".formatted(value);
    }

    /** 按合同把测试JSON编码为UTF-8原文字节。 */
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** 断言稳定拒绝原因，避免测试依赖Jackson异常消息。 */
    private void assertRejected(
            String revision, byte[] source, ApplicationDraftContractViolation.Reason reason) {
        assertThatThrownBy(() -> validator.validate(revision, source))
                .isInstanceOfSatisfying(ApplicationDraftContractViolation.class,
                        exception -> assertThat(exception.reason()).isEqualTo(reason));
    }
}
