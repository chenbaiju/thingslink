package com.things.link.dashboard.application.publication;

import com.things.link.device.application.DeviceModelBindingFacts;
import com.things.link.device.application.DeviceModelBindingFactsPort;
import com.things.link.device.application.ThingModelPropertyFacts;
import com.things.link.device.application.ThingModelPropertyFactsPort;
import com.things.link.device.application.ThingModelVersionDescriptor;
import com.things.link.device.application.ThingModelVersionDescriptorPort;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 逐项编排宿主、device领域与运行数据适配器外部资格的只读服务。 */
@Service
public class DashboardPublicationQualificationService {

    /** ConfigNumber非零绝对值下界。 */
    private static final BigDecimal CONFIG_NUMBER_MINIMUM_ABSOLUTE = new BigDecimal("0.000000000001");
    /** ConfigNumber绝对值上界。 */
    private static final BigDecimal CONFIG_NUMBER_MAXIMUM_ABSOLUTE = new BigDecimal("1000000000000");
    /** 不可变模型版本身份与摘要权威端口。 */
    private final ThingModelVersionDescriptorPort modelPort;
    /** 不可变模型版本单属性权威端口。 */
    private final ThingModelPropertyFactsPort propertyPort;
    /** 默认设备当前模型绑定权威端口。 */
    private final DeviceModelBindingFactsPort devicePort;
    /** 实际部署宿主注册事实端口。 */
    private final DashboardHostQualificationPort hostPort;
    /** 已交付生产数据适配能力端口。 */
    private final DashboardDataAdapterQualificationPort dataAdapterPort;

    /**
     * 创建只读资格编排器。
     *
     * @param modelPort device领域不可变模型资格端口
     * @param propertyPort device领域不可变模型属性端口
     * @param devicePort device领域默认设备绑定端口
     * @param hostPort 实际宿主组件与资源注册端口
     * @param dataAdapterPort 实际生产数据适配能力端口
     */
    public DashboardPublicationQualificationService(
            ThingModelVersionDescriptorPort modelPort,
            ThingModelPropertyFactsPort propertyPort,
            DeviceModelBindingFactsPort devicePort,
            DashboardHostQualificationPort hostPort,
            DashboardDataAdapterQualificationPort dataAdapterPort) {
        this.modelPort = Objects.requireNonNull(modelPort, "modelPort");
        this.propertyPort = Objects.requireNonNull(propertyPort, "propertyPort");
        this.devicePort = Objects.requireNonNull(devicePort, "devicePort");
        this.hostPort = Objects.requireNonNull(hostPort, "hostPort");
        this.dataAdapterPort = Objects.requireNonNull(dataAdapterPort, "dataAdapterPort");
    }

    /**
     * 核验候选全部八类外部需求；端口异常原样传播，绝不降级为成功。
     *
     * <p>入口保持包内可见，只供同包后续原子发布编排消费，外部调用者不能用自构候选取得资格证明。</p>
     *
     * @param candidate 已绑定持久草稿身份和PG摘要的候选
     * @return 无公开构造器的外部资格证明
     */
    QualifiedDashboardPublicationCandidate qualify(DashboardPublicationCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        // 旧发布保持先本地预算再外部依赖的错误优先级；同快照入口再次验证仍是有界检查。
        requirePageBudgets(candidate.normalizedSchema());
        DashboardHostQualificationDescriptor host = hostPort.current()
                .orElseThrow(() -> failure(
                        DashboardPublicationQualificationException.Reason.HOST_COMPONENT_UNAVAILABLE));
        return qualify(candidate, host);
    }

