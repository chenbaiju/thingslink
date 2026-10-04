package com.things.link.dashboard.application.schema;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 四类看板变量的包内校验器与不可变索引，供binding及后续slot规则共享。 */
final class DashboardSchemaVariableCatalog {
    /** TIME_RANGE允许的预设闭集。 */
    private static final Set<String> TIME_PRESETS = Set.of("LAST_1_HOUR", "LAST_24_HOURS", "LAST_7_DAYS");
    /** TIME_RANGE默认预设的稳定数组顺序。 */
    private static final List<String> DEFAULT_TIME_PRESETS = List.of("LAST_1_HOUR", "LAST_24_HOURS", "LAST_7_DAYS");
    /** 按声明key索引的不可变变量定义。 */
    private final Map<String, VariableDefinition> definitions;

    /**
     * 创建不可变变量索引。
     *
     * @param definitions 已校验变量定义
     */
    private DashboardSchemaVariableCatalog(Map<String, VariableDefinition> definitions) {
        this.definitions = Map.copyOf(definitions);
    }

    /**
     * 校验完整变量数组、注入本范围默认值并建立支持前向binding引用的索引。
     *
     * @param variables 变量数组
     * @param modelKeys 已在根模型数组完成校验的key集合
     * @return 不可变变量索引
     */
    static DashboardSchemaVariableCatalog validateAndNormalize(ArrayNode variables, Set<String> modelKeys) {
        requireSize(variables, "$.variables", 0, 20);
        Map<String, VariableDefinition> result = new LinkedHashMap<>();
        for (int index = 0; index < variables.size(); index++) {
            String path = "$.variables[" + index + "]";
            ObjectNode variable = DashboardSchemaStructureRules.requireObject(variables.get(index), path);
            String key = DashboardSchemaValueRules.localKey(required(variable, "key", path), path + ".key");
            if (modelKeys.contains(key)) throw invalidCollection(path + ".key", "变量key不得与model key混用命名空间");
            VariableType type = enumValue(VariableType.class,
                    requireString(variable, "type", path), path + ".type");
            DashboardSchemaValueRules.title(required(variable, "title", path), path + ".title");
            DashboardSchemaStructureRules.putDefault(variable, "required", true);
            DashboardSchemaStructureRules.requireBoolean(variable, "required", path);
            VariableDefinition definition = switch (type) {
                case DEVICE_SINGLE -> validateSingleDevice(variable, path, key, modelKeys);
                case DEVICE_MULTI -> validateMultiDevice(variable, path, key, modelKeys);
                case TIME_RANGE -> validateTimeRange(variable, path, key);
                case TEXT_ENUM -> validateTextEnum(variable, path, key);
            };
            if (result.putIfAbsent(key, definition) != null) {
                throw invalidCollection(path + ".key", "变量key必须全文唯一");
            }
        }
        return new DashboardSchemaVariableCatalog(result);
    }

    /**
     * 返回指定key的变量定义。
     *
     * @param key 变量key
     * @return 变量定义，不存在时为null
     */
    VariableDefinition definition(String key) {
        return definitions.get(key);
    }

    /** 校验单设备变量封闭字段及模型引用。 */
    private static VariableDefinition validateSingleDevice(
            ObjectNode variable, String path, String key, Set<String> modelKeys) {
        DashboardSchemaStructureRules.requireObject(variable, path,
                Set.of("key", "type", "title", "required", "modelKey", "defaultDeviceId"));
        String modelKey = requireModelKey(variable, path, modelKeys);
        if (variable.has("defaultDeviceId")) {
            DashboardSchemaValueRules.uuid(variable.get("defaultDeviceId"), path + ".defaultDeviceId");
        }
        return new VariableDefinition(key, VariableType.DEVICE_SINGLE, modelKey);
    }

