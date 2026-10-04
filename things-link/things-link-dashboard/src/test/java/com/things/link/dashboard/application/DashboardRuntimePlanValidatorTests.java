package com.things.link.dashboard.application;

import com.things.link.dashboard.application.draft.DashboardDraftContractValidator;
import com.things.link.dashboard.application.schema.DefaultDashboardDraftContractValidator;
import com.things.link.dashboard.infrastructure.schema.JacksonDashboardSchemaParser;
import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 已授权Schema统一运行计划只接受声明模型、键、查询类型与变量容量的纯测试。 */
class DashboardRuntimePlanValidatorTests {
    /** 测试JSON构造器。 */ private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 精确模型版本。 */ private static final UUID MODEL = UUID.randomUUID();
    /** 固定模型摘要。 */ private static final String DIGEST = "a".repeat(64);
    /** 被测纯验证器。 */ private final DashboardRuntimePlanValidator validator = new DashboardRuntimePlanValidator();
    /** 真实严格Schema管线，保证计划测试不会使用不可能发布的结构。 */
    private final DashboardDraftContractValidator schemaValidator =
            new DefaultDashboardDraftContractValidator(new JacksonDashboardSchemaParser());

    /** Schema声明的当前键、历史组合、目录和告警精确过滤均可执行。 */
    @Test
    void acceptsOnlyExactDeclaredRuntimeQueries() {
        RuntimeDashboardSchema schema = schema("DEVICE_MULTI", 2);
        List<DashboardRuntimeDeviceRequest> devices = List.of(device("temperature"), device("temperature"));
        validator.requireSnapshot(schema, List.of(model()), devices);
        validator.requireCurrentValues(schema, devices);
        validator.requireCatalog(schema, MODEL);
        validator.requireHistory(schema, MODEL, "temperature",
                Instant.parse("2026-09-07T00:00:00Z"), Instant.parse("2026-09-07T01:00:00Z"),
                "RAW", "AVG", Instant.parse("2026-09-07T01:00:00Z"));
        validator.requireAlarms(schema, devices.stream().map(item ->
                        new DashboardRuntimeDeviceRequest(item.deviceId(), MODEL, List.of())).toList(),
                Set.of("ACTIVE"), Set.of("UNACKNOWLEDGED"), Set.of("MAJOR"));
    }

    /** 未声明键、历史组合和过滤不得借合法模型版本读取任意数据。 */
    @Test
    void rejectsPropertiesAndQueryKindsOutsideSchemaPlan() {
        RuntimeDashboardSchema schema = schema("DEVICE_MULTI", 2);
        assertInvalid(() -> validator.requireCurrentValues(schema, List.of(device("pressure"))));
        assertInvalid(() -> validator.requireHistory(schema, MODEL, "temperature",
                Instant.parse("2026-09-07T00:00:00Z"), Instant.parse("2026-09-07T01:00:00Z"),
                "ONE_MINUTE", "AVG", Instant.parse("2026-09-07T01:00:00Z")));
        assertInvalid(() -> validator.requireAlarms(schema,
                List.of(new DashboardRuntimeDeviceRequest(UUID.randomUUID(), MODEL, List.of())),
                Set.of("CLEARED"), Set.of("UNACKNOWLEDGED"), Set.of("MAJOR")));
    }

    /** DEVICE_SINGLE声明不能因同模型而扩大为20台批量读取。 */
    @Test
    void enforcesDeviceVariableCardinalityPerModel() {
        RuntimeDashboardSchema schema = schema("DEVICE_SINGLE", 1);
        List<DashboardRuntimeDeviceRequest> two = List.of(device("temperature"), device("temperature"));
        assertInvalid(() -> validator.requireSnapshot(schema, List.of(model()), two));
        assertInvalid(() -> validator.requireCurrentValues(schema, two));
        assertInvalid(() -> validator.requireAlarms(schema, two.stream().map(item ->
                        new DashboardRuntimeDeviceRequest(item.deviceId(), MODEL, List.of())).toList(),
                Set.of("ACTIVE"), Set.of("UNACKNOWLEDGED"), Set.of("MAJOR")));
    }