    /** 分享在同一权威宿主快照核兼容区间后重用完整资格，不扩展公开任意资格入口。 */
    QualifiedDashboardPublicationCandidate qualify(
            DashboardPublicationCandidate candidate, DashboardHostQualificationDescriptor host) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(host, "host");
        requirePageBudgets(candidate.normalizedSchema());
        require("tc.webapp-host/v1".equals(host.formatVersion())
                        && validHostVersion(host.hostVersion())
                        && host.supportedApplicationFormats().equals(Set.of("tc.application/v1"))
                        && host.supportedSchemas().equals(Set.of("tc.dashboard/v1"))
                        && host.supportedSchemas().contains(
                                candidate.normalizedSchema().path("schemaVersion").asString())
                        && !host.componentVersions().isEmpty() && host.componentVersions().size() <= 10
                        && host.resourceDigests().size() <= 64,
                DashboardPublicationQualificationException.Reason.HOST_COMPONENT_UNAVAILABLE);
        Map<String, ThingModelVersionDescriptor> models = qualifyModels(candidate);
        Map<PropertyIdentity, ThingModelPropertyFacts> properties = new HashMap<>();
        for (DashboardPublicationEligibilityRequirement requirement : candidate.eligibilityRequirements()) {
            if (requirement instanceof DashboardPublicationEligibilityRequirement.HostComponent value) {
                require(value.componentVersion().equals(host.componentVersions().get(value.kind()))
                                && hostVersionInRange(host.hostVersion(), value.minimumHostVersionInclusive(),
                                        value.maximumHostVersionExclusive()),
                        DashboardPublicationQualificationException.Reason.HOST_COMPONENT_UNAVAILABLE);
            } else if (requirement instanceof DashboardPublicationEligibilityRequirement.BuiltinResource value) {
                require(value.digest().equals(host.resourceDigests().get(value.resourceId())),
                        DashboardPublicationQualificationException.Reason.BUILTIN_RESOURCE_UNAVAILABLE);
            } else if (requirement instanceof DashboardPublicationEligibilityRequirement.ModelReference) {
                // 模型引用已在首轮统一核验并建立后续需求所需的别名索引。
            } else if (requirement instanceof DashboardPublicationEligibilityRequirement.DefaultDevice value) {
                qualifyDefaultDevice(candidate, models, value);
            } else if (requirement instanceof DashboardPublicationEligibilityRequirement.ModelProperty value) {
                ThingModelPropertyFacts property = property(
                        candidate.projectId(), models, properties, value.modelKey(), value.propertyKey());
                require(value.allowedDataTypes().stream().anyMatch(type -> type.name().equals(property.dataType().name())),
                        DashboardPublicationQualificationException.Reason.MODEL_PROPERTY_INVALID);
            } else if (requirement instanceof DashboardPublicationEligibilityRequirement.HistoricalProperty value) {
                ThingModelPropertyFacts property = property(
                        candidate.projectId(), models, properties, value.modelKey(), value.propertyKey());
                require(property.dataType() == ThingModelPropertyFacts.DataType.NUMBER,
                        DashboardPublicationQualificationException.Reason.MODEL_PROPERTY_INVALID);
                require(dataAdapterPort.supportsHistory(candidate.projectId(), value),
                        DashboardPublicationQualificationException.Reason.DATA_ADAPTER_UNAVAILABLE);
            } else if (requirement instanceof DashboardPublicationEligibilityRequirement.ModelGaugeRange value) {
                ThingModelPropertyFacts property = property(
                        candidate.projectId(), models, properties, value.modelKey(), value.propertyKey());
                require(property.dataType() == ThingModelPropertyFacts.DataType.NUMBER
                                && configNumber(property.minimumValue()) && configNumber(property.maximumValue())
                                && property.minimumValue().compareTo(property.maximumValue()) < 0,
                        DashboardPublicationQualificationException.Reason.MODEL_RANGE_INVALID);
            } else if (requirement instanceof DashboardPublicationEligibilityRequirement.DataAdapter value) {
                require(dataAdapterPort.supports(candidate.projectId(), value),
                        DashboardPublicationQualificationException.Reason.DATA_ADAPTER_UNAVAILABLE);
            } else {
                throw new IllegalStateException("存在未编排的看板发布资格需求类型");
            }
        }
        return new QualifiedDashboardPublicationCandidate(candidate);
    }

    /** 首轮核验所有模型身份、项目、摘要算法、摘要和Profile，供后续属性与设备需求复用。 */
    private Map<String, ThingModelVersionDescriptor> qualifyModels(
            DashboardPublicationCandidate candidate) {
        Map<String, ThingModelVersionDescriptor> models = new HashMap<>();
        for (DashboardPublicationEligibilityRequirement requirement : candidate.eligibilityRequirements()) {
            if (!(requirement instanceof DashboardPublicationEligibilityRequirement.ModelReference value)) {
                continue;
            }
            ThingModelVersionDescriptor actual = modelPort
                    .find(candidate.projectId(), value.versionId())
                    .orElseThrow(() -> failure(
                            DashboardPublicationQualificationException.Reason.MODEL_REFERENCE_INVALID));
            require(actual.projectId().equals(candidate.projectId())
                            && actual.versionId().equals(value.versionId())
                            && actual.digestAlgorithm().equals(value.digestAlgorithm())
                            && actual.digest().equals(value.digest())
                            && actual.profile().equals(value.profile()),
                    DashboardPublicationQualificationException.Reason.MODEL_REFERENCE_INVALID);
            if (models.putIfAbsent(value.modelKey(), actual) != null) {
                throw failure(DashboardPublicationQualificationException.Reason.MODEL_REFERENCE_INVALID);
            }
        }
        return Map.copyOf(models);
    }

    /** 核验默认设备属于候选项目且当前绑定到变量声明的精确模型版本。 */
    private void qualifyDefaultDevice(
            DashboardPublicationCandidate candidate,
            Map<String, ThingModelVersionDescriptor> models,
            DashboardPublicationEligibilityRequirement.DefaultDevice requirement) {
        ThingModelVersionDescriptor expectedModel = models.get(requirement.modelKey());
        require(expectedModel != null,
                DashboardPublicationQualificationException.Reason.DEFAULT_DEVICE_INVALID);
        DeviceModelBindingFacts device = devicePort.find(candidate.projectId(), requirement.deviceId())
                .orElseThrow(() -> failure(
                        DashboardPublicationQualificationException.Reason.DEFAULT_DEVICE_INVALID));
        require(device.projectId().equals(candidate.projectId())
                        && device.deviceId().equals(requirement.deviceId())
                        && device.thingModelVersionId().equals(expectedModel.versionId()),
                DashboardPublicationQualificationException.Reason.DEFAULT_DEVICE_INVALID);
    }

    /** 按模型别名和属性键取得权威顶层属性；任一缺失使用同一安全分类。 */
    private ThingModelPropertyFacts property(
            UUID projectId, Map<String, ThingModelVersionDescriptor> models,
            Map<PropertyIdentity, ThingModelPropertyFacts> cache,
            String modelKey, String propertyKey) {
        ThingModelVersionDescriptor model = models.get(modelKey);
        if (model == null) {
            throw failure(DashboardPublicationQualificationException.Reason.MODEL_PROPERTY_INVALID);
        }
        PropertyIdentity identity = new PropertyIdentity(model.versionId(), propertyKey);
        ThingModelPropertyFacts property = cache.get(identity);
        if (property == null) {
            property = propertyPort.find(projectId, model.versionId(), propertyKey)
                    .orElseThrow(() -> failure(
                            DashboardPublicationQualificationException.Reason.MODEL_PROPERTY_INVALID));
            cache.put(identity, property);
        }
        if (property == null || !property.propertyKey().equals(propertyKey)) {
            throw failure(DashboardPublicationQualificationException.Reason.MODEL_PROPERTY_INVALID);
        }
        return property;
    }

    /** 按Schema合同ConfigNumber范围和十五位有效数字复核MODEL量程端点。 */
    private static boolean configNumber(BigDecimal value) {
        if (value == null) {
            return false;
        }
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.precision() > 15) {
            return false;
        }
        if (normalized.signum() == 0) {
            return true;
        }
        BigDecimal absolute = normalized.abs();
        return absolute.compareTo(CONFIG_NUMBER_MINIMUM_ABSOLUTE) >= 0
                && absolute.compareTo(CONFIG_NUMBER_MAXIMUM_ABSOLUTE) <= 0;
    }

    /** 按三段无前导零SemVer比较宿主版本是否落在需求半开区间。 */
    private static boolean hostVersionInRange(String hostVersion, String minimum, String maximum) {
        try {
            int[] current = semanticVersion(hostVersion);
            return compareVersion(current, semanticVersion(minimum)) >= 0
                    && compareVersion(current, semanticVersion(maximum)) < 0;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** 无条件验证HostDescriptor当前版本，即使空画布没有组件范围也不能绕过描述符合同。 */
    private static boolean validHostVersion(String hostVersion) {
        try {
            semanticVersion(hostVersion);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** 解析合同限定的三个非负整数版本段。 */
    private static int[] semanticVersion(String value) {
        if (value == null || !value.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")) {
            throw new IllegalArgumentException("宿主版本格式无效");
        }
        String[] parts = value.split("\\.");
        try {
            int[] version = new int[]{
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
            for (int part : version) {
                if (part > 65_535) {
                    throw new IllegalArgumentException("宿主版本段超过65535");
                }
            }
            return version;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("宿主版本段超出整数范围", exception);
        }
    }

    /** 逐段比较三段版本号。 */
    private static int compareVersion(int[] left, int[] right) {
        for (int index = 0; index < left.length; index++) {
            int comparison = Integer.compare(left[index], right[index]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    /** 对每个可见页面按变量maxItems和完整查询键执行确定性最坏预算预检。 */
    private static void requirePageBudgets(JsonNode schema) {
        Map<String, VariableBudget> variables = variableBudgets(schema.path("variables"));
        for (JsonNode page : schema.path("pages")) {
            PageBudget budget = new PageBudget(variables);
            for (JsonNode component : page.path("components")) {
                List<JsonNode> bindings = new java.util.ArrayList<>();
                collectBindings(component.path("bindings"), bindings);
                for (JsonNode binding : bindings) {
                    budget.accept(binding, component.path("props"));
                }
            }
            budget.verify();
        }
    }

    /** 建立设备变量的保守设备数与模型别名索引。 */
    private static Map<String, VariableBudget> variableBudgets(JsonNode variables) {
        Map<String, VariableBudget> result = new LinkedHashMap<>();
        for (JsonNode variable : variables) {
            String type = variable.path("type").asString();
            if ("DEVICE_SINGLE".equals(type)) {
                result.put(variable.path("key").asString(),
                        new VariableBudget(1, variable.path("modelKey").asString()));
            } else if ("DEVICE_MULTI".equals(type)) {
                result.put(variable.path("key").asString(), new VariableBudget(
                        variable.path("maxItems").asInt(), variable.path("modelKey").asString()));
            }
        }
        return Map.copyOf(result);
    }

    /** 递归提取bindings中以source判别的叶节点，保持数组与对象组合结构无关。 */
    private static void collectBindings(JsonNode node, List<JsonNode> result) {
        if (node.isObject()) {
            if (node.path("source").isString()) {
                result.add(node);
                return;
            }
            node.properties().forEach(entry -> collectBindings(entry.getValue(), result));
        } else if (node.isArray()) {
            node.forEach(value -> collectBindings(value, result));
        }
    }

    /** 条件不成立时抛出固定安全失败，不拼接外部事实值。 */
    private static void require(boolean condition, DashboardPublicationQualificationException.Reason reason) {
        if (!condition) {
            throw failure(reason);
        }
    }

    /** @return 指定稳定原因的安全失败 */
    private static DashboardPublicationQualificationException failure(
            DashboardPublicationQualificationException.Reason reason) {
        return new DashboardPublicationQualificationException(reason);
    }

    /** @param versionId 不可变模型版本ID @param propertyKey 顶层属性键 */
    private record PropertyIdentity(UUID versionId, String propertyKey) { }

    /** @param maximumDevices 发布预检保守设备数 @param modelKey 模型别名 */
    private record VariableBudget(int maximumDevices, String modelKey) { }

    /** 单页预算累加器，以变量槽位代表运行时可能互不重叠的设备集合。 */
    private static final class PageBudget {
        /** 设备变量定义。 */ private final Map<String, VariableBudget> variables;
        /** 本页引用的设备变量。 */ private final Set<String> deviceVariables = new HashSet<>();
        /** meta所需的变量与属性组合。 */ private final Set<String> metadataKeys = new HashSet<>();
        /** 当前值所需的变量与属性组合。 */ private final Set<String> currentKeys = new HashSet<>();
        /** 完整历史查询键。 */ private final Set<String> historyKeys = new HashSet<>();
        /** 告警首页查询键。 */ private final Set<String> alarmKeys = new HashSet<>();
        /** 目录首页查询键。 */ private final Set<String> directoryKeys = new HashSet<>();

        /** @param variables 规范Schema设备变量定义 */
        private PageBudget(Map<String, VariableBudget> variables) {
            this.variables = variables;
        }

        /** 将一个规范binding计入对应设备、属性和API最坏上界。 */
        private void accept(JsonNode binding, JsonNode props) {
            String source = binding.path("source").asString();
            String variableKey = deviceVariableKey(binding);
            VariableBudget variable = variables.get(variableKey);
            if (variable != null) {
                deviceVariables.add(variableKey);
            }
            String propertyKey = binding.path("propertyKey").asString();
            if (variable != null && !propertyKey.isEmpty()
                    && ("CURRENT_VALUE".equals(source) || "HISTORY_SERIES".equals(source))) {
                metadataKeys.add(variableKey + '\u0000' + propertyKey);
            }
            if (variable != null && "CURRENT_VALUE".equals(source)) {
                currentKeys.add(variableKey + '\u0000' + propertyKey);
            } else if (variable != null && "HISTORY_SERIES".equals(source)) {
                historyKeys.add(variableKey + '\u0000' + propertyKey + '\u0000'
                        + binding.path("timeRangeVariableKey").asString() + '\u0000'
                        + binding.path("granularity").asString() + '\u0000'
                        + binding.path("aggregation").asString());
            } else if ("ALARM_LIST".equals(source)) {
                alarmKeys.add(variableKey + '\u0000'
                        + sortedValues(binding.path("conditionStates")) + '\u0000'
                        + sortedValues(binding.path("ackStates")) + '\u0000'
                        + sortedValues(binding.path("severities")) + '\u0000'
                        + props.path("pageSize").asInt());
            } else if (variable != null && "DEVICE_DIRECTORY".equals(source)) {
                directoryKeys.add(variable.modelKey() + '\u0000' + props.path("pageSize").asInt());
            }
        }

        /** 从单设备device或多设备devices引用中取得变量key。 */
        private static String deviceVariableKey(JsonNode binding) {
            JsonNode reference = binding.has("device") ? binding.path("device")
                    : binding.has("devices") ? binding.path("devices") : binding;
            return reference.path("variableKey").asString();
        }

        /** 把已校验枚举数组按机器值排序，避免JSON属性或数组顺序影响等价告警查询去重。 */
        private static String sortedValues(JsonNode values) {
            List<String> sorted = new java.util.ArrayList<>();
            values.forEach(value -> sorted.add(value.asString()));
            sorted.sort(String::compareTo);
            return String.join(",", sorted);
        }

        /** 核验20设备、200元信息/当前值、单设备50键、10历史及20次最坏请求。 */
        private void verify() {
            int devices = deviceVariables.stream().mapToInt(key -> variables.get(key).maximumDevices()).sum();
            int metadata = expandedPropertyCount(metadataKeys);
            int current = expandedPropertyCount(currentKeys);
            int histories = expandedHistoryCount();
            int requests = 4 + (devices > 0 ? 1 : 0) + (current > 0 ? 1 : 0)
                    + histories + alarmKeys.size() + directoryKeys.size();
            boolean perDeviceExceeded = deviceVariables.stream().anyMatch(variable ->
                    propertyCount(metadataKeys, variable) > 50 || propertyCount(currentKeys, variable) > 50);
            if (devices > 20 || metadata > 200 || current > 200 || histories > 10
                    || requests > 20 || perDeviceExceeded) {
                throw failure(DashboardPublicationQualificationException.Reason.BUDGET_EXCEEDED);
            }
        }

        /** 按变量maxItems保守展开属性组合。 */
        private int expandedPropertyCount(Set<String> keys) {
            return deviceVariables.stream().mapToInt(variable ->
                    Math.toIntExact(propertyCount(keys, variable)
                            * variables.get(variable).maximumDevices())).sum();
        }

        /** 按变量maxItems保守展开历史完整查询键。 */
        private int expandedHistoryCount() {
            return deviceVariables.stream().mapToInt(variable ->
                    Math.toIntExact(propertyCount(historyKeys, variable)
                            * variables.get(variable).maximumDevices())).sum();
        }

        /** 统计指定变量的去重键数量。 */
        private static long propertyCount(Set<String> keys, String variable) {
            String prefix = variable + '\u0000';
            return keys.stream().filter(key -> key.startsWith(prefix)).count();
        }
    }}
