package com.things.link.dashboard.application;

import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 已授权Dashboard Schema的统一运行数据计划验证器。
 *
 * <p>访问合同§5要求五个App数据入口每次只执行当前Schema可能声明的读取。本类型只解释已经由
 * {@link ApplicationRuntimeSchemaService} 完整校验并从数据库返回的规范Schema；它不查物模型、设备或数据表，
 * 因而可在调用方完成设备可见性和当前模型复核之前阻止任意模型与任意查询类型，又不会通过数据库错误探测
 * 隐藏模型。设备不可见和模型失配的优先级仍由enduser编排层在调用设备描述端口时保证。</p>
 */
@Component
public class DashboardRuntimePlanValidator {

    /** 固定时间预设到精确UTC持续时间的映射。 */
    private static final Map<String, Duration> TIME_PRESETS = Map.of(
            "LAST_1_HOUR", Duration.ofHours(1),
            "LAST_24_HOURS", Duration.ofHours(24),
            "LAST_7_DAYS", Duration.ofDays(7));

    /**
     * 验证模型描述快照只含Schema设备变量声明的模型与数据绑定实际使用的顶层键。
     *
     * @param schema 已完成身份、版本和grant确权的Schema
     * @param models 请求模型描述
     * @param devices 请求设备与键
     */
    public void requireSnapshot(RuntimeDashboardSchema schema, List<DashboardRuntimeModelRequest> models,
            List<DashboardRuntimeDeviceRequest> devices) {
        Plan plan = parse(schema);
        requireRequestBudgets(devices, true);
        if (models == null || models.size() > 20 || hasDuplicateModel(models)) throw invalid();
        Map<UUID, DashboardRuntimeModelRequest> requestedModels = new HashMap<>();
        for (DashboardRuntimeModelRequest model : models) {
            Model declared = plan.modelsByVersion().get(model.versionId());
            if (declared == null || !declared.matches(model) || !declared.selectable()) throw invalid();
            requestedModels.put(model.versionId(), model);
        }
        for (DashboardRuntimeDeviceRequest device : devices) {
            Model model = plan.modelsByVersion().get(device.expectedModelVersionId());
            if (!requestedModels.containsKey(device.expectedModelVersionId()) || model == null
                    || !model.metadataKeys().containsAll(device.propertyKeys())) throw invalid();
        }
        requireModelCounts(devices, plan.modelsByVersion(), false);
    }

    /**
     * 验证稀疏当前值只读取Schema CURRENT_VALUE绑定声明的顶层键。
     *
     * @param schema 已授权Schema
     * @param devices 请求设备与当前值键
     */
    public void requireCurrentValues(RuntimeDashboardSchema schema, List<DashboardRuntimeDeviceRequest> devices) {
        Plan plan = parse(schema);
        requireRequestBudgets(devices, false);
        for (DashboardRuntimeDeviceRequest device : devices) {
            Model model = plan.modelsByVersion().get(device.expectedModelVersionId());
            if (model == null || !model.currentKeys().containsAll(device.propertyKeys())) throw invalid();
        }
        requireModelCounts(devices, plan.modelsByVersion(), true);
    }

    /**
     * 验证目录模型确实由DEVICE_DIRECTORY绑定声明。
     *
     * @param schema 已授权Schema
     * @param modelVersionId 目录精确模型版本
     */
    public void requireCatalog(RuntimeDashboardSchema schema, UUID modelVersionId) {
        Model model = parse(schema).modelsByVersion().get(modelVersionId);
        if (model == null || !model.directory()) throw invalid();
    }

    /**
     * 验证版本化历史的模型、键、粒度、聚合与冻结时间预设均来自同一个HISTORY_SERIES绑定。
     *
     * @param schema 已授权Schema
     * @param modelVersionId 设备预期当前模型
     * @param propertyKey 顶层属性键
     * @param from 窗口起点（含）
     * @param to 窗口终点（不含）
     * @param granularity 请求粒度
     * @param aggregation 请求聚合
     * @param now 服务端同次请求捕获的UTC时刻
     */
    public void requireHistory(RuntimeDashboardSchema schema, UUID modelVersionId, String propertyKey,
            Instant from, Instant to, String granularity, String aggregation, Instant now) {
        Objects.requireNonNull(now, "now");
        Plan plan = parse(schema);
        if (from == null || to == null || !from.isBefore(to) || to.isAfter(now.plusSeconds(60))) throw invalid();
        Duration duration = Duration.between(from, to);
        boolean accepted = plan.histories().stream().anyMatch(history ->
                history.modelVersionId().equals(modelVersionId)
                        && history.propertyKey().equals(propertyKey)
                        && history.granularity().equals(granularity)
                        && history.aggregation().equals(aggregation)
                        && history.allowedDurations().contains(duration));
        if (!accepted) throw invalid();
    }