    /** 校验多设备变量、数量默认及不重复默认设备集合。 */
    private static VariableDefinition validateMultiDevice(
            ObjectNode variable, String path, String key, Set<String> modelKeys) {
        DashboardSchemaStructureRules.requireObject(variable, path,
                Set.of("key", "type", "title", "required", "modelKey", "maxItems", "defaultDeviceIds"));
        String modelKey = requireModelKey(variable, path, modelKeys);
        DashboardSchemaStructureRules.putDefault(variable, "maxItems", 20);
        int maxItems = requireInteger(variable, "maxItems", path, 1, 20);
        DashboardSchemaStructureRules.defaultArray(variable, "defaultDeviceIds");
        ArrayNode defaults = DashboardSchemaStructureRules.requireArray(
                variable.get("defaultDeviceIds"), path + ".defaultDeviceIds");
        requireSize(defaults, path + ".defaultDeviceIds", 0, maxItems);
        Set<String> unique = new HashSet<>();
        for (int index = 0; index < defaults.size(); index++) {
            String deviceId = DashboardSchemaValueRules.uuid(
                    defaults.get(index), path + ".defaultDeviceIds[" + index + "]");
            if (!unique.add(deviceId)) throw invalidCollection(path + ".defaultDeviceIds", "默认设备不得重复");
        }
        return new VariableDefinition(key, VariableType.DEVICE_MULTI, modelKey);
    }

    /** 校验时间范围变量及预设默认值。 */
    private static VariableDefinition validateTimeRange(ObjectNode variable, String path, String key) {
        DashboardSchemaStructureRules.requireObject(variable, path,
                Set.of("key", "type", "title", "required", "defaultPreset", "allowedPresets"));
        DashboardSchemaStructureRules.putDefault(variable, "defaultPreset", "LAST_1_HOUR");
        String defaultPreset = requireEnum(variable.get("defaultPreset"), path + ".defaultPreset", TIME_PRESETS);
        if (!variable.has("allowedPresets")) {
            ArrayNode defaults = variable.arrayNode();
            DEFAULT_TIME_PRESETS.forEach(defaults::add);
            variable.set("allowedPresets", defaults);
        }
        Set<String> allowed = requireUniqueEnums(DashboardSchemaStructureRules.requireArray(
                variable.get("allowedPresets"), path + ".allowedPresets"), path + ".allowedPresets", TIME_PRESETS);
        if (!allowed.contains(defaultPreset)) {
            throw invalidReference(path + ".defaultPreset", "必须包含在allowedPresets中");
        }
        return new VariableDefinition(key, VariableType.TIME_RANGE, null);
    }

    /** 校验文本枚举变量、选项唯一性及默认引用。 */
    private static VariableDefinition validateTextEnum(ObjectNode variable, String path, String key) {
        DashboardSchemaStructureRules.requireObject(variable, path,
                Set.of("key", "type", "title", "required", "options", "defaultValue"));
        ArrayNode options = DashboardSchemaStructureRules.requireArray(
                required(variable, "options", path), path + ".options");
        requireSize(options, path + ".options", 1, 20);
        Set<String> values = new HashSet<>();
        for (int index = 0; index < options.size(); index++) {
            String optionPath = path + ".options[" + index + "]";
            ObjectNode option = DashboardSchemaStructureRules.requireObject(
                    options.get(index), optionPath, Set.of("value", "label"));
            String value = DashboardSchemaValueRules.localKey(required(option, "value", optionPath), optionPath + ".value");
            DashboardSchemaValueRules.title(required(option, "label", optionPath), optionPath + ".label");
            if (!values.add(value)) throw invalidCollection(path + ".options", "option value不得重复");
        }
        if (variable.has("defaultValue")) {
            String defaultValue = DashboardSchemaValueRules.localKey(
                    variable.get("defaultValue"), path + ".defaultValue");
            if (!values.contains(defaultValue)) {
                throw invalidReference(path + ".defaultValue", "必须引用已声明option value");
            }
        }
        return new VariableDefinition(key, VariableType.TEXT_ENUM, null);
    }

