package com.things.link.dashboard.application.schema;

import com.things.link.dashboard.infrastructure.schema.JacksonDashboardSchemaParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 十种内置组件描述符及组件语义测试。 */
@DisplayName("看板内置组件描述符与完整组件语义")
class DashboardComponentSemanticRulesTests {
    /** 原文解析到完整内部语义范围的固定管线。 */
    private final DashboardSchemaValidator validator = DashboardSchemaValidator.using(new JacksonDashboardSchemaParser());

    /** 十种描述符必须唯一、版本精确并共同声明两画布和保守宿主范围。 */
    @Test
    @DisplayName("注册十种唯一组件描述符")
    void registersTenUniqueComponentDescriptors() {
        List<DashboardComponentDescriptorRegistry.ComponentDescriptor> descriptors =
                DashboardComponentDescriptorRegistry.descriptors().stream()
                        .filter(d -> d.componentVersion().equals("1.0.0")).toList();

        assertThat(descriptors).hasSize(10);
        assertThat(descriptors).extracting(descriptor -> descriptor.kind().name()).doesNotHaveDuplicates()
                .containsExactlyInAnyOrder(
                        "TEXT", "IMAGE", "VALUE_CARD", "STATUS", "GAUGE", "LINE_CHART",
                        "TABLE", "JSON_VIEW", "ALARM_LIST", "DEVICE_SELECTOR");
        assertThat(descriptors).allSatisfy(descriptor -> {
            assertThat(descriptor.componentVersion()).isEqualTo("1.0.0");
            assertThat(descriptor.supportedCanvasModes()).containsExactlyInAnyOrder(
                    DashboardComponentDescriptorRegistry.CanvasMode.RESPONSIVE_GRID,
                    DashboardComponentDescriptorRegistry.CanvasMode.FIXED_SCREEN);
            assertThat(descriptor.hostCompatibility().minInclusive()).isEqualTo("1.0.0");
            assertThat(descriptor.hostCompatibility().maxExclusive()).isEqualTo("1.0.1");
            assertThat(descriptor.hostCompatibility().contains("1.0.0")).isTrue();
            assertThat(descriptor.hostCompatibility().contains("1.0.1")).isFalse();
            assertThat(descriptor.hostCompatibility().contains("1.0.10")).isFalse();
            assertThat(descriptor.hostCompatibility().contains("01.0.0")).isFalse();
            assertThat(descriptor.hostCompatibility().contains("١.٠.٠")).isFalse();
            assertThat(descriptor.hostCompatibility().contains("１.０.０")).isFalse();
        });
    }

    /** 新补丁只扩展登记身份，不修改既有描述符或假定历史宿主能运行新组件。 */
    @Test
    void patchDescriptorsKeepOldSemanticsAndUseOnlyQualifiedHostRange() {
        assertThat(DashboardComponentDescriptorRegistry.descriptors()).hasSize(20);
        for (var kind : DashboardComponentDescriptorRegistry.ComponentKind.values()) {
            var old = DashboardComponentDescriptorRegistry.require(kind.name(), "1.0.0", "$");
            var patch = DashboardComponentDescriptorRegistry.require(kind.name(), "1.0.1", "$");
            assertThat(patch.props()).isEqualTo(old.props());
            assertThat(patch.slots()).isEqualTo(old.slots());
            assertThat(patch.supportedDataTypes()).isEqualTo(old.supportedDataTypes());
            assertThat(patch.supportedCanvasModes()).isEqualTo(old.supportedCanvasModes());
            assertThat(old.hostCompatibility().contains("1.1.0")).isFalse();
            assertThat(patch.hostCompatibility().contains("1.0.0")).isFalse();
            assertThat(patch.hostCompatibility().contains("1.1.0")).isTrue();
            assertThat(patch.hostCompatibility().contains("1.1.1")).isTrue();
            assertThat(patch.hostCompatibility().contains("1.1.2")).isFalse();
        }
    }

