package com.things.link.dashboard.application;

import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.dashboard.domain.DashboardShareVariableScope;
import com.things.link.device.application.RuntimeDeviceQuery;
import com.things.link.device.application.RuntimeModelReference;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 分享合同§2/4的纯查询授权；始终保留原变量及binding，不把同模型范围与属性做笛卡尔积。 */
@Component
public class DashboardSharePlanValidator {
    /** 冻结预设以UTC持续时间计算，不接受客户端任意起止窗口。 */
    private static final Map<String, Duration> PRESETS = Map.of("LAST_1_HOUR", Duration.ofHours(1),
            "LAST_24_HOURS", Duration.ofHours(24), "LAST_7_DAYS", Duration.ofDays(7));

    /** 模型身份必须精确，元数据键只能来自设备所属变量实际使用的当前值或历史binding。 */
    public void requireSnapshots(DashboardShareReadContext context, List<RuntimeModelReference> models,
            List<RuntimeDeviceQuery> devices) {
        budgets(devices, true);
        if (models == null || models.size() > 20) throw invalid();
        Plan plan = parse(context);
        Set<UUID> requested = new HashSet<>();
        for (RuntimeModelReference model : models) {
            if (model == null || !requested.add(model.versionId())) throw invalid();
            if (plan.variables().values().stream().noneMatch(variable -> variable.model().equals(model))) {
                throw forbidden();
            }
        }
        for (RuntimeDeviceQuery device : devices) {
            if (!requested.contains(device.expectedModelVersionId())) throw invalid();
            List<Binding> bindings = matching(plan, device);
            if (bindings.isEmpty() || device.propertyKeys().stream().anyMatch(key -> bindings.stream().noneMatch(
                    binding -> Set.of("CURRENT_VALUE", "HISTORY_SERIES").contains(binding.source())
                            && key.equals(binding.node().path("propertyKey").asString())))) throw forbidden();
        }
    }

    /** 候选集合全部已获能力授权；匿名请求没有宿主当前选择状态，不能推断SINGLE正在选择哪台设备。 */
    public void requireCurrent(DashboardShareReadContext context, List<RuntimeDeviceQuery> devices) {
        budgets(devices, false);
        Plan plan = parse(context);
        for (RuntimeDeviceQuery device : devices) {
            List<Binding> bindings = matching(plan, device);
            for (String key : device.propertyKeys()) {
                if (bindings.stream().noneMatch(binding -> "CURRENT_VALUE".equals(binding.source())
                        && key.equals(binding.node().path("propertyKey").asString()))) throw forbidden();
            }
        }
    }

    /** 目录只返回该变量冻结候选，不以同模型其他变量或整个项目扩展分页。 */
    public DashboardShareVariableScope requireCatalog(DashboardShareReadContext context, String variableKey) {
        if (variableKey == null || !variableKey.matches("[a-z][a-z0-9_]{0,63}")) throw invalid();
        Plan plan = parse(context);
        return plan.bindings().stream().filter(binding -> "DEVICE_DIRECTORY".equals(binding.source())
                        && binding.variable().scope().variableKey().equals(variableKey))
                .map(binding -> binding.variable().scope()).findFirst().orElseThrow(DashboardSharePlanValidator::forbidden);
    }

    /** 历史预设、粒度、聚合和设备必须同时命中同一个原binding，锚点以同事务数据库时间为准。 */
    public Instant requireHistory(DashboardShareReadContext context, UUID deviceId, UUID modelId, String key,
            String preset, Instant anchor, String granularity, String aggregation) {
        if (deviceId == null || modelId == null || !validKey(key) || preset == null || !PRESETS.containsKey(preset)
                || anchor == null || anchor.isBefore(context.databaseNow().minusSeconds(60))
                || anchor.isAfter(context.databaseNow())
                || granularity == null || !Set.of("RAW", "ONE_MINUTE", "ONE_HOUR", "ONE_DAY").contains(granularity)
                || aggregation == null || !Set.of("AVG", "MIN", "MAX", "SUM", "COUNT").contains(aggregation)) {
            throw invalid();
        }
        Plan plan = parse(context);
        RuntimeDeviceQuery device = new RuntimeDeviceQuery(deviceId, modelId, List.of(key));
        boolean allowed = matching(plan, device).stream().anyMatch(binding -> {
            JsonNode node = binding.node();
            return "HISTORY_SERIES".equals(binding.source()) && key.equals(node.path("propertyKey").asString())
                    && granularity.equals(node.path("granularity").asString())
                    && aggregation.equals(node.path("aggregation").asString())
                    && plan.presets().getOrDefault(node.path("timeRangeVariableKey").asString(), Set.of()).contains(preset);
        });
        if (!allowed) throw forbidden();
        return anchor.minus(PRESETS.get(preset));
    }

