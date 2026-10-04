package com.things.link.dashboard.application.schema;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Set;

/** 六种看板binding精确分支与内部变量引用规则，供后续组件slot校验复用。 */
final class DashboardSchemaBindingRules {
    /** 工具类不允许实例化。 */
    private DashboardSchemaBindingRules() {
    }

    /**
     * 校验一个精确binding分支并返回供组件slot继续判定的内部投影。
     *
     * @param binding binding对象
     * @param path 稳定JSON路径
     * @param variables 变量目录
     * @return 已解析binding投影
     */
    static BindingDefinition validate(
            ObjectNode binding, String path, DashboardSchemaVariableCatalog variables) {
        BindingSource source = enumValue(BindingSource.class,
                requireString(binding, "source", path), path + ".source");
        return switch (source) {
            case CURRENT_VALUE -> validateCurrentValue(binding, path, variables);
            case HISTORY_SERIES -> validateHistorySeries(binding, path, variables);
            case DEVICE_STATUS -> validateDeviceStatus(binding, path, variables);
            case ALARM_LIST -> validateAlarmList(binding, path, variables);
            case DEVICE_DIRECTORY -> validateDeviceDirectory(binding, path, variables);
            case ENUM_TEXT -> validateEnumText(binding, path, variables);
        };
    }

    /** 校验CURRENT_VALUE闭集、设备引用及顶层属性键。 */
    private static BindingDefinition validateCurrentValue(
            ObjectNode binding, String path, DashboardSchemaVariableCatalog variables) {
        requireOnly(binding, path, Set.of("source", "device", "propertyKey"));
        DashboardSchemaVariableCatalog.VariableDefinition device = requireDeviceReference(
                required(binding, "device", path), path + ".device",
                Set.of(DashboardSchemaVariableCatalog.VariableType.DEVICE_SINGLE,
                        DashboardSchemaVariableCatalog.VariableType.DEVICE_MULTI), variables);
        String propertyKey = DashboardSchemaValueRules.propertyKey(
                required(binding, "propertyKey", path), path + ".propertyKey");
        return definition(BindingSource.CURRENT_VALUE, device, propertyKey, "");
    }

    /** 校验HISTORY_SERIES闭集、单设备、时间变量和两个枚举。 */
    private static BindingDefinition validateHistorySeries(
            ObjectNode binding, String path, DashboardSchemaVariableCatalog variables) {
        requireOnly(binding, path,
                Set.of("source", "device", "propertyKey", "timeRangeVariableKey", "granularity", "aggregation"));
        DashboardSchemaVariableCatalog.VariableDefinition device = requireDeviceReference(
                required(binding, "device", path), path + ".device",
                Set.of(DashboardSchemaVariableCatalog.VariableType.DEVICE_SINGLE), variables);
        String propertyKey = DashboardSchemaValueRules.propertyKey(
                required(binding, "propertyKey", path), path + ".propertyKey");
        String timeRangeKey = requireLocalKey(binding, "timeRangeVariableKey", path);
        requireVariable(timeRangeKey, path + ".timeRangeVariableKey",
                Set.of(DashboardSchemaVariableCatalog.VariableType.TIME_RANGE), variables);
        String granularity = requireEnum(required(binding, "granularity", path), path + ".granularity",
                Set.of("RAW", "ONE_MINUTE", "ONE_HOUR", "ONE_DAY"));
        String aggregation = requireEnum(required(binding, "aggregation", path), path + ".aggregation",
                Set.of("AVG", "MIN", "MAX", "SUM", "COUNT"));
        return definition(BindingSource.HISTORY_SERIES, device, propertyKey, timeRangeKey, granularity, aggregation);
    }

    /** 校验DEVICE_STATUS只能引用单设备变量。 */
    private static BindingDefinition validateDeviceStatus(
            ObjectNode binding, String path, DashboardSchemaVariableCatalog variables) {
        requireOnly(binding, path, Set.of("source", "device"));
        DashboardSchemaVariableCatalog.VariableDefinition device = requireDeviceReference(
                required(binding, "device", path), path + ".device",
                Set.of(DashboardSchemaVariableCatalog.VariableType.DEVICE_SINGLE), variables);
        return definition(BindingSource.DEVICE_STATUS, device, "", "");
    }

    /** 校验ALARM_LIST设备引用及三个正交非空枚举数组。 */
    private static BindingDefinition validateAlarmList(
            ObjectNode binding, String path, DashboardSchemaVariableCatalog variables) {
        requireOnly(binding, path, Set.of("source", "devices", "conditionStates", "ackStates", "severities"));
        DashboardSchemaVariableCatalog.VariableDefinition device = requireDeviceReference(
                required(binding, "devices", path), path + ".devices",
                Set.of(DashboardSchemaVariableCatalog.VariableType.DEVICE_SINGLE,
                        DashboardSchemaVariableCatalog.VariableType.DEVICE_MULTI), variables);
        requireUniqueEnums(requireArray(binding, "conditionStates", path), path + ".conditionStates",
                Set.of("PENDING", "ACTIVE", "CLEARED"));
        requireUniqueEnums(requireArray(binding, "ackStates", path), path + ".ackStates",
                Set.of("UNACKNOWLEDGED", "ACKNOWLEDGED"));
        requireUniqueEnums(requireArray(binding, "severities", path), path + ".severities",
                Set.of("CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO"));
        return definition(BindingSource.ALARM_LIST, device, "", "");
    }