    /**
     * 验证告警查询的全部设备模型和三组过滤条件与一个Schema ALARM_LIST声明精确一致。
     *
     * @param schema 已授权Schema
     * @param devices 请求设备；属性键必须为空
     * @param conditionStates 条件状态集合
     * @param ackStates 确认状态集合
     * @param severities 等级集合
     */
    public void requireAlarms(RuntimeDashboardSchema schema, List<DashboardRuntimeDeviceRequest> devices,
            Set<String> conditionStates, Set<String> ackStates, Set<String> severities) {
        requireRequestBudgets(devices, true);
        if (devices.stream().anyMatch(device -> !device.propertyKeys().isEmpty())) throw invalid();
        Set<UUID> models = new HashSet<>();
        devices.forEach(device -> models.add(device.expectedModelVersionId()));
        boolean accepted = parse(schema).alarms().stream().anyMatch(alarm ->
                alarm.modelVersionIds().containsAll(models)
                        && devices.size() <= alarm.maximumDevices()
                        && alarm.conditionStates().equals(conditionStates)
                        && alarm.ackStates().equals(ackStates)
                        && alarm.severities().equals(severities));
        if (!accepted) throw invalid();
    }

    /** 每个模型的请求设备数不得超过Schema关联变量可表达的保守上界。 */
    private static void requireModelCounts(List<DashboardRuntimeDeviceRequest> devices,
            Map<UUID, Model> models, boolean currentOnly) {
        Map<UUID, Long> counts = devices.stream().collect(java.util.stream.Collectors.groupingBy(
                DashboardRuntimeDeviceRequest::expectedModelVersionId, java.util.stream.Collectors.counting()));
        for (Map.Entry<UUID, Long> entry : counts.entrySet()) {
            Model model = models.get(entry.getKey());
            int maximum = model == null ? 0 : currentOnly ? model.currentMaximumDevices() : model.snapshotMaximumDevices();
            if (entry.getValue() > maximum) throw invalid();
        }
    }

    /** 验证设备数量、重复、单设备键数与总组合预算。 */
    private static void requireRequestBudgets(List<DashboardRuntimeDeviceRequest> devices, boolean allowEmptyKeys) {
        if (devices == null || devices.isEmpty() || devices.size() > 20) throw invalid();
        Set<UUID> uniqueDevices = new HashSet<>();
        int combinations = 0;
        for (DashboardRuntimeDeviceRequest device : devices) {
            if (device == null || !uniqueDevices.add(device.deviceId())
                    || (!allowEmptyKeys && device.propertyKeys().isEmpty())
                    || device.propertyKeys().size() > 50
                    || new HashSet<>(device.propertyKeys()).size() != device.propertyKeys().size()
                    || device.propertyKeys().stream().anyMatch(key -> key == null || key.isBlank())) throw invalid();
            combinations += device.propertyKeys().size();
        }
        if (combinations > 200) throw invalid();
    }

    /** 检测请求模型版本重复。 */
    private static boolean hasDuplicateModel(List<DashboardRuntimeModelRequest> models) {
        Set<UUID> ids = new HashSet<>();
        return models.stream().anyMatch(model -> model == null || !ids.add(model.versionId()));
    }

    /** 从已验规范Schema建立本次不可变运行查询闭集。 */
    private static Plan parse(RuntimeDashboardSchema source) {
        Objects.requireNonNull(source, "source");
        JsonNode root = source.schema();
        Map<String, MutableModel> models = models(root.path("models"));
        Map<String, Variable> variables = variables(root.path("variables"), models);
        List<History> histories = new ArrayList<>();
        List<Alarm> alarms = new ArrayList<>();
        for (JsonNode page : root.path("pages")) {
            for (JsonNode component : page.path("components")) {
                bindings(component.path("bindings"), variables, models, histories, alarms);
            }
        }
        Map<UUID, Model> frozen = new LinkedHashMap<>();
        for (MutableModel model : models.values()) frozen.put(model.versionId, model.freeze());
        return new Plan(Map.copyOf(frozen), List.copyOf(histories), List.copyOf(alarms));
    }

    /** 解析规范根模型；此处失败表示持久Schema越过了2a5完整性守卫。 */
    private static Map<String, MutableModel> models(JsonNode nodes) {
        Map<String, MutableModel> result = new LinkedHashMap<>();
        for (JsonNode node : nodes) {
            MutableModel model = new MutableModel(text(node, "key"), UUID.fromString(text(node, "versionId")),
                    text(node, "digestAlgorithm"), text(node, "digest"), text(node, "profile"));
            result.put(model.key, model);
        }
        return result;
    }

