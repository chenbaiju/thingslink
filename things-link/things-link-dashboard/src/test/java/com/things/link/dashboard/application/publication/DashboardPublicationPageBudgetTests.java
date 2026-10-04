package com.things.link.dashboard.application.publication;

import com.things.link.dashboard.application.draft.ValidatedDashboardDraft;
import com.things.link.dashboard.application.schema.DefaultDashboardDraftContractValidator;
import com.things.link.dashboard.infrastructure.schema.JacksonDashboardSchemaParser;
import com.things.link.device.application.DeviceModelBindingFactsPort;
import com.things.link.device.application.ThingModelPropertyFactsPort;
import com.things.link.device.application.ThingModelVersionDescriptorPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 看板发布前逐页数据计划预算的合法边界、保守展开、去重和失败短路测试。 */
@DisplayName("看板发布逐页预算")
class DashboardPublicationPageBudgetTests {

    /** 程序化构造合法Schema的JSON映射器。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 固定模型版本ID。 */
    private static final UUID MODEL_VERSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000201");
    /** 固定摘要。 */
    private static final String DIGEST = "a".repeat(64);

    /** 20设备、200元信息/当前值、10历史和20次请求的同一合法页面必须通过。 */
    @Test
    @DisplayName("所有逐页预算同时达到边界仍可取得资格")
    void allPageBudgetsAtTheirExactLimitsAreAccepted() {
        ObjectNode schema = schema(List.of(
                variable("one", "DEVICE_MULTI", 1),
                variable("ten", "DEVICE_MULTI", 10),
                variable("eight", "DEVICE_MULTI", 8),
                singleVariable(),
                timeRange()), List.of(boundaryComponents("limit", 1)));

        QualificationHarness harness = harness();
        assertThat(harness.service.qualify(candidate(schema)).candidate().projectId()).isNotNull();
    }

    /** 不同变量的maxItems须保守相加，二十加一即使各自合法也必须拒绝。 */
    @Test
    @DisplayName("不同变量设备上限保守相加超过二十时拒绝")
    void deviceMaximumsAcrossVariablesAreAddedConservatively() {
        ObjectNode schema = schema(List.of(
                variable("twenty", "DEVICE_MULTI", 20),
                variable("one", "DEVICE_MULTI", 1)),
                List.of(List.of(table("first", "twenty", 0, 1),
                        table("second", "one", 0, 1))));

        assertBudgetExceededBeforeAuthorities(schema);
    }

    /** 每变量不超过五十键时，按maxItems展开后的全页第201个元信息/当前值组合仍必须拒绝。 */
    @Test
    @DisplayName("元信息和当前值展开到二百零一组合时拒绝")
    void expandedMetadataAndCurrentValueCombinationAboveTwoHundredIsRejected() {
        List<ObjectNode> components = new ArrayList<>();
        components.addAll(tables("wide", "four", 5, 0));
        components.add(table("extra", "one", 50, 1));
        ObjectNode schema = schema(List.of(
                variable("four", "DEVICE_MULTI", 4),
                variable("one", "DEVICE_MULTI", 1)), List.of(components));

        assertBudgetExceededBeforeAuthorities(schema);
    }

    /** 单设备第51个不同属性键即使全页总数不足200也必须拒绝。 */
    @Test
    @DisplayName("单设备属性键超过五十时拒绝")
    void moreThanFiftyKeysForOneDeviceIsRejected() {
        List<ObjectNode> components = new ArrayList<>(tables("single", "one", 5, 0));
        components.add(table("extra", "one", 50, 1));
        ObjectNode schema = schema(List.of(variable("one", "DEVICE_MULTI", 1)), List.of(components));

        assertBudgetExceededBeforeAuthorities(schema);
    }

    /** 十一条完整历史查询键均可通过结构校验，但必须在外部端口前被预算拒绝。 */
    @Test
    @DisplayName("完整历史查询超过十条时拒绝")
    void moreThanTenDistinctHistoryQueriesAreRejected() {
        List<ObjectNode> components = historyCharts("history", "single", 11);
        ObjectNode schema = schema(List.of(singleVariable(), timeRange()), List.of(components));

        assertBudgetExceededBeforeAuthorities(schema);
    }

