package com.things.link.dashboard.application.schema;

import com.things.link.dashboard.infrastructure.schema.JacksonDashboardSchemaParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 看板根、模型、画布、页面、组件壳与布局合同测试。 */
@DisplayName("看板画布结构合同")
class DashboardSchemaCanvasStructureTests {
    /** 测试使用固定原文解析到语义校验管线。 */
    private final DashboardSchemaValidator validator = DashboardSchemaValidator.using(new JacksonDashboardSchemaParser());

    /** 响应式画布应注入冻结参数，并接受同页矩形接边。 */
    @Test
    @DisplayName("响应式画布注入默认值并允许组件接边")
    void normalizesResponsiveCanvasAndAllowsTouchingRectangles() {
        DashboardSchemaValidationResult result = validate(schema(
                "{\"mode\":\"RESPONSIVE_GRID\"}", "[]",
                "[" + page("main", component("left", "{\"x\":0,\"y\":0,\"w\":12,\"h\":8}")
                        + "," + component("right", "{\"x\":12,\"y\":0,\"w\":12,\"h\":8}")) + "]"));

        JsonNode presentation = result.normalizedRoot().get("presentation");
        assertThat(presentation.get("theme").asString()).isEqualTo("LIGHT");
        assertThat(presentation.get("columns").asInt()).isEqualTo(24);
        assertThat(presentation.get("rowHeight").asInt()).isEqualTo(8);
        assertThat(presentation.get("gap").asInt()).isEqualTo(8);
    }

    /** 固定画布应注入1920乘1080、FIT与LIGHT默认值并接受边界像素。 */
    @Test
    @DisplayName("固定画布注入默认值并接受边界矩形")
    void normalizesFixedCanvasAndAcceptsBoundaryRectangle() {
        DashboardSchemaValidationResult result = validate(schema(
                "{\"mode\":\"FIXED_SCREEN\"}", "[]",
                "[" + page("main", component("pixel", "{\"x\":1919,\"y\":1079,\"w\":1,\"h\":1}")) + "]"));

        JsonNode presentation = result.normalizedRoot().get("presentation");
        assertThat(presentation.get("width").asInt()).isEqualTo(1920);
        assertThat(presentation.get("height").asInt()).isEqualTo(1080);
        assertThat(presentation.get("scaleMode").asString()).isEqualTo("FIT");
        assertThat(presentation.get("theme").asString()).isEqualTo("LIGHT");
    }

    /** 模型集合应接受20项边界并保持输入顺序。 */
    @Test
    @DisplayName("模型集合接受20项边界")
    void acceptsTwentyUniqueModels() {
        StringBuilder models = new StringBuilder("[");
        for (int index = 0; index < 20; index++) {
            if (index > 0) models.append(',');
            models.append(model(index));
        }
        models.append(']');

        DashboardSchemaValidationResult result = validate(schema(
                "{\"mode\":\"RESPONSIVE_GRID\"}", models.toString(),
                "[" + page("main", "") + "]"));

        assertThat(result.normalizedRoot().get("models")).hasSize(20);
        assertThat(result.normalizedRoot().get("models").get(0).get("key").asString()).isEqualTo("model_0");
    }