    /** 时间窗口只接受TIME_RANGE实际允许的固定持续时间且to不越过服务端容差。 */
    @Test
    void enforcesDeclaredTimePresetAndServerNow() {
        RuntimeDashboardSchema schema = schema("DEVICE_SINGLE", 1);
        assertInvalid(() -> validator.requireHistory(schema, MODEL, "temperature",
                Instant.parse("2026-09-07T00:00:00Z"), Instant.parse("2026-09-07T02:00:00Z"),
                "RAW", "AVG", Instant.parse("2026-09-07T02:00:00Z")));
        assertInvalid(() -> validator.requireHistory(schema, MODEL, "temperature",
                Instant.parse("2026-09-07T00:00:00Z"), Instant.parse("2026-09-07T01:00:00Z"),
                "RAW", "AVG", Instant.parse("2026-09-07T00:58:59Z")));
    }

    /** 仅声明STATUS的合法Schema允许用空属性键读取设备状态和模型身份。 */
    @Test
    void acceptsStatusOnlySnapshotWithoutPropertyKeys() {
        validator.requireSnapshot(statusSchema(), List.of(model()),
                List.of(new DashboardRuntimeDeviceRequest(UUID.randomUUID(), MODEL, List.of())));
    }

    /** 构造含五类数据声明的规范Schema。 */
    private RuntimeDashboardSchema schema(String variableType, int maxItems) {
        ObjectNode root = JSON.createObjectNode().put("schemaVersion", "tc.dashboard/v1");
        root.putObject("presentation").put("mode", "RESPONSIVE_GRID");
        root.putArray("models").addObject().put("key", "pump").put("versionId", MODEL.toString())
                .put("digestAlgorithm", "PG_JSONB_TEXT_V1_SHA256").put("digest", DIGEST)
                .put("profile", "TC_PROPERTY_COMPOSITE_V1");
        ArrayNode variables = root.putArray("variables");
        ObjectNode variable = variables.addObject().put("key", "devices")
                .put("type", variableType).put("title", "设备").put("modelKey", "pump");
        if ("DEVICE_MULTI".equals(variableType)) variable.put("maxItems", maxItems);
        if ("DEVICE_MULTI".equals(variableType)) {
            variables.addObject().put("key", "single").put("type", "DEVICE_SINGLE")
                    .put("title", "历史设备").put("modelKey", "pump");
        }
        variables.addObject().put("key", "period").put("type", "TIME_RANGE")
                .put("title", "时间").put("defaultPreset", "LAST_1_HOUR")
                .putArray("allowedPresets").add("LAST_1_HOUR");
        ArrayNode components = root.putArray("pages").addObject().put("id", "main").put("title", "主页面")
                .putArray("components");
        if ("DEVICE_MULTI".equals(variableType)) {
            components.add(table("value", "devices"));
        } else {
            ObjectNode valueComponent = component("value", "VALUE_CARD", 0);
            valueComponent.putObject("props");
            valueComponent.putObject("bindings").set("value", current("devices"));
            components.add(valueComponent);
        }
        ObjectNode historyComponent = component("history", "LINE_CHART", 1);
        historyComponent.putObject("props").putArray("series").addObject()
                .put("id", "temperature").put("label", "温度");
        ArrayNode historySeries = JSON.createArrayNode();
        historySeries.addObject().put("id", "temperature").set("value",
                history("DEVICE_MULTI".equals(variableType) ? "single" : "devices"));
        historyComponent.putObject("bindings").set("series", historySeries);
        components.add(historyComponent);
        ObjectNode directoryComponent = component("directory", "DEVICE_SELECTOR", 2);
        directoryComponent.putObject("props");
        directoryComponent.putObject("bindings").set("directory",
                JSON.createObjectNode().put("source", "DEVICE_DIRECTORY").put("variableKey", "devices"));
        components.add(directoryComponent);
        ObjectNode alarmsComponent = component("alarms", "ALARM_LIST", 3);
        alarmsComponent.putObject("props");
        alarmsComponent.putObject("bindings").set("alarms", alarm());
        components.add(alarmsComponent);
        return runtime(root);
    }