    /** 在其他预算仍合法时增加第二个告警首页，最坏请求从20升为21并拒绝。 */
    @Test
    @DisplayName("最坏API请求达到二十一次时拒绝")
    void worstCaseApiRequestsAboveTwentyAreRejected() {
        List<ObjectNode> components = new ArrayList<>(boundaryComponents("request", 1));
        components.add(alarm("second_alarm", "one", 2));
        ObjectNode schema = schema(List.of(
                variable("one", "DEVICE_MULTI", 1),
                variable("ten", "DEVICE_MULTI", 10),
                variable("eight", "DEVICE_MULTI", 8),
                singleVariable(),
                timeRange()), List.of(components));

        assertBudgetExceededBeforeAuthorities(schema);
    }

    /** 页面只要引用设备变量就有一次快照复验；状态、告警和目录组合须守住20/21请求边界。 */
    @Test
    @DisplayName("状态告警目录仍计设备快照并守住请求边界")
    void statusAlarmAndDirectoryBindingsStillCountOneDeviceSnapshotRequest() {
        List<ObjectNode> components = new ArrayList<>();
        components.add(status("status", "single"));
        for (int pageSize = 1; pageSize <= 8; pageSize++) {
            components.add(alarm("alarm_" + pageSize, "multi", pageSize));
            if (pageSize <= 7) {
                components.add(selector("directory_" + pageSize, "multi", pageSize));
            }
        }
        List<ObjectNode> variables = List.of(
                variable("multi", "DEVICE_MULTI", 19), singleVariable());
        ObjectNode accepted = schema(variables, List.of(components));

        assertThat(harness().service.qualify(candidate(accepted))).isNotNull();

        components.add(selector("directory_8", "multi", 8));
        assertBudgetExceededBeforeAuthorities(schema(variables, List.of(components)));
    }

    /** 相同完整binding被多个组件观察时只计一次，不能按组件数量重复收费。 */
    @Test
    @DisplayName("重复binding按完整键去重")
    void duplicateBindingsAreCountedOnce() {
        ObjectNode schema = schema(List.of(variable("twenty", "DEVICE_MULTI", 20)),
                List.of(List.of(
                        table("first", "twenty", 0, 1),
                        table("second", "twenty", 0, 1))));

        QualificationHarness harness = harness();
        assertThat(harness.service.qualify(candidate(schema))).isNotNull();
    }

    /** 相同历史完整查询由不同组件重复观察时只计一次。 */
    @Test
    @DisplayName("重复历史完整查询键只计一次")
    void duplicateHistoryQueriesAreCountedOnce() {
        List<ObjectNode> components = new ArrayList<>(historyCharts("first", "single", 10));
        components.addAll(historyCharts("second", "single", 10));
        ObjectNode schema = schema(List.of(singleVariable(), timeRange()), List.of(components));

        QualificationHarness harness = harness();
        assertThat(harness.service.qualify(candidate(schema))).isNotNull();
    }

    /** 告警过滤数组顺序不改变查询身份，而不同pageSize必须形成不同请求。 */
    @Test
    @DisplayName("告警查询按过滤集合去重并区分分页大小")
    void alarmQueriesIgnoreFilterOrderButIncludePageSize() {
        List<ObjectNode> components = new ArrayList<>();
        components.add(alarm("first", "one", 1,
                List.of("PENDING", "ACTIVE"), List.of("UNACKNOWLEDGED", "ACKNOWLEDGED"),
                List.of("CRITICAL", "MAJOR")));
        components.add(alarm("same", "one", 1,
                List.of("ACTIVE", "PENDING"), List.of("ACKNOWLEDGED", "UNACKNOWLEDGED"),
                List.of("MAJOR", "CRITICAL")));
        for (int pageSize = 2; pageSize <= 15; pageSize++) {
            components.add(alarm("alarm_" + pageSize, "one", pageSize));
        }
        ObjectNode accepted = schema(List.of(variable("one", "DEVICE_MULTI", 1)), List.of(components));

        assertThat(harness().service.qualify(candidate(accepted))).isNotNull();

        components.add(alarm("alarm_16", "one", 16));
        assertBudgetExceededBeforeAuthorities(
                schema(List.of(variable("one", "DEVICE_MULTI", 1)), List.of(components)));
    }

