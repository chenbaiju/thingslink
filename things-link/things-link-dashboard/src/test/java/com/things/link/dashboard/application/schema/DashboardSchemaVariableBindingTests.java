package com.things.link.dashboard.application.schema;

import com.things.link.dashboard.infrastructure.schema.JacksonDashboardSchemaParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 四类变量、DeviceReference及六种binding内部合同测试。 */
@DisplayName("看板变量与Binding合同")
class DashboardSchemaVariableBindingTests {
    /** 固定原文解析到语义校验管线。 */
    private final DashboardSchemaValidator validator = DashboardSchemaValidator.using(new JacksonDashboardSchemaParser());
    /** 构造包内Binding规则输入的测试JSON映射器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 四类变量先建立完整索引，六种binding再按真实slot入口使用该索引。 */
    @Test
    @DisplayName("建立四类变量索引并校验六种Binding")
    void validatesAllVariablesAndBindingsWithForwardReferences() throws Exception {
        DashboardSchemaValidationResult result = validate(schema("{}", allVariables()));

        JsonNode variables = result.normalizedRoot().get("variables");
        assertThat(variables.get(0).get("required").asBoolean()).isTrue();
        assertThat(variables.get(1).get("maxItems").asInt()).isEqualTo(20);
        assertThat(variables.get(1).get("defaultDeviceIds")).isEmpty();
        assertThat(variables.get(2).get("defaultPreset").asString()).isEqualTo("LAST_1_HOUR");
        assertThat(variables.get(2).get("allowedPresets"))
                .extracting(JsonNode::asString)
                .containsExactly("LAST_1_HOUR", "LAST_24_HOURS", "LAST_7_DAYS");
        DashboardSchemaVariableCatalog catalog = variableCatalog();
        validateBinding(currentBinding("multi"), catalog);
        validateBinding(historyBinding("single", "range"), catalog);
        validateBinding("{\"source\":\"DEVICE_STATUS\",\"device\":{\"variableKey\":\"single\"}}", catalog);
        validateBinding(alarmBinding(), catalog);
        validateBinding("{\"source\":\"DEVICE_DIRECTORY\",\"variableKey\":\"multi\"}", catalog);
        validateBinding("{\"source\":\"ENUM_TEXT\",\"variableKey\":\"choice\"}", catalog);
    }

