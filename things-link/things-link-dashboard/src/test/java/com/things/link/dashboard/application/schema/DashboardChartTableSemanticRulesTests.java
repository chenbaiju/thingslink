package com.things.link.dashboard.application.schema;

import com.things.link.dashboard.infrastructure.schema.JacksonDashboardSchemaParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 折线图、表格及十组件完整内部语义测试。 */
@DisplayName("看板折线图与表格语义")
class DashboardChartTableSemanticRulesTests {
    /** 原文解析到完整内部语义的固定管线。 */
    private final DashboardSchemaValidator validator = DashboardSchemaValidator.using(new JacksonDashboardSchemaParser());

    /** LINE_CHART校验两侧同序映射、默认值并投影完整历史需求。 */
    @Test
    @DisplayName("折线序列一一对应并保留完整历史身份")
    void lineChartMapsSeriesAndProjectsHistoryIdentity() {
        String component = component("line", "LINE_CHART",
                "{\"series\":[{\"id\":\"temperature\",\"label\":\"温度\"},"
                        + "{\"id\":\"pressure\",\"label\":\"压力\"}]}",
                "{\"series\":[{\"id\":\"temperature\",\"value\":"
                        + history("temperature", "AVG") + "},{\"id\":\"pressure\",\"value\":"
                        + history("pressure", "MAX") + "}]}");
        DashboardSchemaValidationResult result = validate(schema(component));

        assertThat(firstComponent(result).get("props").get("showLegend").asBoolean()).isTrue();
        assertThat(result.unresolvedRequirements().stream()
                .filter(HistoricalPropertyRequirement.class::isInstance)
                .map(HistoricalPropertyRequirement.class::cast))
                .extracting(HistoricalPropertyRequirement::propertyKey,
                        HistoricalPropertyRequirement::granularity,
                        HistoricalPropertyRequirement::aggregation)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("temperature", "ONE_MINUTE", "AVG"),
                        org.assertj.core.groups.Tuple.tuple("pressure", "ONE_MINUTE", "MAX"));
        assertThat(result.unresolvedRequirements().stream()
                .filter(DataAdapterRequirement.class::isInstance)
                .map(DataAdapterRequirement.class::cast)
                .map(DataAdapterRequirement::capability))
                .contains(AdapterCapability.HISTORY_SERIES);
    }

    /** 完整历史绑定去重必须包含粒度和聚合，不能只按属性键误判。 */
    @Test
    @DisplayName("按完整历史绑定身份去重")
    void rejectsOnlyExactDuplicateHistoryBindings() {
        String props = "{\"series\":[{\"id\":\"first\",\"label\":\"第一\"},"
                + "{\"id\":\"second\",\"label\":\"第二\"}]}";
        String distinct = "{\"series\":[{\"id\":\"first\",\"value\":" + history("temperature", "AVG")
                + "},{\"id\":\"second\",\"value\":" + history("temperature", "MAX") + "}]}";
        validate(schema(component("line", "LINE_CHART", props, distinct)));

        String duplicate = "{\"series\":[{\"id\":\"first\",\"value\":" + history("temperature", "AVG")
                + "},{\"id\":\"second\",\"value\":" + history("temperature", "AVG") + "}]}";
        assertRejected(component("line", "LINE_CHART", props, duplicate),
                DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
    }

    /** LINE_CHART拒绝空数组、重复id、数量不等和顺序错配。 */
    @Test
    @DisplayName("拒绝折线序列集合和映射错误")
    void rejectsInvalidLineSeriesMappings() {
        assertRejected(component("line", "LINE_CHART", "{\"series\":[]}", "{\"series\":[]}"),
                DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
        assertRejected(component("line", "LINE_CHART", lineProps(5), lineBindings(5)),
                DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
        assertRejected(component("line", "LINE_CHART", lineProps(2), lineBindings(1)),
                DashboardSchemaValidationException.Reason.INVALID_REFERENCE);
        assertRejected(component("line", "LINE_CHART",
                        "{\"series\":[{\"id\":\"same\",\"label\":\"一\"},{\"id\":\"same\",\"label\":\"二\"}]}",
                        "{\"series\":[{\"id\":\"same\",\"value\":" + history("temperature", "AVG")
                                + "},{\"id\":\"same\",\"value\":" + history("pressure", "AVG") + "}]}"),
                DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
        assertRejected(component("line", "LINE_CHART",
                        "{\"series\":[{\"id\":\"first\",\"label\":\"一\"}]}",
                        "{\"series\":[{\"id\":\"second\",\"value\":" + history("temperature", "AVG") + "}]}"),
                DashboardSchemaValidationException.Reason.INVALID_REFERENCE);
    }

    /** TABLE LIST_VALUE只接收单设备顶层LIST并产生完整列表快照需求。 */
    @Test
    @DisplayName("表格列表模式接收单设备LIST")
    void tableListValueUsesSingleListSnapshot() {
        DashboardSchemaValidationResult result = validate(schema(component(
                "table", "TABLE", "{\"mode\":\"LIST_VALUE\"}",
                "{\"value\":" + current("single", "payload") + "}")));

        assertThat(firstComponent(result).get("props").get("rowLimit").asInt()).isEqualTo(20);
        assertThat(result.unresolvedRequirements().stream()
                .filter(ModelPropertyRequirement.class::isInstance)
                .map(ModelPropertyRequirement.class::cast)
                .map(ModelPropertyRequirement::allowedDataTypes))
                .contains(Set.of(DashboardComponentDescriptorRegistry.PropertyDataType.LIST));
        assertThat(result.unresolvedRequirements().stream()
                .filter(DataAdapterRequirement.class::isInstance)
                .map(DataAdapterRequirement.class::cast)
                .map(DataAdapterRequirement::capability))
                .contains(AdapterCapability.LIST_SNAPSHOT);
    }

    /** TABLE LIST_VALUE拒绝columns、多设备变量及未声明slot。 */
    @Test
    @DisplayName("拒绝列表表格非法分支")
    void rejectsInvalidListValueTable() {
        assertRejected(component("table", "TABLE",
                        "{\"mode\":\"LIST_VALUE\",\"columns\":[{\"id\":\"x\",\"label\":\"X\"}]}",
                        "{\"value\":" + current("single", "payload") + "}"),
                DashboardSchemaValidationException.Reason.INVALID_VALUE);
        assertRejected(component("table", "TABLE", "{\"mode\":\"LIST_VALUE\"}",
                        "{\"value\":" + current("multi", "payload") + "}"),
                DashboardSchemaValidationException.Reason.INVALID_REFERENCE);
    }

    /** TABLE DEVICE_VALUES要求两侧列同序同id且全部使用同一多设备变量。 */
    @Test
    @DisplayName("多设备表格映射同一变量的多列")
    void tableDeviceValuesMapsColumnsToOneMultiVariable() {
        DashboardSchemaValidationResult result = validate(schema(component(
                "table", "TABLE",
                "{\"mode\":\"DEVICE_VALUES\",\"columns\":[{\"id\":\"temperature\",\"label\":\"温度\"},"
                        + "{\"id\":\"pressure\",\"label\":\"压力\"}]}",
                "{\"columns\":[{\"id\":\"temperature\",\"value\":" + current("multi", "temperature")
                        + "},{\"id\":\"pressure\",\"value\":" + current("multi", "pressure") + "}]}")));

        assertThat(result.unresolvedRequirements().stream()
                .filter(ModelPropertyRequirement.class::isInstance)
                .map(ModelPropertyRequirement.class::cast)
                .filter(requirement -> requirement.propertyKey().equals("temperature")
                        || requirement.propertyKey().equals("pressure")))
                .allSatisfy(requirement -> assertThat(requirement.allowedDataTypes()).containsExactlyInAnyOrder(
                        DashboardComponentDescriptorRegistry.PropertyDataType.NUMBER,
                        DashboardComponentDescriptorRegistry.PropertyDataType.TEXT,
                        DashboardComponentDescriptorRegistry.PropertyDataType.SWITCH,
                        DashboardComponentDescriptorRegistry.PropertyDataType.ENUM));
    }

    /** TABLE DEVICE_VALUES拒绝单设备、不同多设备变量及列顺序错配。 */
    @Test
    @DisplayName("拒绝多设备表格基数和列映射错误")
    void rejectsInvalidDeviceValuesTable() {
        String props = "{\"mode\":\"DEVICE_VALUES\",\"columns\":[{\"id\":\"first\",\"label\":\"一\"},"
                + "{\"id\":\"second\",\"label\":\"二\"}]}";
        assertRejected(component("table", "TABLE", props,
                        "{\"columns\":[{\"id\":\"first\",\"value\":" + current("single", "temperature")
                                + "},{\"id\":\"second\",\"value\":" + current("single", "pressure") + "}]}"),
                DashboardSchemaValidationException.Reason.INVALID_REFERENCE);
        assertRejected(component("table", "TABLE", props,
                        "{\"columns\":[{\"id\":\"first\",\"value\":" + current("multi", "temperature")
                                + "},{\"id\":\"second\",\"value\":" + current("multi_alt", "pressure") + "}]}"),
                DashboardSchemaValidationException.Reason.INVALID_REFERENCE);
        assertRejected(component("table", "TABLE", props,
                        "{\"columns\":[{\"id\":\"second\",\"value\":" + current("multi", "temperature")
                                + "},{\"id\":\"first\",\"value\":" + current("multi", "pressure") + "}]}"),
                DashboardSchemaValidationException.Reason.INVALID_REFERENCE);
        assertRejected(component("table", "TABLE",
                        "{\"mode\":\"DEVICE_VALUES\",\"columns\":[]}", "{\"columns\":[]}"),
                DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
        assertRejected(component("table", "TABLE", tableProps(11), tableBindings(11)),
                DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
    }

    /** 根模型和默认设备必须形成完整外部需求，且内部完成范围不能冒充发布资格。 */
    @Test
    @DisplayName("投影模型引用和默认设备需求")
    void projectsModelAndDefaultDeviceRequirements() {
        String source = schema(component("text", "TEXT", "{}", "{}"))
                .replace("{\"key\":\"single\",\"type\":\"DEVICE_SINGLE\",\"title\":\"单设备\","
                                + "\"modelKey\":\"pump_model\"}",
                        "{\"key\":\"single\",\"type\":\"DEVICE_SINGLE\",\"title\":\"单设备\","
                                + "\"modelKey\":\"pump_model\","
                                + "\"defaultDeviceId\":\"00000000-0000-0000-0000-000000000101\"}")
                .replace("\"modelKey\":\"pump_model\",\"maxItems\":20}",
                        "\"modelKey\":\"pump_model\",\"maxItems\":20,\"defaultDeviceIds\":["
                                + "\"00000000-0000-0000-0000-000000000102\"]}");
        DashboardSchemaValidationResult result = validate(source);

        assertThat(result.scope()).isEqualTo(DashboardSchemaValidationResult.Scope.COMPLETE_INTERNAL_SCHEMA_SEMANTICS);
        assertThat(result.unresolvedRequirements()).anyMatch(ModelReferenceRequirement.class::isInstance);
        assertThat(result.unresolvedRequirements()).filteredOn(DefaultDeviceRequirement.class::isInstance).hasSize(2);
    }

    /** 十种组件可在同一合法Schema中完成内部语义校验并保留全部外部需求。 */
    @Test
    @DisplayName("完整Schema覆盖十种内置组件")
    void validatesCompleteSchemaWithAllComponents() {
        String components = String.join(",",
                at(component("text", "TEXT", "{}", "{}"), 0),
                at(component("image", "IMAGE", "{\"resourceId\":\"empty_state\",\"resourceDigest\":\""
                        + "a".repeat(64) + "\",\"alt\":\"暂无\"}", "{}"), 1),
                at(component("card", "VALUE_CARD", "{}", "{\"value\":" + current("single", "temperature") + "}"), 2),
                at(component("status", "STATUS", "{}", "{\"status\":{\"source\":\"DEVICE_STATUS\","
                        + "\"device\":{\"variableKey\":\"single\"}}}"), 3),
                at(component("gauge", "GAUGE", "{}", "{\"value\":" + current("single", "temperature") + "}"), 4),
                at(component("line", "LINE_CHART", "{\"series\":[{\"id\":\"temperature\",\"label\":\"温度\"}]}",
                        "{\"series\":[{\"id\":\"temperature\",\"value\":" + history("temperature", "AVG") + "}]}"), 5),
                at(component("table", "TABLE", "{\"mode\":\"LIST_VALUE\"}",
                        "{\"value\":" + current("single", "payload") + "}"), 6),
                at(component("json", "JSON_VIEW", "{}", "{\"value\":" + current("single", "payload") + "}"), 7),
                at(component("alarms", "ALARM_LIST", "{}", "{\"alarms\":{\"source\":\"ALARM_LIST\","
                        + "\"devices\":{\"variableKey\":\"multi\"},\"conditionStates\":[\"ACTIVE\"],"
                        + "\"ackStates\":[\"UNACKNOWLEDGED\"],\"severities\":[\"MAJOR\"]}}"), 8),
                at(component("selector", "DEVICE_SELECTOR", "{}", "{\"directory\":{\"source\":\"DEVICE_DIRECTORY\","
                        + "\"variableKey\":\"multi\"}}"), 9));
        DashboardSchemaValidationResult result = validate(schema(components));

        assertThat(result.scope()).isEqualTo(DashboardSchemaValidationResult.Scope.COMPLETE_INTERNAL_SCHEMA_SEMANTICS);
        assertThat(result.unresolvedRequirements()).filteredOn(HostComponentRequirement.class::isInstance).hasSize(10);
        assertThat(result.normalizedRoot().get("pages").get(0).get("components")).hasSize(10);
    }

    /** 构造一个组件的完整Schema及LINE/TABLE所需变量。 */
    private static String schema(String component) {
        return "{\"schemaVersion\":\"tc.dashboard/v1\",\"presentation\":{\"mode\":\"RESPONSIVE_GRID\"},"
                + "\"models\":[{\"key\":\"pump_model\",\"versionId\":\"00000000-0000-0000-0000-000000000001\","
                + "\"digestAlgorithm\":\"PG_JSONB_TEXT_V1_SHA256\",\"digest\":\"" + "a".repeat(64)
                + "\",\"profile\":\"TC_PROPERTY_COMPOSITE_V1\"}],\"variables\":["
                + "{\"key\":\"single\",\"type\":\"DEVICE_SINGLE\",\"title\":\"单设备\",\"modelKey\":\"pump_model\"},"
                + "{\"key\":\"multi\",\"type\":\"DEVICE_MULTI\",\"title\":\"多设备\",\"modelKey\":\"pump_model\",\"maxItems\":20},"
                + "{\"key\":\"multi_alt\",\"type\":\"DEVICE_MULTI\",\"title\":\"另一设备组\",\"modelKey\":\"pump_model\"},"
                + "{\"key\":\"range\",\"type\":\"TIME_RANGE\",\"title\":\"时间\"},"
                + "{\"key\":\"choice\",\"type\":\"TEXT_ENUM\",\"title\":\"选择\","
                + "\"options\":[{\"value\":\"normal\",\"label\":\"正常\"}]}],"
                + "\"pages\":[{\"id\":\"main\",\"title\":\"主页\",\"components\":[" + component + "]}]}";
    }

    /** 构造带共同壳字段的组件。 */
    private static String component(String id, String kind, String props, String bindings) {
        return "{\"id\":\"" + id + "\",\"kind\":\"" + kind + "\",\"componentVersion\":\"1.0.0\","
                + "\"layout\":{\"x\":0,\"y\":0,\"w\":1,\"h\":1},\"props\":" + props
                + ",\"bindings\":" + bindings + "}";
    }

    /** 为完整Schema中的组件设置互不重叠的横坐标。 */
    private static String at(String component, int x) {
        return component.replace("\"x\":0", "\"x\":" + x);
    }

    /** 构造单设备历史序列binding。 */
    private static String history(String propertyKey, String aggregation) {
        return "{\"source\":\"HISTORY_SERIES\",\"device\":{\"variableKey\":\"single\"},"
                + "\"propertyKey\":\"" + propertyKey + "\",\"timeRangeVariableKey\":\"range\","
                + "\"granularity\":\"ONE_MINUTE\",\"aggregation\":\"" + aggregation + "\"}";
    }

    /** 构造当前值binding。 */
    private static String current(String variableKey, String propertyKey) {
        return "{\"source\":\"CURRENT_VALUE\",\"device\":{\"variableKey\":\"" + variableKey
                + "\"},\"propertyKey\":\"" + propertyKey + "\"}";
    }

    /** 构造指定数量的折线props序列，用于精确验证集合边界。 */
    private static String lineProps(int count) {
        return "{\"series\":[" + IntStream.range(0, count)
                .mapToObj(index -> "{\"id\":\"series" + index + "\",\"label\":\"序列" + index + "\"}")
                .collect(Collectors.joining(",")) + "]}";
    }

    /** 构造指定数量的折线binding序列；数量边界必须先于重复身份语义拒绝。 */
    private static String lineBindings(int count) {
        return "{\"series\":[" + IntStream.range(0, count)
                .mapToObj(index -> "{\"id\":\"series" + index + "\",\"value\":"
                        + history("temperature", "AVG") + "}")
                .collect(Collectors.joining(",")) + "]}";
    }

    /** 构造指定数量的DEVICE_VALUES列定义，用于精确验证集合边界。 */
    private static String tableProps(int count) {
        return "{\"mode\":\"DEVICE_VALUES\",\"columns\":[" + IntStream.range(0, count)
                .mapToObj(index -> "{\"id\":\"column" + index + "\",\"label\":\"列" + index + "\"}")
                .collect(Collectors.joining(",")) + "]}";
    }

    /** 构造指定数量的DEVICE_VALUES列binding，用于精确验证集合边界。 */
    private static String tableBindings(int count) {
        return "{\"columns\":[" + IntStream.range(0, count)
                .mapToObj(index -> "{\"id\":\"column" + index + "\",\"value\":"
                        + current("multi", "property" + index) + "}")
                .collect(Collectors.joining(",")) + "]}";
    }

    /** 执行公开原文语义管线。 */
    private DashboardSchemaValidationResult validate(String source) {
        return validator.validateAndNormalize(source.getBytes(StandardCharsets.UTF_8));
    }

    /** 返回规范化后的首个组件。 */
    private static JsonNode firstComponent(DashboardSchemaValidationResult result) {
        return result.normalizedRoot().get("pages").get(0).get("components").get(0);
    }

    /** 断言完整管线以稳定原因拒绝组件。 */
    private void assertRejected(String component, DashboardSchemaValidationException.Reason reason) {
        assertThatThrownBy(() -> validate(schema(component)))
                .isInstanceOfSatisfying(DashboardSchemaValidationException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(reason));
    }
}