    /** 同模型和pageSize的目录查询合并，不同pageSize保持独立。 */
    @Test
    @DisplayName("目录查询按模型和分页大小精确去重")
    void directoryQueriesDeduplicateByModelAndPageSize() {
        List<ObjectNode> components = new ArrayList<>();
        components.add(selector("first", "one", 1));
        components.add(selector("same", "one", 1));
        for (int pageSize = 2; pageSize <= 15; pageSize++) {
            components.add(selector("directory_" + pageSize, "one", pageSize));
        }
        ObjectNode accepted = schema(List.of(variable("one", "DEVICE_MULTI", 1)), List.of(components));

        assertThat(harness().service.qualify(candidate(accepted))).isNotNull();

        components.add(selector("directory_16", "one", 16));
        assertBudgetExceededBeforeAuthorities(
                schema(List.of(variable("one", "DEVICE_MULTI", 1)), List.of(components)));
    }

    /** 两页可各自达到200组合，页面切换不应把互斥计划合并成400。 */
    @Test
    @DisplayName("不同页面独立计算预算")
    void differentPagesAreBudgetedIndependently() {
        ObjectNode schema = schema(List.of(variable("twenty", "DEVICE_MULTI", 20)),
                List.of(tables("page_a", "twenty", 1, 0),
                        tables("page_b", "twenty", 1, 10)));

        QualificationHarness harness = harness();
        assertThat(harness.service.qualify(candidate(schema))).isNotNull();
    }

    /** 断言预算拒绝发生在全部宿主、模型、属性、设备和数据适配端口之前。 */
    private static void assertBudgetExceededBeforeAuthorities(ObjectNode schema) {
        DashboardPublicationCandidate candidate = candidate(schema);
        QualificationHarness harness = harness();

        assertThatThrownBy(() -> harness.service.qualify(candidate))
                .isInstanceOfSatisfying(DashboardPublicationQualificationException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(
                                DashboardPublicationQualificationException.Reason.BUDGET_EXCEEDED));
        verifyNoInteractions(harness.models, harness.properties, harness.devices, harness.host, harness.adapters);
    }

    /** 用公开严格Schema门面先证明输入合法，再构造只隔离预算的候选。 */
    private static DashboardPublicationCandidate candidate(ObjectNode source) {
        ValidatedDashboardDraft validated = new DefaultDashboardDraftContractValidator(
                new JacksonDashboardSchemaParser()).validate(
                        "0", source.toString().getBytes(StandardCharsets.UTF_8));
        JsonNode normalized = validated.content();
        return new DashboardPublicationCandidate(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
                normalized, normalized.toString(), "PG_JSONB_TEXT_V1_SHA256", DIGEST,
                validated.persistenceReferences(), List.of(), List.of(), List.of());
    }

    /** 构造仅宿主快照可用、其他端口无行为的预算测试服务。 */
    private static QualificationHarness harness() {
        ThingModelVersionDescriptorPort models = mock(ThingModelVersionDescriptorPort.class);
        ThingModelPropertyFactsPort properties = mock(ThingModelPropertyFactsPort.class);
        DeviceModelBindingFactsPort devices = mock(DeviceModelBindingFactsPort.class);
        DashboardHostQualificationPort host = mock(DashboardHostQualificationPort.class);
        DashboardDataAdapterQualificationPort adapters = mock(DashboardDataAdapterQualificationPort.class);
        when(host.current()).thenReturn(Optional.of(new DashboardHostQualificationDescriptor(
                "tc.webapp-host/v1", "1.0.0", Set.of("tc.application/v1"), Set.of("tc.dashboard/v1"),
                Map.of(DashboardPublicationEligibilityRequirement.ComponentKind.TEXT, "1.0.0"), Map.of())));
        return new QualificationHarness(models, properties, devices, host, adapters,
                new DashboardPublicationQualificationService(
                        models, properties, devices, host, adapters));
    }

    /** 构造同时命中20/200/10/20边界的合法组件集合；alarm页大小用于制造不同请求键。 */
    private static List<ObjectNode> boundaryComponents(String prefix, int alarmPageSize) {
        List<ObjectNode> components = new ArrayList<>();
        components.add(table(prefix + "_one", "one", 0));
        components.add(table(prefix + "_ten", "ten", 0));
        components.add(table(prefix + "_eight", "eight", 0));
        for (int index = 0; index < 10; index++) {
            components.add(valueCard(prefix + "_single_" + index, "single", "p" + index));
        }
        components.addAll(historyCharts(prefix + "_history", "single", 10));
        components.add(selector(prefix + "_directory_one", "one", 20));
        components.add(selector(prefix + "_directory_ten", "ten", 30));
        components.add(selector(prefix + "_directory_eight", "eight", 40));
        components.add(alarm(prefix + "_alarm", "one", alarmPageSize));
        return List.copyOf(components);
    }