    /** 解析设备和时间变量，并将可选模型标记到根模型。 */
    private static Map<String, Variable> variables(JsonNode nodes, Map<String, MutableModel> models) {
        Map<String, Variable> result = new LinkedHashMap<>();
        for (JsonNode node : nodes) {
            String type = text(node, "type");
            String key = text(node, "key");
            String modelKey = node.has("modelKey") ? text(node, "modelKey") : "";
            int maximumDevices = "DEVICE_SINGLE".equals(type) ? 1
                    : "DEVICE_MULTI".equals(type) ? node.path("maxItems").asInt() : 0;
            Set<Duration> durations = Set.of();
            if ("DEVICE_SINGLE".equals(type) || "DEVICE_MULTI".equals(type)) {
                requireModel(models, modelKey).selectable = true;
            } else if ("TIME_RANGE".equals(type)) {
                Set<Duration> mutable = new HashSet<>();
                for (JsonNode preset : node.path("allowedPresets")) {
                    Duration duration = TIME_PRESETS.get(preset.asString());
                    if (duration == null) throw corrupt();
                    mutable.add(duration);
                }
                durations = Set.copyOf(mutable);
            }
            result.put(key, new Variable(key, type, modelKey, maximumDevices, durations));
        }
        return result;
    }

    /** 遍历封闭binding对象及数组，提取五条数据路由所需的查询声明。 */
    private static void bindings(JsonNode node, Map<String, Variable> variables,
            Map<String, MutableModel> models, List<History> histories, List<Alarm> alarms) {
        if (node == null || node.isMissingNode()) return;
        if (node.isArray()) {
            for (JsonNode child : node) bindings(child, variables, models, histories, alarms);
            return;
        }
        if (!node.isObject()) return;
        if (node.has("source")) {
            String source = text(node, "source");
            switch (source) {
                case "CURRENT_VALUE" -> current(node, variables, models);
                case "HISTORY_SERIES" -> history(node, variables, models, histories);
                case "DEVICE_DIRECTORY" -> directory(node, variables, models);
                case "ALARM_LIST" -> alarm(node, variables, models, alarms);
                case "DEVICE_STATUS" -> status(node, variables, models);
                case "ENUM_TEXT" -> { }
                default -> throw corrupt();
            }
        }
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            if (!"device".equals(field.getKey()) && !"devices".equals(field.getKey())) {
                bindings(field.getValue(), variables, models, histories, alarms);
            }
        }
    }

    /** 登记CURRENT_VALUE可读顶层键。 */
    private static void current(JsonNode binding, Map<String, Variable> variables,
            Map<String, MutableModel> models) {
        Variable variable = deviceVariable(binding.path("device"), variables);
        String key = text(binding, "propertyKey");
        MutableModel model = requireModel(models, variable.modelKey());
        model.currentKeys.add(key);
        model.metadataKeys.add(key);
        model.currentVariables.put(variable.key(), variable.maximumDevices());
        model.snapshotVariables.put(variable.key(), variable.maximumDevices());
    }

    /** 登记HISTORY_SERIES精确模型、键、时间预设、粒度与聚合。 */
    private static void history(JsonNode binding, Map<String, Variable> variables,
            Map<String, MutableModel> models, List<History> histories) {
        Variable device = deviceVariable(binding.path("device"), variables);
        Variable time = variables.get(text(binding, "timeRangeVariableKey"));
        if (time == null || !"TIME_RANGE".equals(time.type())) throw corrupt();
        String key = text(binding, "propertyKey");
        MutableModel model = requireModel(models, device.modelKey());
        model.metadataKeys.add(key);
        model.snapshotVariables.put(device.key(), device.maximumDevices());
        histories.add(new History(model.versionId, key, text(binding, "granularity"),
                text(binding, "aggregation"), time.allowedDurations()));
    }

    /** 登记DEVICE_DIRECTORY允许的模型。 */
    private static void directory(JsonNode binding, Map<String, Variable> variables,
            Map<String, MutableModel> models) {
        Variable variable = variables.get(text(binding, "variableKey"));
        if (variable == null || !variable.type().startsWith("DEVICE_")) throw corrupt();
        MutableModel model = requireModel(models, variable.modelKey());
        model.directory = true;
        model.snapshotVariables.put(variable.key(), variable.maximumDevices());
    }

    /** 登记ALARM_LIST精确模型与过滤集合。 */
    private static void alarm(JsonNode binding, Map<String, Variable> variables,
            Map<String, MutableModel> models, List<Alarm> alarms) {
        Variable variable = deviceVariable(binding.path("devices"), variables);
        MutableModel declared = requireModel(models, variable.modelKey());
        declared.snapshotVariables.put(variable.key(), variable.maximumDevices());
        UUID model = declared.versionId;
        alarms.add(new Alarm(Set.of(model), strings(binding.path("conditionStates")),
                strings(binding.path("ackStates")), strings(binding.path("severities")), variable.maximumDevices()));
    }

    /** 设备状态也需要运行快照复核其设备变量容量。 */
    private static void status(JsonNode binding, Map<String, Variable> variables,
            Map<String, MutableModel> models) {
        Variable variable = deviceVariable(binding.path("device"), variables);
        MutableModel model = requireModel(models, variable.modelKey());
        model.snapshotVariables.put(variable.key(), variable.maximumDevices());
    }

    /** 解析DeviceReference并要求设备变量。 */
    private static Variable deviceVariable(JsonNode reference, Map<String, Variable> variables) {
        Variable variable = variables.get(text(reference, "variableKey"));
        if (variable == null || !variable.type().startsWith("DEVICE_")) throw corrupt();
        return variable;
    }

    /** 读取字符串数组为不可变集合。 */
    private static Set<String> strings(JsonNode node) {
        Set<String> values = new HashSet<>();
        for (JsonNode value : node) values.add(value.asString());
        return Set.copyOf(values);
    }

    /** 获取已验模型别名。 */
    private static MutableModel requireModel(Map<String, MutableModel> models, String key) {
        MutableModel model = models.get(key);
        if (model == null) throw corrupt();
        return model;
    }

    /** 从已验Schema读取必填字符串。 */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) throw corrupt();
        return value.asString();
    }

    /** 客户端计划越过Schema声明统一使用10001。 */
    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER);
    }

    /** 已验持久Schema无法解释时保留内部完整性失败，禁止降为客户端参数错误。 */
    private static IllegalStateException corrupt() {
        return new IllegalStateException("已验Dashboard Schema无法建立运行数据计划");
    }

    /** 根模型解析期间使用的可变聚合器。 */
    private static final class MutableModel {
        /** Schema模型别名。 */ private final String key;
        /** 不可变模型版本。 */ private final UUID versionId;
        /** 摘要算法。 */ private final String digestAlgorithm;
        /** 摘要。 */ private final String digest;
        /** Profile。 */ private final String profile;
        /** CURRENT_VALUE声明的键。 */ private final Set<String> currentKeys = new HashSet<>();
        /** 快照元数据可读取键。 */ private final Set<String> metadataKeys = new HashSet<>();
        /** 是否被设备变量引用。 */ private boolean selectable;
        /** 是否存在目录绑定。 */ private boolean directory;
        /** 所有快照相关设备变量及各自声明容量。 */ private final Map<String, Integer> snapshotVariables = new HashMap<>();
        /** 当前值相关设备变量及各自声明容量。 */ private final Map<String, Integer> currentVariables = new HashMap<>();

        /** 保存根模型不可变身份。 */
        private MutableModel(String key, UUID versionId, String digestAlgorithm, String digest, String profile) {
            this.key = key;
            this.versionId = versionId;
            this.digestAlgorithm = digestAlgorithm;
            this.digest = digest;
            this.profile = profile;
        }

        /** 冻结本次聚合结果。 */
        private Model freeze() {
            return new Model(versionId, digestAlgorithm, digest, profile, Set.copyOf(currentKeys),
                    Set.copyOf(metadataKeys), selectable, directory,
                    maximum(snapshotVariables), maximum(currentVariables));
        }

        /** 不同变量可同时选择，保守求和后仍受全页20设备上限约束。 */
        private static int maximum(Map<String, Integer> variables) {
            return Math.min(20, variables.values().stream().mapToInt(Integer::intValue).sum());
        }
    }

    /** 不可变模型运行闭集。 */
    private record Model(UUID versionId, String digestAlgorithm, String digest, String profile,
            Set<String> currentKeys, Set<String> metadataKeys, boolean selectable, boolean directory,
            int snapshotMaximumDevices, int currentMaximumDevices) {
        /** 核对客户端摘要声明，不以版本ID单独冒充完整模型身份。 */
        private boolean matches(DashboardRuntimeModelRequest request) {
            return digestAlgorithm.equals(request.digestAlgorithm()) && digest.equals(request.digest())
                    && profile.equals(request.profile());
        }
    }

    /** Schema变量的运行相关投影。 */
    private record Variable(String key, String type, String modelKey, int maximumDevices,
            Set<Duration> allowedDurations) { }

    /** 精确历史声明。 */
    private record History(UUID modelVersionId, String propertyKey, String granularity,
            String aggregation, Set<Duration> allowedDurations) { }

    /** 精确告警声明。 */
    private record Alarm(Set<UUID> modelVersionIds, Set<String> conditionStates,
            Set<String> ackStates, Set<String> severities, int maximumDevices) { }

    /** 单Schema解析出的全部运行查询闭集。 */
    private record Plan(Map<UUID, Model> modelsByVersion, List<History> histories, List<Alarm> alarms) { }
}