    /** 变量集合不得超过20且key必须全文唯一。 */
    @Test
    @DisplayName("拒绝变量数量和key重复")
    void rejectsVariableLimitAndDuplicateKeys() {
        StringBuilder variables = new StringBuilder("[");
        for (int index = 0; index < 21; index++) {
            if (index > 0) variables.append(',');
            variables.append(textEnum("variable_" + index, "value_" + index));
        }
        variables.append(']');
        assertRejected(schema("{}", variables.toString()), DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
        assertRejected(schema("{}", "[" + textEnum("same", "first") + "," + textEnum("same", "second") + "]"),
                DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
    }

    /** variable key不得与model key混用命名空间。 */
    @Test
    @DisplayName("拒绝变量与模型同名")
    void rejectsModelVariableNamespaceCollision() {
        assertRejected(schema("{}", "[" + textEnum("pump_model", "value") + "]"),
                DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
    }

    /** 每个变量判别分支都是封闭对象且必填类型严格匹配。 */
    @Test
    @DisplayName("拒绝变量未知字段和错误类型")
    void rejectsUnknownVariableFieldAndWrongType() {
        String unknown = appendField(textEnum("choice", "value"), "\"extra\":true");
        assertRejected(schema("{}", "[" + unknown + "]"), DashboardSchemaValidationException.Reason.UNKNOWN_FIELD);
        String wrongRequired = textEnum("choice", "value").replace("\"title\":\"枚举\"", "\"title\":\"枚举\",\"required\":1");
        assertRejected(schema("{}", "[" + wrongRequired + "]"), DashboardSchemaValidationException.Reason.TYPE_MISMATCH);
    }

    /** 设备变量必须引用根内已声明model key，不查询或猜测外部模型。 */
    @Test
    @DisplayName("拒绝不存在的内部模型引用")
    void rejectsUnknownModelReference() {
        String variable = "{\"key\":\"device\",\"type\":\"DEVICE_SINGLE\",\"title\":\"设备\",\"modelKey\":\"missing\"}";
        assertRejected(schema("{}", "[" + variable + "]"), DashboardSchemaValidationException.Reason.INVALID_REFERENCE);
    }

    /** 多设备默认值受maxItems约束且UUID不得重复。 */
    @Test
    @DisplayName("拒绝多设备默认值越界和重复")
    void rejectsInvalidMultiDeviceDefaults() {
        String duplicate = "{\"key\":\"devices\",\"type\":\"DEVICE_MULTI\",\"title\":\"设备\","
                + "\"modelKey\":\"pump_model\",\"maxItems\":2,\"defaultDeviceIds\":[\"" + uuid(1) + "\",\"" + uuid(1) + "\"]}";
        assertRejected(schema("{}", "[" + duplicate + "]"), DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
        String overMaximum = duplicate.replace("\"" + uuid(1) + "\",\"" + uuid(1) + "\"",
                "\"" + uuid(1) + "\",\"" + uuid(2) + "\",\"" + uuid(3) + "\"");
        assertRejected(schema("{}", "[" + overMaximum + "]"), DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
    }

    /** TIME_RANGE数组必须非空、不重复且包含默认预设。 */
    @Test
    @DisplayName("拒绝非法时间预设集合")
    void rejectsInvalidTimeRangePresets() {
        String duplicate = "{\"key\":\"range\",\"type\":\"TIME_RANGE\",\"title\":\"时间\","
                + "\"allowedPresets\":[\"LAST_1_HOUR\",\"LAST_1_HOUR\"]}";
        assertRejected(schema("{}", "[" + duplicate + "]"), DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
        String missingDefault = "{\"key\":\"range\",\"type\":\"TIME_RANGE\",\"title\":\"时间\","
                + "\"defaultPreset\":\"LAST_24_HOURS\",\"allowedPresets\":[\"LAST_1_HOUR\"]}";
        assertRejected(schema("{}", "[" + missingDefault + "]"), DashboardSchemaValidationException.Reason.INVALID_REFERENCE);
    }

    /** TEXT_ENUM option value必须唯一且defaultValue必须命中。 */
    @Test
    @DisplayName("拒绝非法文本枚举选项")
    void rejectsInvalidTextEnumOptions() {
        String duplicate = "{\"key\":\"choice\",\"type\":\"TEXT_ENUM\",\"title\":\"枚举\",\"options\":["
                + "{\"value\":\"same\",\"label\":\"一\"},{\"value\":\"same\",\"label\":\"二\"}]}";
        assertRejected(schema("{}", "[" + duplicate + "]"), DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
        String missingDefault = appendField(textEnum("choice", "first"), "\"defaultValue\":\"missing\"");
        assertRejected(schema("{}", "[" + missingDefault + "]"), DashboardSchemaValidationException.Reason.INVALID_REFERENCE);
    }

    /** CURRENT_VALUE引用必须命中设备变量且DeviceReference保持封闭。 */
    @Test
    @DisplayName("拒绝当前值错误变量类型和DeviceReference未知字段")
    void rejectsInvalidCurrentValueDeviceReference() throws Exception {
        String wrongType = currentBinding("range");
        assertBindingRejected(wrongType, DashboardSchemaValidationException.Reason.INVALID_REFERENCE);
        String unknownField = currentBinding("single").replace("\"variableKey\":\"single\"",
                "\"variableKey\":\"single\",\"deviceId\":\"" + uuid(1) + "\"");
        assertBindingRejected(unknownField, DashboardSchemaValidationException.Reason.UNKNOWN_FIELD);
    }

    /** HISTORY_SERIES和DEVICE_STATUS只允许DEVICE_SINGLE，历史还要求TIME_RANGE。 */
    @Test
    @DisplayName("拒绝历史和状态Binding基数错误")
    void rejectsHistoryAndStatusCardinality() throws Exception {
        String historyMulti = historyBinding("multi", "range");
        assertBindingRejected(historyMulti, DashboardSchemaValidationException.Reason.INVALID_REFERENCE);
        String historyWrongRange = historyBinding("single", "choice");
        assertBindingRejected(historyWrongRange, DashboardSchemaValidationException.Reason.INVALID_REFERENCE);
        String statusMulti = "{\"source\":\"DEVICE_STATUS\",\"device\":{\"variableKey\":\"multi\"}}";
        assertBindingRejected(statusMulti, DashboardSchemaValidationException.Reason.INVALID_REFERENCE);
    }

    /** DEVICE_DIRECTORY和ENUM_TEXT必须引用各自允许的变量判别类型。 */
    @Test
    @DisplayName("拒绝目录和枚举文本Binding类型错误")
    void rejectsDirectoryAndEnumTextTypeMismatch() throws Exception {
        String directory = "{\"source\":\"DEVICE_DIRECTORY\",\"variableKey\":\"range\"}";
        assertBindingRejected(directory, DashboardSchemaValidationException.Reason.INVALID_REFERENCE);
        String enumText = "{\"source\":\"ENUM_TEXT\",\"variableKey\":\"single\"}";
        assertBindingRejected(enumText, DashboardSchemaValidationException.Reason.INVALID_REFERENCE);
    }

    /** ALARM_LIST三个数组必须非空、不重复且保持条件与确认枚举正交。 */
    @Test
    @DisplayName("拒绝告警Binding空重复和错域枚举")
    void rejectsInvalidAlarmArrays() throws Exception {
        String valid = alarmBinding();
        assertBindingRejected(valid.replace("[\"ACTIVE\"]", "[]"),
                DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
        assertBindingRejected(valid.replace("[\"UNACKNOWLEDGED\"]", "[\"UNACKNOWLEDGED\",\"UNACKNOWLEDGED\"]"),
                DashboardSchemaValidationException.Reason.INVALID_COLLECTION);
        assertBindingRejected(valid.replace("[\"ACTIVE\"]", "[\"ACKNOWLEDGED\"]"),
                DashboardSchemaValidationException.Reason.INVALID_VALUE);
    }

    /** 六种binding对象均为精确闭集且缺失必填字段不能延迟到slot阶段。 */
    @Test
    @DisplayName("拒绝Binding未知字段和缺失字段")
    void rejectsUnknownAndMissingBindingFields() throws Exception {
        String unknown = appendField(currentBinding("single"), "\"path\":\"nested\"");
        assertBindingRejected(unknown, DashboardSchemaValidationException.Reason.UNKNOWN_FIELD);
        String missing = currentBinding("single").replace(",\"propertyKey\":\"temperature\"", "");
        assertBindingRejected(missing, DashboardSchemaValidationException.Reason.REQUIRED_FIELD_MISSING);
    }

    /** 枚举拒绝消息不得回显攻击者提供的内容。 */
    @Test
    @DisplayName("拒绝消息隐藏非法枚举原文")
    void rejectionMessageHidesUntrustedEnumText() throws Exception {
        String injected = "UNSUPPORTED_SECRET";
        String variable = textEnum("choice", "normal").replace("TEXT_ENUM", injected);
        assertThatThrownBy(() -> validate(schema("{}", "[" + variable + "]")))
                .isInstanceOfSatisfying(DashboardSchemaValidationException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(DashboardSchemaValidationException.Reason.INVALID_VALUE);
                    assertThat(exception.getMessage()).doesNotContain(injected);
                });
        String binding = currentBinding("single").replace("CURRENT_VALUE", injected);
        assertThatThrownBy(() -> validateBinding(binding, variableCatalog()))
                .isInstanceOfSatisfying(DashboardSchemaValidationException.class,
                        exception -> assertThat(exception.getMessage()).doesNotContain(injected));
    }

    /** 内部变量引用和定义使用同一LocalKey语法。 */
    @Test
    @DisplayName("拒绝非法Binding变量键")
    void rejectsInvalidBindingVariableKeySyntax() throws Exception {
        assertBindingRejected(currentBinding("INVALID KEY"), DashboardSchemaValidationException.Reason.INVALID_VALUE);
        assertBindingRejected(historyBinding("single", "INVALID KEY"), DashboardSchemaValidationException.Reason.INVALID_VALUE);
    }

    /** 构造根Schema，并故意把pages写在variables之前以验证前向引用。 */
    private static String schema(String bindings, String variables) {
        return "{\"schemaVersion\":\"tc.dashboard/v1\",\"presentation\":{\"mode\":\"RESPONSIVE_GRID\"},"
                + "\"pages\":[{\"id\":\"main\",\"title\":\"主页\",\"components\":[{\"id\":\"probe\",\"kind\":\"TEXT\","
                + "\"componentVersion\":\"1.0.0\",\"layout\":{\"x\":0,\"y\":0,\"w\":1,\"h\":1},"
                + "\"props\":{},\"bindings\":" + bindings + "}]}],\"variables\":" + variables + ",\"models\":[" + model() + "]}";
    }

    /** 构造根模型引用。 */
    private static String model() {
        return "{\"key\":\"pump_model\",\"versionId\":\"" + uuid(100) + "\","
                + "\"digestAlgorithm\":\"PG_JSONB_TEXT_V1_SHA256\",\"digest\":\"" + "a".repeat(64)
                + "\",\"profile\":\"TC_PROPERTY_COMPOSITE_V1\"}";
    }

    /** 构造全部四类变量。 */
    private static String allVariables() {
        return "[{\"key\":\"single\",\"type\":\"DEVICE_SINGLE\",\"title\":\"单设备\",\"modelKey\":\"pump_model\"},"
                + "{\"key\":\"multi\",\"type\":\"DEVICE_MULTI\",\"title\":\"多设备\",\"modelKey\":\"pump_model\"},"
                + "{\"key\":\"range\",\"type\":\"TIME_RANGE\",\"title\":\"时间\"},"
                + textEnum("choice", "normal") + "]";
    }

    /** 构造TEXT_ENUM变量。 */
    private static String textEnum(String key, String value) {
        return "{\"key\":\"" + key + "\",\"type\":\"TEXT_ENUM\",\"title\":\"枚举\","
                + "\"options\":[{\"value\":\"" + value + "\",\"label\":\"正常\"}]}";
    }

    /** 构造CURRENT_VALUE binding。 */
    private static String currentBinding(String variableKey) {
        return "{\"source\":\"CURRENT_VALUE\",\"device\":{\"variableKey\":\"" + variableKey
                + "\"},\"propertyKey\":\"temperature\"}";
    }

    /** 构造HISTORY_SERIES binding。 */
    private static String historyBinding(String deviceVariable, String timeVariable) {
        return "{\"source\":\"HISTORY_SERIES\",\"device\":{\"variableKey\":\"" + deviceVariable
                + "\"},\"propertyKey\":\"temperature\",\"timeRangeVariableKey\":\"" + timeVariable
                + "\",\"granularity\":\"ONE_MINUTE\",\"aggregation\":\"AVG\"}";
    }

    /** 构造ALARM_LIST binding。 */
    private static String alarmBinding() {
        return "{\"source\":\"ALARM_LIST\",\"devices\":{\"variableKey\":\"multi\"},"
                + "\"conditionStates\":[\"ACTIVE\"],\"ackStates\":[\"UNACKNOWLEDGED\"],\"severities\":[\"MAJOR\"]}";
    }

    /** 在对象末尾追加一个字段而不改写任何嵌套对象。 */
    private static String appendField(String object, String field) {
        return object.substring(0, object.length() - 1) + "," + field + "}";
    }

    /** 构造规范小写UUID。 */
    private static String uuid(int value) {
        return "00000000-0000-0000-0000-" + "%012d".formatted(value);
    }

    /** 通过公开语义管线校验。 */
    private DashboardSchemaValidationResult validate(String source) {
        return validator.validateAndNormalize(source.getBytes(StandardCharsets.UTF_8));
    }

    /** 建立已完整处理全数组的变量索引，避免把文本顺序误当成引用顺序。 */
    private DashboardSchemaVariableCatalog variableCatalog() throws Exception {
        ArrayNode variables = (ArrayNode) objectMapper.readTree(allVariables());
        return DashboardSchemaVariableCatalog.validateAndNormalize(variables, Set.of("pump_model"));
    }

    /** 通过包内规则校验一个已由组件slot定位的binding。 */
    private DashboardSchemaBindingRules.BindingDefinition validateBinding(
            String source, DashboardSchemaVariableCatalog catalog) throws Exception {
        return DashboardSchemaBindingRules.validate((ObjectNode) objectMapper.readTree(source), "$.binding", catalog);
    }

    /** 断言真实slot入口将binding按稳定语义原因拒绝。 */
    private void assertBindingRejected(
            String source, DashboardSchemaValidationException.Reason reason) throws Exception {
        DashboardSchemaVariableCatalog catalog = variableCatalog();
        assertThatThrownBy(() -> validateBinding(source, catalog))
                .isInstanceOfSatisfying(DashboardSchemaValidationException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(reason));
    }

    /** 断言按稳定语义原因拒绝。 */
    private void assertRejected(String source, DashboardSchemaValidationException.Reason reason) {
        assertThatThrownBy(() -> validate(source))
                .isInstanceOfSatisfying(DashboardSchemaValidationException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(reason));
    }
}