    /** 整组设备及三种过滤必须属于同一告警binding，不能拼接两个合法查询扩大范围。 */
    public void requireAlarms(DashboardShareReadContext context, List<RuntimeDeviceQuery> devices,
            Set<String> condition, Set<String> ack, Set<String> severities) {
        budgets(devices, true);
        if (devices.stream().anyMatch(device -> !device.propertyKeys().isEmpty())
                || !validEnums(condition, Set.of("PENDING", "ACTIVE", "CLEARED"))
                || !validEnums(ack, Set.of("UNACKNOWLEDGED", "ACKNOWLEDGED"))
                || !validEnums(severities, Set.of("CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO"))) throw invalid();
        boolean allowed = parse(context).bindings().stream().anyMatch(binding ->
                "ALARM_LIST".equals(binding.source()) && devices.size() <= binding.variable().maximum()
                        && devices.stream().allMatch(device -> owns(binding.variable(), device))
                        && condition.equals(strings(binding.node().path("conditionStates")))
                        && ack.equals(strings(binding.node().path("ackStates")))
                        && severities.equals(strings(binding.node().path("severities"))));
        if (!allowed) throw forbidden();
    }

    /** 先验证客户端语法和固定预算，避免把400参数错误误报成能力丢失。 */
    private static void budgets(List<RuntimeDeviceQuery> devices, boolean emptyKeysAllowed) {
        if (devices == null || devices.isEmpty() || devices.size() > 20) throw invalid();
        Set<UUID> identities = new HashSet<>();
        int total = 0;
        for (RuntimeDeviceQuery device : devices) {
            if (device == null || !identities.add(device.deviceId()) || device.propertyKeys().size() > 50
                    || (!emptyKeysAllowed && device.propertyKeys().isEmpty())
                    || new HashSet<>(device.propertyKeys()).size() != device.propertyKeys().size()
                    || device.propertyKeys().stream().anyMatch(key -> !validKey(key))) throw invalid();
            total += device.propertyKeys().size();
        }
        if (total > 200) throw invalid();
    }

    /** 顶层属性沿用设备公开查询语法，包含数字开头与连字符。 */
    private static boolean validKey(String key) { return key != null && key.matches("[A-Za-z0-9_-]{1,64}"); }

    /** 拒绝空、未知或null枚举；合法但未声明的集合另报范围禁止。 */
    private static boolean validEnums(Set<String> values, Set<String> allowed) {
        return values != null && !values.isEmpty() && values.stream().allMatch(value -> value != null && allowed.contains(value));
    }

    /** 设备归属必须同时满足变量冻结模型及候选身份，不能只按模型版本匹配。 */
    private static boolean owns(Variable variable, RuntimeDeviceQuery device) {
        return variable.scope().modelVersionId().equals(device.expectedModelVersionId())
                && variable.scope().deviceIds().contains(device.deviceId());
    }

    /** 保留所有原binding，允许同一设备确实同时被两个变量授权的不同键。 */
    private static List<Binding> matching(Plan plan, RuntimeDeviceQuery device) {
        return plan.bindings().stream().filter(binding -> owns(binding.variable(), device)).toList();
    }