    /** 构造只含合法STATUS组件的规范Schema。 */
    private RuntimeDashboardSchema statusSchema() {
        ObjectNode root = JSON.createObjectNode().put("schemaVersion", "tc.dashboard/v1");
        root.putObject("presentation").put("mode", "RESPONSIVE_GRID");
        root.putArray("models").addObject().put("key", "pump").put("versionId", MODEL.toString())
                .put("digestAlgorithm", "PG_JSONB_TEXT_V1_SHA256").put("digest", DIGEST)
                .put("profile", "TC_PROPERTY_COMPOSITE_V1");
        root.putArray("variables").addObject().put("key", "devices").put("type", "DEVICE_SINGLE")
                .put("title", "状态设备").put("modelKey", "pump");
        ArrayNode components = root.putArray("pages").addObject().put("id", "main").put("title", "主页面")
                .putArray("components");
        ObjectNode status = component("status", "STATUS", 0);
        status.putObject("props");
        status.putObject("bindings").putObject("status").put("source", "DEVICE_STATUS")
                .putObject("device").put("variableKey", "devices");
        components.add(status);
        return runtime(root);
    }

    /** 通过真实严格Schema管线规范化测试输入，再建立已授权运行值。 */
    private RuntimeDashboardSchema runtime(ObjectNode source) {
        var normalized = schemaValidator.validate("0", source.toString().getBytes(StandardCharsets.UTF_8)).content();
        int schemaBytes = normalized.toString().getBytes(StandardCharsets.UTF_8).length;
        return new RuntimeDashboardSchema(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "app_" + "b".repeat(32), UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(), 1,
                "tc.dashboard/v1", "PG_JSONB_TEXT_V1_SHA256", "c".repeat(64), List.of(), List.of(),
                normalized, schemaBytes);
    }

    /** 构造带合法响应式布局的组件壳。 */
    private static ObjectNode component(String id, String kind, int x) {
        ObjectNode component = JSON.createObjectNode().put("id", id).put("kind", kind)
                .put("componentVersion", "1.0.0");
        component.putObject("layout").put("x", x).put("y", 0).put("w", 1).put("h", 1);
        return component;
    }

    /** 构造允许多设备当前值的DEVICE_VALUES表格。 */
    private static ObjectNode table(String id, String variableKey) {
        ObjectNode table = component(id, "TABLE", 0);
        table.putObject("props").put("mode", "DEVICE_VALUES").putArray("columns")
                .addObject().put("id", "temperature").put("label", "温度");
        ObjectNode value = table.putObject("bindings").putArray("columns").addObject()
                .put("id", "temperature").putObject("value");
        value.setAll(current(variableKey));
        return table;
    }

    /** 构造当前值binding。 */
    private static ObjectNode current(String variableKey) {
        return JSON.createObjectNode().put("source", "CURRENT_VALUE")
                .set("device", JSON.createObjectNode().put("variableKey", variableKey))
                .put("propertyKey", "temperature");
    }

    /** 构造历史binding。 */
    private static ObjectNode history(String variableKey) {
        return JSON.createObjectNode().put("source", "HISTORY_SERIES")
                .set("device", JSON.createObjectNode().put("variableKey", variableKey))
                .put("propertyKey", "temperature").put("timeRangeVariableKey", "period")
                .put("granularity", "RAW").put("aggregation", "AVG");
    }

    /** 构造告警binding。 */
    private static ObjectNode alarm() {
        ObjectNode alarm = JSON.createObjectNode().put("source", "ALARM_LIST")
                .set("devices", JSON.createObjectNode().put("variableKey", "devices"));
        alarm.putArray("conditionStates").add("ACTIVE");
        alarm.putArray("ackStates").add("UNACKNOWLEDGED");
        alarm.putArray("severities").add("MAJOR");
        return alarm;
    }

    /** 构造模型请求。 */
    private static DashboardRuntimeModelRequest model() {
        return new DashboardRuntimeModelRequest(MODEL, "PG_JSONB_TEXT_V1_SHA256", DIGEST,
                "TC_PROPERTY_COMPOSITE_V1");
    }

    /** 构造不同设备的当前属性请求。 */
    private static DashboardRuntimeDeviceRequest device(String key) {
        return new DashboardRuntimeDeviceRequest(UUID.randomUUID(), MODEL, List.of(key));
    }

    /** 断言公开参数错误。 */
    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode().code()).isEqualTo(10001);
    }
}