    /** 模型key与versionId任一重复都必须拒绝。 */
    @Test
    @DisplayName("拒绝重复模型key和versionId")
    void rejectsDuplicateModelIdentities() {
        String first = model(1);
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\"}", "[" + first + "," + first + "]",
                "[" + page("main", "") + "]"), DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
        String duplicateVersion = model(2).replace("model_2", "other_model");
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\"}", "[" + model(2) + "," + duplicateVersion + "]",
                "[" + page("main", "") + "]"), DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
    }

    /** 模型引用必须字段完整、值格式正确且使用冻结摘要算法与Profile。 */
    @Test
    @DisplayName("拒绝不完整或常量漂移的模型引用")
    void rejectsInvalidModelReferenceShapeAndConstants() {
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\"}",
                        "[" + model(1).replace(",\"digest\":\"" + "%064x".formatted(2) + "\"", "") + "]",
                        "[" + page("main", "") + "]"),
                DashboardSchemaValidationException.Reason.REQUIRED_FIELD_MISSING);
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\"}",
                        "[" + model(1).replace("PG_JSONB_TEXT_V1_SHA256", "SHA256") + "]",
                        "[" + page("main", "") + "]"),
                DashboardSchemaValidationException.Reason.INVALID_VALUE);
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\"}",
                        "[" + model(1).replace("model_1", "Model") + "]",
                        "[" + page("main", "") + "]"),
                DashboardSchemaValidationException.Reason.INVALID_VALUE);
    }

    /** 第21个模型、响应式第6页和第51个页内组件都越过集合上限。 */
    @Test
    @DisplayName("拒绝模型页面和组件集合越界")
    void rejectsCollectionLimits() {
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\"}", repeatedModels(21),
                "[" + page("main", "") + "]"), DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\"}", "[]", repeatedPages(6)),
                DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\"}", "[]",
                "[" + page("main", repeatedComponents(51)) + "]"),
                DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
    }

    /** 两种画布都要求至少一页，固定画布也不能接受空集合。 */
    @Test
    @DisplayName("拒绝空页面集合")
    void rejectsEmptyPageCollections() {
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\"}", "[]", "[]"),
                DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
        assertRejected(schema("{\"mode\":\"FIXED_SCREEN\"}", "[]", "[]"),
                DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
    }

    /** 固定画布必须恰好一页，不能沿用响应式多页规则。 */
    @Test
    @DisplayName("固定画布拒绝多页")
    void fixedCanvasRejectsMultiplePages() {
        assertRejected(schema("{\"mode\":\"FIXED_SCREEN\"}", "[]",
                "[" + page("first", "") + "," + page("second", "") + "]"),
                DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
    }

    /** 页面id全文唯一，组件id还必须跨页全文唯一。 */
    @Test
    @DisplayName("拒绝重复页面与跨页组件id")
    void rejectsDuplicatePageAndGlobalComponentIds() {
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\"}", "[]",
                "[" + page("same", "") + "," + page("same", "") + "]"),
                DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\"}", "[]",
                "[" + page("first", component("same", "{\"x\":0,\"y\":0,\"w\":1,\"h\":1}"))
                        + "," + page("second", component("same", "{\"x\":0,\"y\":0,\"w\":1,\"h\":1}")) + "]"),
                DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
    }

    /** 响应式和固定画布都必须拒绝越界矩形。 */
    @Test
    @DisplayName("拒绝两种画布越界矩形")
    void rejectsOutOfBoundsLayouts() {
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\"}", "[]",
                "[" + page("main", component("bad", "{\"x\":23,\"y\":0,\"w\":2,\"h\":1}")) + "]"),
                DashboardSchemaValidationException.Reason.INVALID_LAYOUT);
        assertRejected(schema("{\"mode\":\"FIXED_SCREEN\"}", "[]",
                "[" + page("main", component("bad", "{\"x\":0,\"y\":1079,\"w\":1,\"h\":2}")) + "]"),
                DashboardSchemaValidationException.Reason.INVALID_LAYOUT);
    }

    /** 同页有正面积交叠必须拒绝，不能依赖CSS自动移动。 */
    @Test
    @DisplayName("拒绝同页组件矩形重叠")
    void rejectsOverlappingRectangles() {
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\"}", "[]",
                "[" + page("main", component("first", "{\"x\":0,\"y\":0,\"w\":12,\"h\":8}")
                        + "," + component("second", "{\"x\":11,\"y\":0,\"w\":12,\"h\":8}")) + "]"),
                DashboardSchemaValidationException.Reason.INVALID_LAYOUT);
    }

    /** 布局字段必须使用JSON整数词法并保持封闭。 */
    @Test
    @DisplayName("拒绝布局浮点词法和未知字段")
    void rejectsNonIntegerAndUnknownLayoutFields() {
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\"}", "[]",
                "[" + page("main", component("bad", "{\"x\":0.0,\"y\":0,\"w\":1,\"h\":1}")) + "]"),
                DashboardSchemaValidationException.Reason.TYPE_MISMATCH);
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\"}", "[]",
                "[" + page("main", component("bad", "{\"x\":0,\"y\":0,\"w\":1,\"h\":1,\"z\":1}")) + "]"),
                DashboardSchemaValidationException.Reason.UNKNOWN_FIELD);
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\"}", "[]",
                "[" + page("main", component("bad", "{\"x\":-1,\"y\":0,\"w\":1,\"h\":1}")) + "]"),
                DashboardSchemaValidationException.Reason.INVALID_VALUE);
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\"}", "[]",
                "[" + page("main", component("bad", "{\"x\":0,\"y\":0,\"w\":1,\"h\":0}")) + "]"),
                DashboardSchemaValidationException.Reason.INVALID_VALUE);
    }

    /** 画布分支字段互斥，冻结参数不能被其他值替代。 */
    @Test
    @DisplayName("拒绝画布分支混用和参数漂移")
    void rejectsMixedOrChangedPresentationFields() {
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\",\"width\":1920}", "[]",
                "[" + page("main", "") + "]"), DashboardSchemaValidationException.Reason.UNKNOWN_FIELD);
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\",\"columns\":12}", "[]",
                "[" + page("main", "") + "]"), DashboardSchemaValidationException.Reason.INVALID_VALUE);
        assertRejected(schema("{\"mode\":\"FIXED_SCREEN\",\"columns\":24}", "[]",
                "[" + page("main", "") + "]"), DashboardSchemaValidationException.Reason.UNKNOWN_FIELD);
    }

    /** 未知枚举值不得进入异常消息，避免控制字符和大字段污染日志或HTTP错误。 */
    @Test
    @DisplayName("枚举拒绝消息不回显外部输入")
    void enumRejectionDoesNotEchoExternalInput() {
        String injected = "UNKNOWN\\nINJECTED";
        String source = schema("{\"mode\":\"" + injected + "\"}", "[]", "[]");

        assertThatThrownBy(() -> validate(source))
                .isInstanceOfSatisfying(DashboardSchemaValidationException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(DashboardSchemaValidationException.Reason.INVALID_VALUE);
                    assertThat(exception.getMessage()).doesNotContain("UNKNOWN", "INJECTED", "\n");
                });
    }

    /** 模型、页面和组件壳对象继续执行未知字段、null和严格对象类型规则。 */
    @Test
    @DisplayName("拒绝壳对象未知字段null和错类型")
    void rejectsInvalidShellStructure() {
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\"}",
                "[" + model(1).replace("}", ",\"current\":true}") + "]",
                "[" + page("main", "") + "]"), DashboardSchemaValidationException.Reason.UNKNOWN_FIELD);
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\"}", "[]",
                "[{\"id\":\"main\",\"title\":null,\"components\":[]}]"),
                DashboardSchemaValidationException.Reason.NULL_NOT_ALLOWED);
        String invalidComponent = component("bad", "{\"x\":0,\"y\":0,\"w\":1,\"h\":1}")
                .replace("\"props\":{}", "\"props\":[]");
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\"}", "[]",
                "[" + page("main", invalidComponent) + "]"), DashboardSchemaValidationException.Reason.TYPE_MISMATCH);
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\"}", "[]",
                "[{\"id\":\"main\",\"title\":\"页面\",\"components\":[],\"default\":true}]"),
                DashboardSchemaValidationException.Reason.UNKNOWN_FIELD);
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\"}", "[]",
                "[" + page("main", component("bad", "{\"x\":0,\"y\":0,\"w\":1,\"h\":1}")
                        .replace(",\"bindings\":{}", "")) + "]"),
                DashboardSchemaValidationException.Reason.REQUIRED_FIELD_MISSING);
    }

    /** 未知组件和非精确组件版本必须在组件壳阶段拒绝。 */
    @Test
    @DisplayName("拒绝未知组件和非精确版本")
    void rejectsUnknownComponentAndVersion() {
        String unknown = component("bad", "{\"x\":0,\"y\":0,\"w\":1,\"h\":1}")
                .replace("\"kind\":\"TEXT\"", "\"kind\":\"COMMAND_BUTTON\"");
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\"}", "[]",
                "[" + page("main", unknown) + "]"), DashboardSchemaValidationException.Reason.INVALID_VALUE);
        String wrongVersion = component("bad", "{\"x\":0,\"y\":0,\"w\":1,\"h\":1}")
                .replace("1.0.0", "1.0.2");
        assertRejected(schema("{\"mode\":\"RESPONSIVE_GRID\"}", "[]",
                "[" + page("main", wrongVersion) + "]"), DashboardSchemaValidationException.Reason.INVALID_VALUE);
    }

    /** 构造完整根Schema。 */
    private static String schema(String presentation, String models, String pages) {
        return "{\"schemaVersion\":\"tc.dashboard/v1\",\"presentation\":" + presentation
                + ",\"models\":" + models + ",\"variables\":[],\"pages\":" + pages + "}";
    }

    /** 构造页面及其组件JSON列表正文。 */
    private static String page(String id, String components) {
        return "{\"id\":\"" + id + "\",\"title\":\"页面\",\"components\":[" + components + "]}";
    }

    /** 构造TEXT组件壳；props和bindings语义留给后续切片。 */
    private static String component(String id, String layout) {
        return "{\"id\":\"" + id + "\",\"kind\":\"TEXT\",\"componentVersion\":\"1.0.0\","
                + "\"layout\":" + layout + ",\"props\":{},\"bindings\":{}}";
    }

    /** 构造唯一模型引用。 */
    private static String model(int index) {
        return "{\"key\":\"model_" + index + "\",\"versionId\":\"00000000-0000-0000-0000-"
                + "%012d".formatted(index) + "\",\"digestAlgorithm\":\"PG_JSONB_TEXT_V1_SHA256\","
                + "\"digest\":\"" + "%064x".formatted(index + 1) + "\",\"profile\":\"TC_PROPERTY_COMPOSITE_V1\"}";
    }

    /** 构造指定数量的唯一模型数组。 */
    private static String repeatedModels(int count) {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < count; index++) {
            if (index > 0) result.append(',');
            result.append(model(index));
        }
        return result.append(']').toString();
    }

    /** 构造指定数量的空页面数组。 */
    private static String repeatedPages(int count) {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < count; index++) {
            if (index > 0) result.append(',');
            result.append(page("page_" + index, ""));
        }
        return result.append(']').toString();
    }

    /** 构造指定数量、不重叠且id唯一的组件列表正文。 */
    private static String repeatedComponents(int count) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < count; index++) {
            if (index > 0) result.append(',');
            result.append(component("component_" + index,
                    "{\"x\":0,\"y\":" + index + ",\"w\":1,\"h\":1}"));
        }
        return result.toString();
    }

    /** 通过内部门面执行完整当前范围校验。 */
    private DashboardSchemaValidationResult validate(String source) {
        return validator.validateAndNormalize(source.getBytes(StandardCharsets.UTF_8));
    }

    /** 断言当前语义层按稳定原因拒绝。 */
    private void assertRejected(String source, DashboardSchemaValidationException.Reason reason) {
        assertThatThrownBy(() -> validate(source))
                .isInstanceOfSatisfying(DashboardSchemaValidationException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(reason));
    }
}