    /** 要求设备变量引用已声明模型key。 */
    private static String requireModelKey(ObjectNode variable, String path, Set<String> modelKeys) {
        String modelKey = DashboardSchemaValueRules.localKey(required(variable, "modelKey", path), path + ".modelKey");
        if (!modelKeys.contains(modelKey)) throw invalidReference(path + ".modelKey", "引用的model key不存在");
        return modelKey;
    }

    /** 返回必填字段。 */
    private static JsonNode required(ObjectNode node, String name, String path) {
        return DashboardSchemaStructureRules.required(node, name, path);
    }

    /** 要求必填字符串字段。 */
    private static String requireString(ObjectNode node, String name, String path) {
        return DashboardSchemaStructureRules.requireString(node, name, path);
    }

    /** 要求JSON整数处于闭区间。 */
    private static int requireInteger(ObjectNode node, String name, String path, int minimum, int maximum) {
        long value = DashboardSchemaStructureRules.requireInteger(node, name, path);
        if (value < minimum || value > maximum) throw invalidValue(path + "." + name, "整数超出允许范围");
        return (int) value;
    }

    /** 要求数组数量处于闭区间。 */
    private static void requireSize(ArrayNode node, String path, int minimum, int maximum) {
        if (node.size() < minimum || node.size() > maximum) {
            throw invalidCollection(path, "数量必须在" + minimum + "至" + maximum + "之间");
        }
    }

    /** 校验非空、不重复字符串枚举数组。 */
    private static Set<String> requireUniqueEnums(ArrayNode node, String path, Set<String> allowed) {
        requireSize(node, path, 1, allowed.size());
        Set<String> result = new HashSet<>();
        for (int index = 0; index < node.size(); index++) {
            String value = requireEnum(node.get(index), path + "[" + index + "]", allowed);
            if (!result.add(value)) throw invalidCollection(path, "枚举值不得重复");
        }
        return result;
    }

    /** 要求字符串枚举属于闭集。 */
    private static String requireEnum(JsonNode node, String path, Set<String> allowed) {
        if (!node.isString()) throw typeMismatch(path, "必须是字符串");
        if (!allowed.contains(node.asString())) throw invalidValue(path, "枚举值不受支持");
        return node.asString();
    }

    /** 将字符串转换为已登记枚举。 */
    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String path) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw invalidValue(path, "枚举值不受支持");
        }
    }

    /** 创建类型错误。 */
    private static DashboardSchemaValidationException typeMismatch(String path, String detail) {
        return reject(DashboardSchemaValidationException.Reason.TYPE_MISMATCH, path, detail);
    }

    /** 创建普通值错误。 */
    private static DashboardSchemaValidationException invalidValue(String path, String detail) {
        return reject(DashboardSchemaValidationException.Reason.INVALID_VALUE, path, detail);
    }

    /** 创建集合错误。 */
    private static DashboardSchemaValidationException invalidCollection(String path, String detail) {
        return reject(DashboardSchemaValidationException.Reason.INVALID_COLLECTION, path, detail);
    }

    /** 创建内部引用错误。 */
    private static DashboardSchemaValidationException invalidReference(String path, String detail) {
        return reject(DashboardSchemaValidationException.Reason.INVALID_REFERENCE, path, detail);
    }

    /** 创建稳定原因异常。 */
    private static DashboardSchemaValidationException reject(
            DashboardSchemaValidationException.Reason reason, String path, String detail) {
        return new DashboardSchemaValidationException(reason, path, detail);
    }

    /** 四种变量判别类型。 */
    enum VariableType {
        /** 单设备变量。 */ DEVICE_SINGLE,
        /** 多设备变量。 */ DEVICE_MULTI,
        /** 时间范围变量。 */ TIME_RANGE,
        /** 文本枚举变量。 */ TEXT_ENUM
    }

    /**
     * binding和后续slot校验所需的最小变量定义。
     *
     * @param key 变量key
     * @param type 变量判别类型
     * @param modelKey 设备变量的模型key，其他类型为null
     */
    record VariableDefinition(String key, VariableType type, String modelKey) { }
}