    /** 构造指定数量的十列表格，每张表使用连续属性键。 */
    private static List<ObjectNode> tables(String prefix, String variableKey, int count, int start) {
        List<ObjectNode> tables = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            tables.add(table(prefix + '_' + index, variableKey, start + index * 10));
        }
        return List.copyOf(tables);
    }

    /** 构造十列合法DEVICE_VALUES表格。 */
    private static ObjectNode table(String id, String variableKey, int start) {
        return table(id, variableKey, start, 10);
    }

    /** 构造指定1至10列的合法DEVICE_VALUES表格。 */
    private static ObjectNode table(String id, String variableKey, int start, int count) {
        ObjectNode component = component(id, "TABLE");
        ObjectNode props = component.putObject("props").put("mode", "DEVICE_VALUES");
        ArrayNode columns = props.putArray("columns");
        ArrayNode bindings = component.putObject("bindings").putArray("columns");
        for (int index = 0; index < count; index++) {
            String key = "p" + (start + index);
            columns.addObject().put("id", key).put("label", "属性" + index);
            ObjectNode binding = bindings.addObject().put("id", key).putObject("value");
            current(binding, variableKey, key);
        }
        return component;
    }

    /** 构造一个合法单设备当前值卡片。 */
    private static ObjectNode valueCard(String id, String variableKey, String propertyKey) {
        ObjectNode component = component(id, "VALUE_CARD");
        component.putObject("props");
        ObjectNode value = component.putObject("bindings").putObject("value");
        current(value, variableKey, propertyKey);
        return component;
    }

    /** 构造最多十条合法历史序列的折线图。 */
    private static ObjectNode lineChart(
            String id, String variableKey, int propertyStart, int count) {
        ObjectNode component = component(id, "LINE_CHART");
        ArrayNode series = component.putObject("props").putArray("series");
        ArrayNode bindings = component.putObject("bindings").putArray("series");
        for (int index = 0; index < count; index++) {
            String seriesId = "series" + index;
            String propertyKey = "p" + (propertyStart + index);
            series.addObject().put("id", seriesId).put("label", "序列" + index);
            ObjectNode value = bindings.addObject().put("id", seriesId).putObject("value");
            value.put("source", "HISTORY_SERIES");
            value.putObject("device").put("variableKey", variableKey);
            value.put("propertyKey", propertyKey);
            value.put("timeRangeVariableKey", "period");
            value.put("granularity", "ONE_MINUTE");
            value.put("aggregation", "AVG");
        }
        return component;
    }

    /** 把任意合法历史查询数切成每组件最多四条的折线图集合。 */
    private static List<ObjectNode> historyCharts(String prefix, String variableKey, int count) {
        List<ObjectNode> charts = new ArrayList<>();
        int start = 0;
        while (start < count) {
            int size = Math.min(4, count - start);
            charts.add(lineChart(prefix + '_' + charts.size(), variableKey, start, size));
            start += size;
        }
        return List.copyOf(charts);
    }

    /** 构造合法设备目录首页需求。 */
    private static ObjectNode selector(String id, String variableKey, int pageSize) {
        ObjectNode component = component(id, "DEVICE_SELECTOR");
        component.putObject("props").put("pageSize", pageSize);
        ObjectNode directory = component.putObject("bindings").putObject("directory");
        directory.put("source", "DEVICE_DIRECTORY");
        directory.put("variableKey", variableKey);
        return component;
    }

    /** 构造不读取属性元信息的合法设备状态组件。 */
    private static ObjectNode status(String id, String variableKey) {
        ObjectNode component = component(id, "STATUS");
        component.putObject("props");
        ObjectNode status = component.putObject("bindings").putObject("status");
        status.put("source", "DEVICE_STATUS");
        status.putObject("device").put("variableKey", variableKey);
        return component;
    }

    /** 构造合法告警首页需求。 */
    private static ObjectNode alarm(String id, String variableKey, int pageSize) {
        return alarm(id, variableKey, pageSize,
                List.of("ACTIVE"), List.of("UNACKNOWLEDGED"), List.of("MAJOR"));
    }

    /** 构造带显式过滤集合的合法告警首页需求。 */
    private static ObjectNode alarm(
            String id, String variableKey, int pageSize,
            List<String> conditionStates, List<String> ackStates, List<String> severities) {
        ObjectNode component = component(id, "ALARM_LIST");
        component.putObject("props").put("pageSize", pageSize);
        ObjectNode alarms = component.putObject("bindings").putObject("alarms");
        alarms.put("source", "ALARM_LIST");
        alarms.putObject("devices").put("variableKey", variableKey);
        conditionStates.forEach(alarms.putArray("conditionStates")::add);
        ackStates.forEach(alarms.putArray("ackStates")::add);
        severities.forEach(alarms.putArray("severities")::add);
        return component;
    }

    /** 写入CURRENT_VALUE binding共同字段。 */
    private static void current(ObjectNode binding, String variableKey, String propertyKey) {
        binding.put("source", "CURRENT_VALUE");
        binding.putObject("device").put("variableKey", variableKey);
        binding.put("propertyKey", propertyKey);
    }

    /** 构造无布局坐标的组件壳，页面组装时统一分配不重叠位置。 */
    private static ObjectNode component(String id, String kind) {
        return JSON.createObjectNode().put("id", id).put("kind", kind).put("componentVersion", "1.0.0");
    }

    /** 构造DEVICE_SINGLE变量。 */
    private static ObjectNode singleVariable() {
        return JSON.createObjectNode().put("key", "single").put("type", "DEVICE_SINGLE")
                .put("title", "单设备").put("modelKey", "pump_model");
    }

    /** 构造DEVICE_MULTI变量；测试显式给出maxItems以证明保守展开事实。 */
    private static ObjectNode variable(String key, String type, int maxItems) {
        ObjectNode variable = JSON.createObjectNode().put("key", key).put("type", type)
                .put("title", "设备变量").put("modelKey", "pump_model");
        if ("DEVICE_MULTI".equals(type)) {
            variable.put("maxItems", maxItems);
        }
        return variable;
    }

    /** 构造历史查询使用的TIME_RANGE变量。 */
    private static ObjectNode timeRange() {
        return JSON.createObjectNode().put("key", "period").put("type", "TIME_RANGE")
                .put("title", "时间范围");
    }

    /** 构造完整合法根Schema并为全部页面组件分配不重叠布局。 */
    private static ObjectNode schema(List<ObjectNode> variables, List<List<ObjectNode>> pages) {
        ObjectNode root = JSON.createObjectNode().put("schemaVersion", "tc.dashboard/v1");
        root.putObject("presentation").put("mode", "RESPONSIVE_GRID");
        root.putArray("models").addObject()
                .put("key", "pump_model")
                .put("versionId", MODEL_VERSION_ID.toString())
                .put("digestAlgorithm", "PG_JSONB_TEXT_V1_SHA256")
                .put("digest", DIGEST)
                .put("profile", "TC_PROPERTY_COMPOSITE_V1");
        ArrayNode variableArray = root.putArray("variables");
        variables.forEach(variable -> variableArray.add(variable.deepCopy()));
        ArrayNode pageArray = root.putArray("pages");
        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            ObjectNode page = pageArray.addObject().put("id", "page" + pageIndex)
                    .put("title", "页面" + pageIndex);
            ArrayNode componentArray = page.putArray("components");
            List<ObjectNode> components = pages.get(pageIndex);
            for (int index = 0; index < components.size(); index++) {
                ObjectNode component = components.get(index).deepCopy();
                component.putObject("layout").put("x", index % 24).put("y", index / 24)
                        .put("w", 1).put("h", 1);
                componentArray.add(component);
            }
        }
        return root;
    }

    /** 被测服务与所有可观察外部端口。 */
    private static final class QualificationHarness {
        /** 模型描述端口。 */ private final ThingModelVersionDescriptorPort models;
        /** 属性端口。 */ private final ThingModelPropertyFactsPort properties;
        /** 默认设备端口。 */ private final DeviceModelBindingFactsPort devices;
        /** 宿主端口。 */ private final DashboardHostQualificationPort host;
        /** 数据适配端口。 */ private final DashboardDataAdapterQualificationPort adapters;
        /** 被测服务。 */ private final DashboardPublicationQualificationService service;

        /** 保存同一组可观察测试依赖。 */
        private QualificationHarness(
                ThingModelVersionDescriptorPort models, ThingModelPropertyFactsPort properties,
                DeviceModelBindingFactsPort devices, DashboardHostQualificationPort host,
                DashboardDataAdapterQualificationPort adapters,
                DashboardPublicationQualificationService service) {
            this.models = models;
            this.properties = properties;
            this.devices = devices;
            this.host = host;
            this.adapters = adapters;
            this.service = service;
        }
    }
}