    /** 校验DEVICE_DIRECTORY只能引用单设备或多设备变量。 */
    private static BindingDefinition validateDeviceDirectory(
            ObjectNode binding, String path, DashboardSchemaVariableCatalog variables) {
        requireOnly(binding, path, Set.of("source", "variableKey"));
        DashboardSchemaVariableCatalog.VariableDefinition variable = requireVariable(
                requireLocalKey(binding, "variableKey", path), path + ".variableKey",
                Set.of(DashboardSchemaVariableCatalog.VariableType.DEVICE_SINGLE,
                        DashboardSchemaVariableCatalog.VariableType.DEVICE_MULTI), variables);
        return definition(BindingSource.DEVICE_DIRECTORY, variable, "", "");
    }

    /** 校验ENUM_TEXT只能引用TEXT_ENUM变量。 */
    private static BindingDefinition validateEnumText(
            ObjectNode binding, String path, DashboardSchemaVariableCatalog variables) {
        requireOnly(binding, path, Set.of("source", "variableKey"));
        DashboardSchemaVariableCatalog.VariableDefinition variable = requireVariable(
                requireLocalKey(binding, "variableKey", path), path + ".variableKey",
                Set.of(DashboardSchemaVariableCatalog.VariableType.TEXT_ENUM), variables);
        return definition(BindingSource.ENUM_TEXT, variable, "", "");
    }

    /** 校验封闭DeviceReference并解析变量。 */
    private static DashboardSchemaVariableCatalog.VariableDefinition requireDeviceReference(
            JsonNode node, String path, Set<DashboardSchemaVariableCatalog.VariableType> allowedTypes,
            DashboardSchemaVariableCatalog variables) {
        ObjectNode reference = DashboardSchemaStructureRules.requireObject(node, path, Set.of("variableKey"));
        return requireVariable(requireLocalKey(reference, "variableKey", path),
                path + ".variableKey", allowedTypes, variables);
    }

    /** 要求变量存在且判别类型适用于当前binding。 */
    private static DashboardSchemaVariableCatalog.VariableDefinition requireVariable(
            String key, String path, Set<DashboardSchemaVariableCatalog.VariableType> allowedTypes,
            DashboardSchemaVariableCatalog variables) {
        DashboardSchemaVariableCatalog.VariableDefinition definition = variables.definition(key);
        if (definition == null || !allowedTypes.contains(definition.type())) {
            throw invalidReference(path, "变量不存在或类型不符合binding");
        }
        return definition;
    }

    /** 要求对象字段为数组。 */
    private static ArrayNode requireArray(ObjectNode node, String name, String path) {
        return DashboardSchemaStructureRules.requireArray(required(node, name, path), path + "." + name);
    }

    /** 返回必填字段。 */
    private static JsonNode required(ObjectNode node, String name, String path) {
        return DashboardSchemaStructureRules.required(node, name, path);
    }

    /** 要求必填字符串字段。 */
    private static String requireString(ObjectNode node, String name, String path) {
        return DashboardSchemaStructureRules.requireString(node, name, path);
    }

    /** 要求必填字段符合内部LocalKey语法。 */
    private static String requireLocalKey(ObjectNode node, String name, String path) {
        return DashboardSchemaValueRules.localKey(required(node, name, path), path + "." + name);
    }

    /** 拒绝binding分支未知字段。 */
    private static void requireOnly(ObjectNode node, String path, Set<String> allowedFields) {
        DashboardSchemaStructureRules.requireObject(node, path, allowedFields);
    }

    /** 校验非空、不重复字符串枚举数组。 */
    private static void requireUniqueEnums(ArrayNode node, String path, Set<String> allowed) {
        if (node.isEmpty() || node.size() > allowed.size()) {
            throw invalidCollection(path, "枚举数组必须非空且不超过允许值数量");
        }
        Set<String> result = new HashSet<>();
        for (int index = 0; index < node.size(); index++) {
            String value = requireEnum(node.get(index), path + "[" + index + "]", allowed);
            if (!result.add(value)) throw invalidCollection(path, "枚举值不得重复");
        }
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

    /** 创建binding结果投影。 */
    private static BindingDefinition definition(
            BindingSource source, DashboardSchemaVariableCatalog.VariableDefinition variable,
            String propertyKey, String timeRangeVariableKey) {
        return definition(source, variable, propertyKey, timeRangeVariableKey, "", "");
    }

    /** 创建包含历史粒度与聚合的binding结果投影。 */
    private static BindingDefinition definition(
            BindingSource source, DashboardSchemaVariableCatalog.VariableDefinition variable,
            String propertyKey, String timeRangeVariableKey, String granularity, String aggregation) {
        return new BindingDefinition(source, variable.key(), variable.type(), variable.modelKey(),
                propertyKey, timeRangeVariableKey, granularity, aggregation);
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

    /** 六种冻结binding source。 */
    enum BindingSource {
        /** 当前属性值。 */ CURRENT_VALUE,
        /** 历史属性序列。 */ HISTORY_SERIES,
        /** 设备状态。 */ DEVICE_STATUS,
        /** 告警列表。 */ ALARM_LIST,
        /** 设备目录。 */ DEVICE_DIRECTORY,
        /** 文本枚举标签。 */ ENUM_TEXT
    }

    /**
     * 供后续组件slot检查source、变量基数和外部属性事实的内部投影。
     *
     * @param source binding source
     * @param variableKey 主设备或文本变量key
     * @param variableType 已解析变量类型
     * @param modelKey 设备变量引用的模型key，非设备变量为空
     * @param propertyKey 顶层属性key，无属性binding为空
     * @param timeRangeVariableKey 历史时间变量key，其他binding为空
     * @param granularity 历史粒度，其他binding为空
     * @param aggregation 历史聚合，其他binding为空
     */
    record BindingDefinition(BindingSource source, String variableKey,
                             DashboardSchemaVariableCatalog.VariableType variableType,
                             String modelKey, String propertyKey, String timeRangeVariableKey,
                             String granularity, String aggregation) { }
}