    /** 只解析已完整验签复算的Schema；持久损坏属于内部依赖故障而非客户端400。 */
    private static Plan parse(DashboardShareReadContext context) {
        JsonNode schema = context.version().schema();
        Map<String, RuntimeModelReference> models = new HashMap<>();
        for (JsonNode node : schema.path("models")) {
            models.put(text(node, "key"), new RuntimeModelReference(UUID.fromString(text(node, "versionId")),
                    text(node, "digestAlgorithm"), text(node, "digest"), text(node, "profile")));
        }
        Map<String, DashboardShareVariableScope> scopes = new HashMap<>();
        for (DashboardShareVariableScope scope : context.scopes()) {
            if (scopes.put(scope.variableKey(), scope) != null) throw corrupt();
        }
        Map<String, Variable> variables = new HashMap<>();
        Map<String, Set<String>> presets = new HashMap<>();
        for (JsonNode node : schema.path("variables")) {
            String key = text(node, "key");
            String type = text(node, "type");
            if (Set.of("DEVICE_SINGLE", "DEVICE_MULTI").contains(type)) {
                RuntimeModelReference model = models.get(text(node, "modelKey"));
                DashboardShareVariableScope scope = scopes.get(key);
                int maximum = "DEVICE_SINGLE".equals(type) ? 1 : node.path("maxItems").asInt();
                if (model == null || scope == null || !model.versionId().equals(scope.modelVersionId())
                        || maximum < 1 || maximum > 20) throw corrupt();
                variables.put(key, new Variable(model, scope, maximum));
            } else if ("TIME_RANGE".equals(type)) {
                presets.put(key, strings(node.path("allowedPresets")));
            }
        }
        if (!variables.keySet().equals(scopes.keySet())) throw corrupt();
        List<Binding> bindings = new ArrayList<>();
        for (JsonNode page : schema.path("pages")) {
            for (JsonNode component : page.path("components")) collect(component.path("bindings"), variables, bindings);
        }
        return new Plan(variables, presets, bindings);
    }

    /** 遍历封闭binding结构，变量引用对象自身不会误识别为第二条读取声明。 */
    private static void collect(JsonNode node, Map<String, Variable> variables, List<Binding> bindings) {
        if (node.isArray()) {
            for (JsonNode child : node) collect(child, variables, bindings);
        } else if (node.isObject()) {
            if (node.has("source")) {
                String source = text(node, "source");
                String variable = switch (source) {
                    case "DEVICE_DIRECTORY" -> text(node, "variableKey");
                    case "ALARM_LIST" -> text(node.path("devices"), "variableKey");
                    case "CURRENT_VALUE", "HISTORY_SERIES", "DEVICE_STATUS" -> text(node.path("device"), "variableKey");
                    case "ENUM_TEXT" -> null;
                    default -> throw corrupt();
                };
                if (variable != null) {
                    Variable declared = variables.get(variable);
                    if (declared == null) throw corrupt();
                    bindings.add(new Binding(source, declared, node));
                }
            }
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (!Set.of("device", "devices").contains(field.getKey())) collect(field.getValue(), variables, bindings);
            }
        }
    }

    /** 规范Schema中的必填文本缺失意味着上游完整性守卫失效。 */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.asString().isBlank()) throw corrupt();
        return value.asString();
    }

    /** 冻结Schema枚举数组，集合等值防止告警过滤子集与混拼绕过。 */
    private static Set<String> strings(JsonNode node) {
        if (!node.isArray()) throw corrupt();
        Set<String> result = new HashSet<>();
        for (JsonNode value : node) {
            if (!value.isString() || !result.add(value.asString())) throw corrupt();
        }
        return Set.copyOf(result);
    }

    /** 参数预算与格式错误不改变能力状态。 */
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
    /** 合法语法仍不得探测能力外的设备、模型或查询。 */
    private static BusinessException forbidden() { return new BusinessException(DashboardErrorCode.SHARE_SCOPE_FORBIDDEN); }
    /** 持久完整性异常由服务编排映射为60055并保留首因。 */
    private static IllegalStateException corrupt() { return new IllegalStateException("分享Schema与冻结变量范围无法建立查询计划"); }

    /** 单变量模型及候选作为不可拆分的授权单元。 */
    private record Variable(RuntimeModelReference model, DashboardShareVariableScope scope, int maximum) { }
    /** 原始binding保留时间与三个告警过滤字段，绝不先按模型合并。 */
    private record Binding(String source, Variable variable, JsonNode node) { }
    /** 单次只读事务内构造的查询计划，不缓存跨请求授权结果。 */
    private record Plan(Map<String, Variable> variables, Map<String, Set<String>> presets, List<Binding> bindings) { }
}