    /** HostRange必须数字比较且保持严格半开区间。 */
    @Test
    @DisplayName("拒绝无效和反向宿主范围")
    void rejectsInvalidHostRanges() {
        assertThatThrownBy(() -> new DashboardComponentDescriptorRegistry.HostRange("1.0.1", "1.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DashboardComponentDescriptorRegistry.HostRange("1.0.10", "1.0.2"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DashboardComponentDescriptorRegistry.HostRange("01.0.0", "1.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 描述符必须明确区分属性dataType集合和完全不适用。 */
    @Test
    @DisplayName("区分组件dataType支持与不适用")
    void distinguishesPropertyDataTypesFromNotApplicable() {
        DashboardComponentDescriptorRegistry.ComponentDescriptor valueCard = descriptor("VALUE_CARD");
        DashboardComponentDescriptorRegistry.ComponentDescriptor status = descriptor("STATUS");

        assertThat(valueCard.supportedDataTypes().applicability())
                .isEqualTo(DashboardComponentDescriptorRegistry.DataTypeApplicability.PROPERTY_TYPED);
        assertThat(valueCard.supportedDataTypes().allowedTypes()).containsExactlyInAnyOrder(
                DashboardComponentDescriptorRegistry.PropertyDataType.NUMBER,
                DashboardComponentDescriptorRegistry.PropertyDataType.TEXT,
                DashboardComponentDescriptorRegistry.PropertyDataType.SWITCH,
                DashboardComponentDescriptorRegistry.PropertyDataType.ENUM);
        assertThat(status.supportedDataTypes().applicability())
                .isEqualTo(DashboardComponentDescriptorRegistry.DataTypeApplicability.NOT_APPLICABLE);
        assertThat(status.supportedDataTypes().allowedTypes()).isEmpty();
    }

    /** 注册表机器元数据必须逐项匹配十组件完整冻结矩阵。 */
    @Test
    @DisplayName("描述符逐项匹配完整冻结矩阵")
    void descriptorsMatchCompleteFrozenMatrix() {
        assertThat(DashboardComponentDescriptorRegistry.descriptors().stream()
                .filter(d -> d.componentVersion().equals("1.0.0")).toList())
                .extracting(DashboardComponentSemanticRulesTests::descriptorSignature)
                .containsExactlyInAnyOrder(
                        "TEXT|align:TEXT_ALIGN:false:[LEFT];content:TEXT_CONTENT:false:[];size:TEXT_SIZE:false:[MEDIUM];tone:TEXT_TONE:false:[REGULAR]|text:BINDING:false:ENUM_TEXT|NOT_APPLICABLE:|FIXED_SCREEN,RESPONSIVE_GRID|1.0.0/1.0.1",
                        "IMAGE|alt:SHORT_TEXT:true:-;fit:IMAGE_FIT:false:[CONTAIN];resourceDigest:SHA256:true:-;resourceId:LOCAL_KEY:true:-;title:TITLE:false:-||NOT_APPLICABLE:|FIXED_SCREEN,RESPONSIVE_GRID|1.0.0/1.0.1",
                        "VALUE_CARD|precision:PRECISION:false:[2];title:TITLE:false:-;unitMode:UNIT_MODE:false:[MODEL]|value:BINDING:true:CURRENT_VALUE|PROPERTY_TYPED:ENUM,NUMBER,SWITCH,TEXT|FIXED_SCREEN,RESPONSIVE_GRID|1.0.0/1.0.1",
                        "STATUS|showLastOnlineAt:BOOLEAN:false:[true];title:TITLE:false:-|status:BINDING:true:DEVICE_STATUS|NOT_APPLICABLE:|FIXED_SCREEN,RESPONSIVE_GRID|1.0.0/1.0.1",
                        "GAUGE|max:CONFIG_NUMBER:false:-;min:CONFIG_NUMBER:false:-;precision:PRECISION:false:[2];scaleMode:GAUGE_SCALE_MODE:false:[MODEL];title:TITLE:false:-;unitMode:UNIT_MODE:false:[MODEL]|value:BINDING:true:CURRENT_VALUE|PROPERTY_TYPED:NUMBER|FIXED_SCREEN,RESPONSIVE_GRID|1.0.0/1.0.1",
                        "LINE_CHART|series:SERIES_DEFINITIONS:true:-;showLegend:BOOLEAN:false:[true];title:TITLE:false:-|series:BINDING_ARRAY:true:HISTORY_SERIES|PROPERTY_TYPED:NUMBER|FIXED_SCREEN,RESPONSIVE_GRID|1.0.0/1.0.1",
                        "TABLE|columns:COLUMN_DEFINITIONS:false:-;mode:TABLE_MODE:true:-;rowLimit:ROW_LIMIT:false:[20];title:TITLE:false:-|columns:BINDING_ARRAY:false:CURRENT_VALUE;value:BINDING:false:CURRENT_VALUE|PROPERTY_TYPED:ENUM,LIST,NUMBER,SWITCH,TEXT|FIXED_SCREEN,RESPONSIVE_GRID|1.0.0/1.0.1",
                        "JSON_VIEW|initialExpandDepth:EXPAND_DEPTH:false:[1];title:TITLE:false:-|value:BINDING:true:CURRENT_VALUE|PROPERTY_TYPED:LIST,OBJECT|FIXED_SCREEN,RESPONSIVE_GRID|1.0.0/1.0.1",
                        "ALARM_LIST|pageSize:PAGE_SIZE:false:[20];showClearedAt:BOOLEAN:false:[true];title:TITLE:false:-|alarms:BINDING:true:ALARM_LIST|NOT_APPLICABLE:|FIXED_SCREEN,RESPONSIVE_GRID|1.0.0/1.0.1",
                        "DEVICE_SELECTOR|pageSize:PAGE_SIZE:false:[20];placeholder:SHORT_TEXT:false:[请选择设备];title:TITLE:false:-|directory:BINDING:true:DEVICE_DIRECTORY|NOT_APPLICABLE:|FIXED_SCREEN,RESPONSIVE_GRID|1.0.0/1.0.1");
        assertThatThrownBy(() -> descriptor("TEXT").props().put("extra", descriptor("TEXT").props().get("content")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> descriptor("TEXT").supportedCanvasModes()
                .add(DashboardComponentDescriptorRegistry.CanvasMode.RESPONSIVE_GRID))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** 静态TEXT注入唯一默认值，动态TEXT只保留枚举slot且不注入content。 */
    @Test
    @DisplayName("规范化静态和动态TEXT")
    void normalizesStaticAndDynamicText() {
        DashboardSchemaValidationResult staticResult = validate(schema(component("text", "TEXT", "{}", "{}")));
        var patched = validate(schema(component("text", "TEXT", "{}", "{}")).replace("1.0.0", "1.0.1"));
        assertThat(component(patched).get("props")).isEqualTo(component(staticResult).get("props"));
        assertThat(patched.unresolvedRequirements()).anySatisfy(requirement -> {
            assertThat(requirement).isInstanceOf(HostComponentRequirement.class);
            var host = (HostComponentRequirement) requirement;
            assertThat(host.componentVersion()).isEqualTo("1.0.1");
            assertThat(host.hostCompatibility().contains("1.1.1")).isTrue();
        });
        JsonNode staticProps = component(staticResult).get("props");
        assertThat(staticProps.get("content").asString()).isEmpty();
        assertThat(staticProps.get("align").asString()).isEqualTo("LEFT");
        assertThat(staticProps.get("size").asString()).isEqualTo("MEDIUM");
        assertThat(staticProps.get("tone").asString()).isEqualTo("REGULAR");
        assertThat(staticResult.unresolvedRequirements()).anyMatch(HostComponentRequirement.class::isInstance);
        assertThat(staticResult.unresolvedRequirements()).anyMatch(ModelReferenceRequirement.class::isInstance);

        DashboardSchemaValidationResult dynamicResult = validate(schema(component(
                "text", "TEXT", "{\"align\":\"CENTER\"}",
                "{\"text\":{\"source\":\"ENUM_TEXT\",\"variableKey\":\"choice\"}}")));
        JsonNode dynamicProps = component(dynamicResult).get("props");
        assertThat(dynamicProps.has("content")).isFalse();
        assertThat(dynamicProps.get("align").asString()).isEqualTo("CENTER");
    }

    /** TEXT拒绝title、双内容来源和错误binding source。 */
    @Test
    @DisplayName("拒绝TEXT未知字段和双内容来源")
    void rejectsInvalidTextSources() {
        assertRejected(component("text", "TEXT", "{\"title\":\"非法\"}", "{}"),
                DashboardSchemaValidationException.Reason.UNKNOWN_FIELD);
        assertRejected(component("text", "TEXT", "{\"content\":\"静态\"}",
                        "{\"text\":{\"source\":\"ENUM_TEXT\",\"variableKey\":\"choice\"}}"),
                DashboardSchemaValidationException.Reason.INVALID_VALUE);
        assertRejected(component("text", "TEXT", "{}",
                        "{\"text\":{\"source\":\"DEVICE_STATUS\",\"device\":{\"variableKey\":\"single\"}}}"),
                DashboardSchemaValidationException.Reason.INVALID_REFERENCE);
    }

    /** IMAGE校验内置资源引用并将真实资源存在性保留为未决需求。 */
    @Test
    @DisplayName("IMAGE产生内置资源未决需求")
    void imageProducesBuiltinResourceRequirement() {
        String props = "{\"resourceId\":\"empty_state\",\"resourceDigest\":\"" + "a".repeat(64)
                + "\",\"alt\":\"暂无数据\"}";
        DashboardSchemaValidationResult result = validate(schema(component("image", "IMAGE", props, "{}")));

        assertThat(component(result).get("props").get("fit").asString()).isEqualTo("CONTAIN");
        assertThat(result.unresolvedRequirements()).hasSize(3);
        assertThat(result.unresolvedRequirements()).anySatisfy(requirement -> assertThat(requirement)
                .isEqualTo(new BuiltinResourceRequirement("empty_state", "a".repeat(64))));
        assertThat(result.unresolvedRequirements()).anyMatch(HostComponentRequirement.class::isInstance);
    }

    /** IMAGE不接受URL、数据binding或错误摘要，避免把外部资源伪装为内置资源。 */
    @Test
    @DisplayName("拒绝IMAGE外部资源字段和binding")
    void rejectsInvalidImageShape() {
        String required = "\"resourceId\":\"empty_state\",\"resourceDigest\":\"" + "a".repeat(64)
                + "\",\"alt\":\"\"";
        assertRejected(component("image", "IMAGE", "{" + required + ",\"url\":\"https://example.invalid\"}", "{}"),
                DashboardSchemaValidationException.Reason.UNKNOWN_FIELD);
        assertRejected(component("image", "IMAGE", "{" + required + "}",
                        "{\"value\":{\"source\":\"CURRENT_VALUE\",\"device\":{\"variableKey\":\"single\"},"
                                + "\"propertyKey\":\"temperature\"}}"),
                DashboardSchemaValidationException.Reason.UNKNOWN_FIELD);
    }

    /** VALUE_CARD注入显示默认值并保留属性与适配器外部需求。 */
    @Test
    @DisplayName("VALUE_CARD接入单设备当前值")
    void valueCardProducesPropertyAndAdapterRequirements() {
        String binding = "{\"value\":" + currentValue("single") + "}";
        DashboardSchemaValidationResult result = validate(schema(component("value", "VALUE_CARD", "{}", binding)));

        JsonNode props = component(result).get("props");
        assertThat(props.get("precision").asInt()).isEqualTo(2);
        assertThat(props.get("unitMode").asString()).isEqualTo("MODEL");
        assertThat(result.unresolvedRequirements()).anySatisfy(requirement -> {
            assertThat(requirement).isInstanceOf(ModelPropertyRequirement.class);
            ModelPropertyRequirement property = (ModelPropertyRequirement) requirement;
            assertThat(property.modelKey()).isEqualTo("pump_model");
            assertThat(property.propertyKey()).isEqualTo("temperature");
            assertThat(property.allowedDataTypes()).containsExactlyInAnyOrder(
                    DashboardComponentDescriptorRegistry.PropertyDataType.NUMBER,
                    DashboardComponentDescriptorRegistry.PropertyDataType.TEXT,
                    DashboardComponentDescriptorRegistry.PropertyDataType.SWITCH,
                    DashboardComponentDescriptorRegistry.PropertyDataType.ENUM);
        });
        assertThat(result.unresolvedRequirements()).anyMatch(DataAdapterRequirement.class::isInstance);
    }

    /** VALUE_CARD严格限制precision、unitMode、slot source和单设备基数。 */
    @Test
    @DisplayName("拒绝VALUE_CARD错误类型和变量基数")
    void rejectsInvalidValueCardSemantics() {
        assertRejected(component("value", "VALUE_CARD", "{\"precision\":7}",
                        "{\"value\":" + currentValue("single") + "}"),
                DashboardSchemaValidationException.Reason.INVALID_VALUE);
        assertRejected(component("value", "VALUE_CARD", "{\"unitMode\":\"AUTO\"}",
                        "{\"value\":" + currentValue("single") + "}"),
                DashboardSchemaValidationException.Reason.INVALID_VALUE);
        assertRejected(component("value", "VALUE_CARD", "{}",
                        "{\"value\":" + currentValue("multi") + "}"),
                DashboardSchemaValidationException.Reason.INVALID_REFERENCE);
    }

    /** STATUS只接单设备状态且不把服务端适配器存在性当作已完成。 */
    @Test
    @DisplayName("STATUS注入默认并产生适配器需求")
    void statusProducesAdapterRequirement() {
        String binding = "{\"status\":{\"source\":\"DEVICE_STATUS\","
                + "\"device\":{\"variableKey\":\"single\"}}}";
        DashboardSchemaValidationResult result = validate(schema(component("status", "STATUS", "{}", binding)));

        assertThat(component(result).get("props").get("showLastOnlineAt").asBoolean()).isTrue();
        assertThat(result.unresolvedRequirements()).anySatisfy(requirement -> {
            assertThat(requirement).isInstanceOf(DataAdapterRequirement.class);
            assertThat(((DataAdapterRequirement) requirement).source())
                    .isEqualTo(DashboardSchemaBindingRules.BindingSource.DEVICE_STATUS);
        });
        assertThat(result.unresolvedRequirements()).noneMatch(ModelPropertyRequirement.class::isInstance);
    }

    /** GAUGE的MODEL量程保留模型范围需求，EXPLICIT量程在纯规则内完成数值验证。 */
    @Test
    @DisplayName("校验GAUGE模型和显式量程")
    void validatesGaugeScaleModesAndRequirements() {
        String bindings = "{\"value\":" + currentValue("single") + "}";
        DashboardSchemaValidationResult modelResult = validate(schema(component("gauge", "GAUGE", "{}", bindings)));
        JsonNode modelProps = component(modelResult).get("props");
        assertThat(modelProps.get("scaleMode").asString()).isEqualTo("MODEL");
        assertThat(modelProps.get("precision").asInt()).isEqualTo(2);
        assertThat(modelProps.get("unitMode").asString()).isEqualTo("MODEL");
        assertThat(modelResult.unresolvedRequirements()).anySatisfy(requirement -> assertThat(requirement)
                .isEqualTo(new ModelGaugeRangeRequirement("pump_model", "temperature")));
        assertThat(modelResult.unresolvedRequirements()).anySatisfy(requirement -> {
            assertThat(requirement).isInstanceOf(ModelPropertyRequirement.class);
            assertThat(((ModelPropertyRequirement) requirement).allowedDataTypes())
                    .containsExactly(DashboardComponentDescriptorRegistry.PropertyDataType.NUMBER);
        });

        DashboardSchemaValidationResult explicitResult = validate(schema(component(
                "gauge", "GAUGE", "{\"scaleMode\":\"EXPLICIT\",\"min\":-1.5,\"max\":2}", bindings)));
        assertThat(explicitResult.unresolvedRequirements())
                .noneMatch(ModelGaugeRangeRequirement.class::isInstance);
    }

    /** GAUGE拒绝MODEL携带边界、EXPLICIT缺边界、反向范围和多设备变量。 */
    @Test
    @DisplayName("拒绝非法GAUGE量程和基数")
    void rejectsInvalidGaugeRangeAndCardinality() {
        String single = "{\"value\":" + currentValue("single") + "}";
        assertRejected(component("gauge", "GAUGE", "{\"min\":0}", single),
                DashboardSchemaValidationException.Reason.INVALID_VALUE);
        assertRejected(component("gauge", "GAUGE", "{\"scaleMode\":\"EXPLICIT\",\"min\":0}", single),
                DashboardSchemaValidationException.Reason.REQUIRED_FIELD_MISSING);
        assertRejected(component("gauge", "GAUGE", "{\"scaleMode\":\"EXPLICIT\",\"min\":2,\"max\":2}", single),
                DashboardSchemaValidationException.Reason.INVALID_VALUE);
        assertRejected(component("gauge", "GAUGE", "{}", "{\"value\":" + currentValue("multi") + "}"),
                DashboardSchemaValidationException.Reason.INVALID_REFERENCE);
    }

    /** JSON_VIEW仅声明OBJECT/LIST属性需求并要求完整复合快照适配器。 */
    @Test
    @DisplayName("JSON_VIEW产生复合快照需求")
    void jsonViewProducesCompositeSnapshotRequirement() {
        DashboardSchemaValidationResult result = validate(schema(component(
                "json", "JSON_VIEW", "{}", "{\"value\":" + currentValue("single") + "}")));

        assertThat(component(result).get("props").get("initialExpandDepth").asInt()).isEqualTo(1);
        assertThat(result.unresolvedRequirements()).anySatisfy(requirement -> {
            assertThat(requirement).isInstanceOf(ModelPropertyRequirement.class);
            assertThat(((ModelPropertyRequirement) requirement).allowedDataTypes()).containsExactlyInAnyOrder(
                    DashboardComponentDescriptorRegistry.PropertyDataType.OBJECT,
                    DashboardComponentDescriptorRegistry.PropertyDataType.LIST);
        });
        assertThat(result.unresolvedRequirements()).anySatisfy(requirement -> {
            assertThat(requirement).isInstanceOf(DataAdapterRequirement.class);
            assertThat(((DataAdapterRequirement) requirement).capability())
                    .isEqualTo(AdapterCapability.COMPOSITE_SNAPSHOT);
        });
    }

    /** JSON_VIEW拒绝越界展开深度和多设备CURRENT_VALUE。 */
    @Test
    @DisplayName("拒绝JSON_VIEW越界和多设备")
    void rejectsInvalidJsonViewSemantics() {
        assertRejected(component("json", "JSON_VIEW", "{\"initialExpandDepth\":3}",
                        "{\"value\":" + currentValue("single") + "}"),
                DashboardSchemaValidationException.Reason.INVALID_VALUE);
        assertRejected(component("json", "JSON_VIEW", "{}",
                        "{\"value\":" + currentValue("multi") + "}"),
                DashboardSchemaValidationException.Reason.INVALID_REFERENCE);
    }

    /** ALARM_LIST和DEVICE_SELECTOR注入分页默认并保留单/多设备基数的有界适配需求。 */
    @Test
    @DisplayName("告警列表和设备选择器产生有界适配需求")
    void alarmListAndSelectorProduceBoundedAdapterRequirements() {
        DashboardSchemaValidationResult alarmResult = validate(schema(component(
                "alarms", "ALARM_LIST", "{}", "{\"alarms\":" + alarmList("multi") + "}")));
        assertThat(component(alarmResult).get("props").get("pageSize").asInt()).isEqualTo(20);
        assertThat(component(alarmResult).get("props").get("showClearedAt").asBoolean()).isTrue();
        assertThat(adapter(alarmResult).capability()).isEqualTo(AdapterCapability.BOUNDED_ALARM_PAGE);
        assertThat(adapter(alarmResult).variableType())
                .isEqualTo(DashboardSchemaVariableCatalog.VariableType.DEVICE_MULTI);

        DashboardSchemaValidationResult selectorResult = validate(schema(component(
                "selector", "DEVICE_SELECTOR", "{}",
                "{\"directory\":{\"source\":\"DEVICE_DIRECTORY\",\"variableKey\":\"single\"}}")));
        assertThat(component(selectorResult).get("props").get("placeholder").asString()).isEqualTo("请选择设备");
        assertThat(component(selectorResult).get("props").get("pageSize").asInt()).isEqualTo(20);
        assertThat(adapter(selectorResult).capability())
                .isEqualTo(AdapterCapability.BOUNDED_DEVICE_DIRECTORY_PAGE);
        assertThat(adapter(selectorResult).variableType())
                .isEqualTo(DashboardSchemaVariableCatalog.VariableType.DEVICE_SINGLE);
    }

    /** ALARM_LIST和DEVICE_SELECTOR拒绝分页越界、错误slot及未声明props。 */
    @Test
    @DisplayName("拒绝告警列表和设备选择器非法结构")
    void rejectsInvalidAlarmListAndSelectorSemantics() {
        assertRejected(component("alarms", "ALARM_LIST", "{\"pageSize\":0}",
                        "{\"alarms\":" + alarmList("single") + "}"),
                DashboardSchemaValidationException.Reason.INVALID_VALUE);
        assertRejected(component("alarms", "ALARM_LIST", "{}",
                        "{\"alarms\":{\"source\":\"DEVICE_DIRECTORY\",\"variableKey\":\"single\"}}"),
                DashboardSchemaValidationException.Reason.INVALID_REFERENCE);
        assertRejected(component("selector", "DEVICE_SELECTOR", "{\"pageSize\":51}",
                        "{\"directory\":{\"source\":\"DEVICE_DIRECTORY\",\"variableKey\":\"single\"}}"),
                DashboardSchemaValidationException.Reason.INVALID_VALUE);
        assertRejected(component("selector", "DEVICE_SELECTOR", "{\"maxItems\":2}",
                        "{\"directory\":{\"source\":\"DEVICE_DIRECTORY\",\"variableKey\":\"multi\"}}"),
                DashboardSchemaValidationException.Reason.UNKNOWN_FIELD);
    }

    /** 完成范围必须限定为内部十组件语义，未决需求不得被吞掉或冒充发布资格。 */
    @Test
    @DisplayName("管线报告完整内部范围和未决事实")
    void pipelineReportsInternalScopeAndUnresolvedFacts() {
        DashboardSchemaValidationResult result = validate(schema(component(
                "value", "VALUE_CARD", "{}", "{\"value\":" + currentValue("single") + "}")));

        assertThat(result.scope()).isEqualTo(DashboardSchemaValidationResult.Scope
                .COMPLETE_INTERNAL_SCHEMA_SEMANTICS);
        assertThat(result.unresolvedRequirements()).isNotEmpty();
    }

    /** 按kind读取注册表中的精确1.0.0描述符。 */
    private static DashboardComponentDescriptorRegistry.ComponentDescriptor descriptor(String kind) {
        return DashboardComponentDescriptorRegistry.require(kind, "1.0.0", "$.component");
    }

    /** 把描述符全部机器字段投影成稳定矩阵行，防止仅校验字段名造成假绿。 */
    private static String descriptorSignature(
            DashboardComponentDescriptorRegistry.ComponentDescriptor descriptor) {
        String props = descriptor.props().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + ":" + entry.getValue().type() + ":" + entry.getValue().required()
                        + ":" + entry.getValue().defaultLiteral().map(value -> "[" + value + "]").orElse("-"))
                .collect(Collectors.joining(";"));
        String slots = descriptor.slots().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + ":" + entry.getValue().type() + ":" + entry.getValue().required()
                        + ":" + enumNames(entry.getValue().acceptedSources()))
                .collect(Collectors.joining(";"));
        return descriptor.kind() + "|" + props + "|" + slots + "|"
                + descriptor.supportedDataTypes().applicability() + ":"
                + enumNames(descriptor.supportedDataTypes().allowedTypes()) + "|"
                + enumNames(descriptor.supportedCanvasModes()) + "|"
                + descriptor.hostCompatibility().minInclusive() + "/"
                + descriptor.hostCompatibility().maxExclusive();
    }

    /** 把枚举集合按名字排序，避免不可变集合迭代顺序影响矩阵断言。 */
    private static String enumNames(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    /** 构造完整Schema并提供单、多设备及文本枚举变量。 */
    private static String schema(String component) {
        return "{\"schemaVersion\":\"tc.dashboard/v1\",\"presentation\":{\"mode\":\"RESPONSIVE_GRID\"},"
                + "\"models\":[" + model() + "],\"variables\":["
                + "{\"key\":\"single\",\"type\":\"DEVICE_SINGLE\",\"title\":\"单设备\",\"modelKey\":\"pump_model\"},"
                + "{\"key\":\"multi\",\"type\":\"DEVICE_MULTI\",\"title\":\"多设备\",\"modelKey\":\"pump_model\"},"
                + "{\"key\":\"choice\",\"type\":\"TEXT_ENUM\",\"title\":\"枚举\","
                + "\"options\":[{\"value\":\"normal\",\"label\":\"正常\"}]}],"
                + "\"pages\":[{\"id\":\"main\",\"title\":\"主页\",\"components\":[" + component + "]}]}";
    }

    /** 构造根模型引用。 */
    private static String model() {
        return "{\"key\":\"pump_model\",\"versionId\":\"00000000-0000-0000-0000-000000000001\","
                + "\"digestAlgorithm\":\"PG_JSONB_TEXT_V1_SHA256\",\"digest\":\"" + "a".repeat(64)
                + "\",\"profile\":\"TC_PROPERTY_COMPOSITE_V1\"}";
    }

    /** 构造带共同壳字段的组件。 */
    private static String component(String id, String kind, String props, String bindings) {
        return "{\"id\":\"" + id + "\",\"kind\":\"" + kind + "\",\"componentVersion\":\"1.0.0\","
                + "\"layout\":{\"x\":0,\"y\":0,\"w\":1,\"h\":1},\"props\":" + props
                + ",\"bindings\":" + bindings + "}";
    }

    /** 构造当前值binding。 */
    private static String currentValue(String variableKey) {
        return "{\"source\":\"CURRENT_VALUE\",\"device\":{\"variableKey\":\"" + variableKey
                + "\"},\"propertyKey\":\"temperature\"}";
    }

    /** 构造带冻结过滤枚举的告警列表binding。 */
    private static String alarmList(String variableKey) {
        return "{\"source\":\"ALARM_LIST\",\"devices\":{\"variableKey\":\"" + variableKey
                + "\"},\"conditionStates\":[\"ACTIVE\"],\"ackStates\":[\"UNACKNOWLEDGED\"],"
                + "\"severities\":[\"MAJOR\"]}";
    }

    /** 从验证结果取得唯一数据适配器需求。 */
    private static DataAdapterRequirement adapter(DashboardSchemaValidationResult result) {
        return result.unresolvedRequirements().stream()
                .filter(DataAdapterRequirement.class::isInstance)
                .map(DataAdapterRequirement.class::cast)
                .findFirst()
                .orElseThrow();
    }

    /** 执行公开原文语义管线。 */
    private DashboardSchemaValidationResult validate(String source) {
        return validator.validateAndNormalize(source.getBytes(StandardCharsets.UTF_8));
    }

    /** 返回规范化后的首个组件。 */
    private static JsonNode component(DashboardSchemaValidationResult result) {
        return result.normalizedRoot().get("pages").get(0).get("components").get(0);
    }

    /** 断言完整管线以稳定原因拒绝组件。 */
    private void assertRejected(String component, DashboardSchemaValidationException.Reason reason) {
        assertThatThrownBy(() -> validate(schema(component)))
                .isInstanceOfSatisfying(DashboardSchemaValidationException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(reason));
    }
}
