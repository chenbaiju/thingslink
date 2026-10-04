package com.things.link.dashboard.application.schema;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 十种组件的精确props、slot、默认化、组合语义和外部需求规则。 */
final class DashboardComponentSemanticRules {
    /** 当前合同已经接入完整Schema管线的全部组件kind。 */
    private static final Set<DashboardComponentDescriptorRegistry.ComponentKind> IMPLEMENTED_KINDS = Set.of(
            DashboardComponentDescriptorRegistry.ComponentKind.TEXT,
            DashboardComponentDescriptorRegistry.ComponentKind.IMAGE,
            DashboardComponentDescriptorRegistry.ComponentKind.VALUE_CARD,
            DashboardComponentDescriptorRegistry.ComponentKind.STATUS,
            DashboardComponentDescriptorRegistry.ComponentKind.GAUGE,
            DashboardComponentDescriptorRegistry.ComponentKind.LINE_CHART,
            DashboardComponentDescriptorRegistry.ComponentKind.TABLE,
            DashboardComponentDescriptorRegistry.ComponentKind.JSON_VIEW,
            DashboardComponentDescriptorRegistry.ComponentKind.ALARM_LIST,
            DashboardComponentDescriptorRegistry.ComponentKind.DEVICE_SELECTOR);

    /** 规则类不允许实例化。 */
    private DashboardComponentSemanticRules() {
    }

    /**
     * 判断当前kind是否属于已实现的十组件闭集。
     *
     * @param kind 组件kind文本
     * @return 已实现时为true
     */
    static boolean supports(String kind) {
        try {
            return IMPLEMENTED_KINDS.contains(DashboardComponentDescriptorRegistry.ComponentKind.valueOf(kind));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 校验并规范化一个已实现组件，返回尚待外部端口证明的事实需求。
     *
     * @param component 完整组件对象
     * @param path 稳定JSON路径
     * @param variables 已完成内部引用校验的变量目录
     * @return 不可变外部需求列表
     */
    static List<DashboardSchemaExternalRequirement> validateAndNormalize(
            ObjectNode component, String path, DashboardSchemaVariableCatalog variables) {
        DashboardSchemaStructureRules.rejectNulls(component, path);
        String kindText = DashboardSchemaStructureRules.requireString(component, "kind", path);
        String version = DashboardSchemaStructureRules.requireString(component, "componentVersion", path);
        DashboardComponentDescriptorRegistry.ComponentDescriptor descriptor =
                DashboardComponentDescriptorRegistry.require(kindText, version, path);
        if (!IMPLEMENTED_KINDS.contains(descriptor.kind())) {
            throw new IllegalStateException("组件描述符与语义分支登记不一致");
        }
        ObjectNode props = DashboardSchemaStructureRules.requireObject(
                DashboardSchemaStructureRules.required(component, "props", path), path + ".props");
        ObjectNode bindings = DashboardSchemaStructureRules.requireObject(
                DashboardSchemaStructureRules.required(component, "bindings", path), path + ".bindings");
        List<DashboardSchemaExternalRequirement> requirements = new ArrayList<>();
        requirements.add(new HostComponentRequirement(
                descriptor.kind(), descriptor.componentVersion(), descriptor.hostCompatibility()));
        switch (descriptor.kind()) {
            case TEXT -> validateText(props, bindings, path, variables);
            case IMAGE -> validateImage(props, bindings, path, requirements);
            case VALUE_CARD -> validateValueCard(props, bindings, path, variables, descriptor, requirements);
            case STATUS -> validateStatus(props, bindings, path, variables, requirements);
            case GAUGE -> validateGauge(props, bindings, path, variables, descriptor, requirements);
            case LINE_CHART -> validateLineChart(props, bindings, path, variables, requirements);
            case TABLE -> validateTable(props, bindings, path, variables, requirements);
            case JSON_VIEW -> validateJsonView(props, bindings, path, variables, descriptor, requirements);
            case ALARM_LIST -> validateAlarmList(props, bindings, path, variables, requirements);
            case DEVICE_SELECTOR -> validateDeviceSelector(props, bindings, path, variables, requirements);
            default -> throw new IllegalStateException("组件描述符与语义分支登记不一致");
        }
        return List.copyOf(requirements);
    }

    /** 校验TEXT静态或动态文本的互斥来源及唯一默认值。 */
    private static void validateText(
            ObjectNode props, ObjectNode bindings, String componentPath,
            DashboardSchemaVariableCatalog variables) {
        String propsPath = componentPath + ".props";
        String bindingsPath = componentPath + ".bindings";
        DashboardSchemaStructureRules.requireObject(
                props, propsPath, Set.of("content", "align", "size", "tone"));
        DashboardSchemaStructureRules.requireObject(bindings, bindingsPath, Set.of("text"));
        boolean dynamic = bindings.has("text");
        if (dynamic && props.has("content")) {
            throw invalidValue(componentPath, "TEXT不得同时定义content与text slot");
        }
        if (dynamic) {
            DashboardSchemaBindingRules.BindingDefinition binding = validateBinding(
                    bindings, "text", bindingsPath, variables,
                    DashboardSchemaBindingRules.BindingSource.ENUM_TEXT);
            if (binding.variableType() != DashboardSchemaVariableCatalog.VariableType.TEXT_ENUM) {
                throw invalidReference(bindingsPath + ".text", "TEXT动态slot必须引用TEXT_ENUM变量");
            }
        } else {
            DashboardSchemaStructureRules.putDefault(props, "content", "");
            DashboardSchemaValueRules.textContent(
                    DashboardSchemaStructureRules.required(props, "content", propsPath), propsPath + ".content");
        }
        DashboardSchemaStructureRules.putDefault(props, "align", "LEFT");
        DashboardSchemaStructureRules.putDefault(props, "size", "MEDIUM");
        DashboardSchemaStructureRules.putDefault(props, "tone", "REGULAR");
        requireEnum(props.get("align"), propsPath + ".align", Set.of("LEFT", "CENTER", "RIGHT"));
        requireEnum(props.get("size"), propsPath + ".size", Set.of("SMALL", "MEDIUM", "LARGE"));
        requireEnum(props.get("tone"), propsPath + ".tone", Set.of("REGULAR", "SECONDARY", "PRIMARY"));
    }

    /** 校验IMAGE仅引用内置资源且不接受任何数据slot。 */
    private static void validateImage(
            ObjectNode props, ObjectNode bindings, String componentPath,
            List<DashboardSchemaExternalRequirement> requirements) {
        String propsPath = componentPath + ".props";
        String bindingsPath = componentPath + ".bindings";
        DashboardSchemaStructureRules.requireObject(props, propsPath,
                Set.of("title", "resourceId", "resourceDigest", "alt", "fit"));
        DashboardSchemaStructureRules.requireObject(bindings, bindingsPath, Set.of());
        validateOptionalTitle(props, propsPath);
        String resourceId = DashboardSchemaValueRules.localKey(
                DashboardSchemaStructureRules.required(props, "resourceId", propsPath), propsPath + ".resourceId");
        String digest = DashboardSchemaValueRules.sha256(
                DashboardSchemaStructureRules.required(props, "resourceDigest", propsPath),
                propsPath + ".resourceDigest");
        DashboardSchemaValueRules.shortText(
                DashboardSchemaStructureRules.required(props, "alt", propsPath), propsPath + ".alt");
        DashboardSchemaStructureRules.putDefault(props, "fit", "CONTAIN");
        requireEnum(props.get("fit"), propsPath + ".fit", Set.of("CONTAIN", "COVER"));
        requirements.add(new BuiltinResourceRequirement(resourceId, digest));
    }

    /** 校验VALUE_CARD单设备当前值slot及显示默认值。 */
    private static void validateValueCard(
            ObjectNode props, ObjectNode bindings, String componentPath,
            DashboardSchemaVariableCatalog variables,
            DashboardComponentDescriptorRegistry.ComponentDescriptor descriptor,
            List<DashboardSchemaExternalRequirement> requirements) {
        String propsPath = componentPath + ".props";
        String bindingsPath = componentPath + ".bindings";
        DashboardSchemaStructureRules.requireObject(props, propsPath, Set.of("title", "precision", "unitMode"));
        DashboardSchemaStructureRules.requireObject(bindings, bindingsPath, Set.of("value"));
        validateOptionalTitle(props, propsPath);
        DashboardSchemaStructureRules.putDefault(props, "precision", 2);
        requireInteger(props, "precision", propsPath, 0, 6);
        DashboardSchemaStructureRules.putDefault(props, "unitMode", "MODEL");
        requireEnum(props.get("unitMode"), propsPath + ".unitMode", Set.of("MODEL", "NONE"));
        DashboardSchemaBindingRules.BindingDefinition binding = validateBinding(
                bindings, "value", bindingsPath, variables,
                DashboardSchemaBindingRules.BindingSource.CURRENT_VALUE);
        if (binding.variableType() != DashboardSchemaVariableCatalog.VariableType.DEVICE_SINGLE) {
            throw invalidReference(bindingsPath + ".value", "VALUE_CARD只接受DEVICE_SINGLE变量");
        }
        Set<DashboardComponentDescriptorRegistry.PropertyDataType> allowedTypes =
                descriptor.supportedDataTypes().allowedTypes();
        requirements.add(new ModelPropertyRequirement(binding.modelKey(), binding.propertyKey(), allowedTypes));
        requirements.add(adapterRequirement(binding, AdapterCapability.CURRENT_VALUE));
    }

    /** 校验STATUS单设备状态slot及最后在线时间显示默认值。 */
    private static void validateStatus(
            ObjectNode props, ObjectNode bindings, String componentPath,
            DashboardSchemaVariableCatalog variables,
            List<DashboardSchemaExternalRequirement> requirements) {
        String propsPath = componentPath + ".props";
        String bindingsPath = componentPath + ".bindings";
        DashboardSchemaStructureRules.requireObject(props, propsPath, Set.of("title", "showLastOnlineAt"));
        DashboardSchemaStructureRules.requireObject(bindings, bindingsPath, Set.of("status"));
        validateOptionalTitle(props, propsPath);
        DashboardSchemaStructureRules.putDefault(props, "showLastOnlineAt", true);
        DashboardSchemaStructureRules.requireBoolean(props, "showLastOnlineAt", propsPath);
        DashboardSchemaBindingRules.BindingDefinition binding = validateBinding(
                bindings, "status", bindingsPath, variables,
                DashboardSchemaBindingRules.BindingSource.DEVICE_STATUS);
        if (binding.variableType() != DashboardSchemaVariableCatalog.VariableType.DEVICE_SINGLE) {
            throw invalidReference(bindingsPath + ".status", "STATUS只接受DEVICE_SINGLE变量");
        }
        requirements.add(adapterRequirement(binding, AdapterCapability.DEVICE_STATUS));
    }

    /** 校验GAUGE单设备数值属性、量程判别结构和显示默认值。 */
    private static void validateGauge(
            ObjectNode props, ObjectNode bindings, String componentPath,
            DashboardSchemaVariableCatalog variables,
            DashboardComponentDescriptorRegistry.ComponentDescriptor descriptor,
            List<DashboardSchemaExternalRequirement> requirements) {
        String propsPath = componentPath + ".props";
        String bindingsPath = componentPath + ".bindings";
        DashboardSchemaStructureRules.requireObject(
                props, propsPath, Set.of("title", "scaleMode", "min", "max", "precision", "unitMode"));
        DashboardSchemaStructureRules.requireObject(bindings, bindingsPath, Set.of("value"));
        validateOptionalTitle(props, propsPath);
        DashboardSchemaStructureRules.putDefault(props, "scaleMode", "MODEL");
        String scaleMode = requireEnum(
                props.get("scaleMode"), propsPath + ".scaleMode", Set.of("MODEL", "EXPLICIT"));
        DashboardSchemaStructureRules.putDefault(props, "precision", 2);
        requireInteger(props, "precision", propsPath, 0, 6);
        DashboardSchemaStructureRules.putDefault(props, "unitMode", "MODEL");
        requireEnum(props.get("unitMode"), propsPath + ".unitMode", Set.of("MODEL", "NONE"));
        if ("MODEL".equals(scaleMode)) {
            if (props.has("min") || props.has("max")) {
                throw invalidValue(propsPath, "GAUGE的MODEL量程禁止声明min或max");
            }
        } else {
            JsonNode minimumNode = DashboardSchemaStructureRules.required(props, "min", propsPath);
            JsonNode maximumNode = DashboardSchemaStructureRules.required(props, "max", propsPath);
            if (DashboardSchemaValueRules.configNumber(minimumNode, propsPath + ".min")
                    .compareTo(DashboardSchemaValueRules.configNumber(maximumNode, propsPath + ".max")) >= 0) {
                throw invalidValue(propsPath, "GAUGE显式量程必须满足min小于max");
            }
        }
        DashboardSchemaBindingRules.BindingDefinition binding = validateBinding(
                bindings, "value", bindingsPath, variables,
                DashboardSchemaBindingRules.BindingSource.CURRENT_VALUE);
        requireSingleDevice(binding, bindingsPath + ".value", "GAUGE");
        requirements.add(new ModelPropertyRequirement(
                binding.modelKey(), binding.propertyKey(), descriptor.supportedDataTypes().allowedTypes()));
        if ("MODEL".equals(scaleMode)) {
            requirements.add(new ModelGaugeRangeRequirement(binding.modelKey(), binding.propertyKey()));
        }
        requirements.add(adapterRequirement(binding, AdapterCapability.CURRENT_VALUE));
    }

    /** 校验JSON_VIEW完整顶层复合快照slot和本地展开默认值。 */
    private static void validateJsonView(
            ObjectNode props, ObjectNode bindings, String componentPath,
            DashboardSchemaVariableCatalog variables,
            DashboardComponentDescriptorRegistry.ComponentDescriptor descriptor,
            List<DashboardSchemaExternalRequirement> requirements) {
        String propsPath = componentPath + ".props";
        String bindingsPath = componentPath + ".bindings";
        DashboardSchemaStructureRules.requireObject(props, propsPath, Set.of("title", "initialExpandDepth"));
        DashboardSchemaStructureRules.requireObject(bindings, bindingsPath, Set.of("value"));
        validateOptionalTitle(props, propsPath);
        DashboardSchemaStructureRules.putDefault(props, "initialExpandDepth", 1);
        requireInteger(props, "initialExpandDepth", propsPath, 0, 2);
        DashboardSchemaBindingRules.BindingDefinition binding = validateBinding(
                bindings, "value", bindingsPath, variables,
                DashboardSchemaBindingRules.BindingSource.CURRENT_VALUE);
        requireSingleDevice(binding, bindingsPath + ".value", "JSON_VIEW");
        requirements.add(new ModelPropertyRequirement(
                binding.modelKey(), binding.propertyKey(), descriptor.supportedDataTypes().allowedTypes()));
        requirements.add(adapterRequirement(binding, AdapterCapability.COMPOSITE_SNAPSHOT));
    }

    /** 校验LINE_CHART两侧series一一对应、完整历史绑定唯一及NUMBER历史需求。 */
    private static void validateLineChart(
            ObjectNode props, ObjectNode bindings, String componentPath,
            DashboardSchemaVariableCatalog variables,
            List<DashboardSchemaExternalRequirement> requirements) {
        String propsPath = componentPath + ".props";
        String bindingsPath = componentPath + ".bindings";
        DashboardSchemaStructureRules.requireObject(props, propsPath, Set.of("title", "showLegend", "series"));
        DashboardSchemaStructureRules.requireObject(bindings, bindingsPath, Set.of("series"));
        validateOptionalTitle(props, propsPath);
        DashboardSchemaStructureRules.putDefault(props, "showLegend", true);
        DashboardSchemaStructureRules.requireBoolean(props, "showLegend", propsPath);
        ArrayNode seriesDefinitions = requireArray(props, "series", propsPath);
        ArrayNode seriesBindings = requireArray(bindings, "series", bindingsPath);
        requireSize(seriesDefinitions, propsPath + ".series", 1, 4);
        requireSize(seriesBindings, bindingsPath + ".series", 1, 4);
        if (seriesDefinitions.size() != seriesBindings.size()) {
            throw invalidReference(bindingsPath + ".series", "必须与props.series数量及顺序一一对应");
        }
        Set<String> ids = new HashSet<>();
        Set<DashboardSchemaBindingRules.BindingDefinition> completeBindings = new HashSet<>();
        for (int index = 0; index < seriesDefinitions.size(); index++) {
            String definitionPath = propsPath + ".series[" + index + "]";
            ObjectNode definition = DashboardSchemaStructureRules.requireObject(
                    seriesDefinitions.get(index), definitionPath, Set.of("id", "label"));
            String id = DashboardSchemaValueRules.localKey(
                    DashboardSchemaStructureRules.required(definition, "id", definitionPath), definitionPath + ".id");
            DashboardSchemaValueRules.title(
                    DashboardSchemaStructureRules.required(definition, "label", definitionPath),
                    definitionPath + ".label");
            if (!ids.add(id)) throw invalidCollection(propsPath + ".series", "series id不得重复");

            String bindingPath = bindingsPath + ".series[" + index + "]";
            ObjectNode entry = DashboardSchemaStructureRules.requireObject(
                    seriesBindings.get(index), bindingPath, Set.of("id", "value"));
            String bindingId = DashboardSchemaValueRules.localKey(
                    DashboardSchemaStructureRules.required(entry, "id", bindingPath), bindingPath + ".id");
            if (!id.equals(bindingId)) {
                throw invalidReference(bindingPath + ".id", "必须与同序props.series id一致");
            }
            DashboardSchemaBindingRules.BindingDefinition binding = validateBinding(
                    entry, "value", bindingPath, variables,
                    DashboardSchemaBindingRules.BindingSource.HISTORY_SERIES);
            requireSingleDevice(binding, bindingPath + ".value", "LINE_CHART");
            if (!completeBindings.add(binding)) {
                throw invalidCollection(bindingsPath + ".series", "相同完整历史数据绑定不得重复");
            }
            requirements.add(new HistoricalPropertyRequirement(
                    binding.variableKey(), binding.modelKey(), binding.propertyKey(),
                    binding.timeRangeVariableKey(), binding.granularity(), binding.aggregation()));
            requirements.add(adapterRequirement(binding, AdapterCapability.HISTORY_SERIES));
        }
    }

    /** 校验TABLE模式判别、LIST单值或多设备列的一一对应规则。 */
    private static void validateTable(
            ObjectNode props, ObjectNode bindings, String componentPath,
            DashboardSchemaVariableCatalog variables,
            List<DashboardSchemaExternalRequirement> requirements) {
        String propsPath = componentPath + ".props";
        String bindingsPath = componentPath + ".bindings";
        DashboardSchemaStructureRules.requireObject(props, propsPath, Set.of("title", "mode", "rowLimit", "columns"));
        validateOptionalTitle(props, propsPath);
        String mode = requireEnum(
                DashboardSchemaStructureRules.required(props, "mode", propsPath),
                propsPath + ".mode", Set.of("LIST_VALUE", "DEVICE_VALUES"));
        DashboardSchemaStructureRules.putDefault(props, "rowLimit", 20);
        requireInteger(props, "rowLimit", propsPath, 1, 256);
        if ("LIST_VALUE".equals(mode)) {
            validateListValueTable(props, bindings, propsPath, bindingsPath, variables, requirements);
        } else {
            validateDeviceValuesTable(props, bindings, propsPath, bindingsPath, variables, requirements);
        }
    }

    /** 校验TABLE LIST_VALUE只消费单设备顶层LIST完整值。 */
    private static void validateListValueTable(
            ObjectNode props, ObjectNode bindings, String propsPath, String bindingsPath,
            DashboardSchemaVariableCatalog variables,
            List<DashboardSchemaExternalRequirement> requirements) {
        if (props.has("columns")) throw invalidValue(propsPath, "LIST_VALUE禁止声明columns");
        DashboardSchemaStructureRules.requireObject(bindings, bindingsPath, Set.of("value"));
        DashboardSchemaBindingRules.BindingDefinition binding = validateBinding(
                bindings, "value", bindingsPath, variables,
                DashboardSchemaBindingRules.BindingSource.CURRENT_VALUE);
        requireSingleDevice(binding, bindingsPath + ".value", "TABLE LIST_VALUE");
        requirements.add(new ModelPropertyRequirement(
                binding.modelKey(), binding.propertyKey(),
                Set.of(DashboardComponentDescriptorRegistry.PropertyDataType.LIST)));
        requirements.add(adapterRequirement(binding, AdapterCapability.LIST_SNAPSHOT));
    }

    /** 校验TABLE DEVICE_VALUES两侧列同序、同id且所有列共用一个多设备变量。 */
    private static void validateDeviceValuesTable(
            ObjectNode props, ObjectNode bindings, String propsPath, String bindingsPath,
            DashboardSchemaVariableCatalog variables,
            List<DashboardSchemaExternalRequirement> requirements) {
        DashboardSchemaStructureRules.requireObject(bindings, bindingsPath, Set.of("columns"));
        ArrayNode columnDefinitions = requireArray(props, "columns", propsPath);
        ArrayNode columnBindings = requireArray(bindings, "columns", bindingsPath);
        requireSize(columnDefinitions, propsPath + ".columns", 1, 10);
        requireSize(columnBindings, bindingsPath + ".columns", 1, 10);
        if (columnDefinitions.size() != columnBindings.size()) {
            throw invalidReference(bindingsPath + ".columns", "必须与props.columns数量及顺序一一对应");
        }
        Set<String> ids = new HashSet<>();
        String sharedVariableKey = null;
        Set<DashboardComponentDescriptorRegistry.PropertyDataType> scalarTypes = Set.of(
                DashboardComponentDescriptorRegistry.PropertyDataType.NUMBER,
                DashboardComponentDescriptorRegistry.PropertyDataType.TEXT,
                DashboardComponentDescriptorRegistry.PropertyDataType.SWITCH,
                DashboardComponentDescriptorRegistry.PropertyDataType.ENUM);
        for (int index = 0; index < columnDefinitions.size(); index++) {
            String definitionPath = propsPath + ".columns[" + index + "]";
            ObjectNode definition = DashboardSchemaStructureRules.requireObject(
                    columnDefinitions.get(index), definitionPath, Set.of("id", "label"));
            String id = DashboardSchemaValueRules.localKey(
                    DashboardSchemaStructureRules.required(definition, "id", definitionPath), definitionPath + ".id");
            DashboardSchemaValueRules.title(
                    DashboardSchemaStructureRules.required(definition, "label", definitionPath),
                    definitionPath + ".label");
            if (!ids.add(id)) throw invalidCollection(propsPath + ".columns", "column id不得重复");

            String bindingPath = bindingsPath + ".columns[" + index + "]";
            ObjectNode entry = DashboardSchemaStructureRules.requireObject(
                    columnBindings.get(index), bindingPath, Set.of("id", "value"));
            String bindingId = DashboardSchemaValueRules.localKey(
                    DashboardSchemaStructureRules.required(entry, "id", bindingPath), bindingPath + ".id");
            if (!id.equals(bindingId)) {
                throw invalidReference(bindingPath + ".id", "必须与同序props.columns id一致");
            }
            DashboardSchemaBindingRules.BindingDefinition binding = validateBinding(
                    entry, "value", bindingPath, variables,
                    DashboardSchemaBindingRules.BindingSource.CURRENT_VALUE);
            if (binding.variableType() != DashboardSchemaVariableCatalog.VariableType.DEVICE_MULTI) {
                throw invalidReference(bindingPath + ".value", "DEVICE_VALUES只接受DEVICE_MULTI变量");
            }
            if (sharedVariableKey == null) {
                sharedVariableKey = binding.variableKey();
            } else if (!sharedVariableKey.equals(binding.variableKey())) {
                throw invalidReference(bindingsPath + ".columns", "所有列必须引用同一DEVICE_MULTI变量");
            }
            requirements.add(new ModelPropertyRequirement(
                    binding.modelKey(), binding.propertyKey(), scalarTypes));
            requirements.add(adapterRequirement(binding, AdapterCapability.BOUNDED_DEVICE_VALUES));
        }
    }

    /** 校验ALARM_LIST精确slot、分页显示默认值及单/多设备变量边界。 */
    private static void validateAlarmList(
            ObjectNode props, ObjectNode bindings, String componentPath,
            DashboardSchemaVariableCatalog variables,
            List<DashboardSchemaExternalRequirement> requirements) {
        String propsPath = componentPath + ".props";
        String bindingsPath = componentPath + ".bindings";
        DashboardSchemaStructureRules.requireObject(props, propsPath, Set.of("title", "pageSize", "showClearedAt"));
        DashboardSchemaStructureRules.requireObject(bindings, bindingsPath, Set.of("alarms"));
        validateOptionalTitle(props, propsPath);
        DashboardSchemaStructureRules.putDefault(props, "pageSize", 20);
        requireInteger(props, "pageSize", propsPath, 1, 50);
        DashboardSchemaStructureRules.putDefault(props, "showClearedAt", true);
        DashboardSchemaStructureRules.requireBoolean(props, "showClearedAt", propsPath);
        DashboardSchemaBindingRules.BindingDefinition binding = validateBinding(
                bindings, "alarms", bindingsPath, variables,
                DashboardSchemaBindingRules.BindingSource.ALARM_LIST);
        requirements.add(adapterRequirement(binding, AdapterCapability.BOUNDED_ALARM_PAGE));
    }

    /** 校验DEVICE_SELECTOR精确目录slot、短文本及显式分页默认值。 */
    private static void validateDeviceSelector(
            ObjectNode props, ObjectNode bindings, String componentPath,
            DashboardSchemaVariableCatalog variables,
            List<DashboardSchemaExternalRequirement> requirements) {
        String propsPath = componentPath + ".props";
        String bindingsPath = componentPath + ".bindings";
        DashboardSchemaStructureRules.requireObject(props, propsPath, Set.of("title", "placeholder", "pageSize"));
        DashboardSchemaStructureRules.requireObject(bindings, bindingsPath, Set.of("directory"));
        validateOptionalTitle(props, propsPath);
        DashboardSchemaStructureRules.putDefault(props, "placeholder", "请选择设备");
        DashboardSchemaValueRules.shortText(props.get("placeholder"), propsPath + ".placeholder");
        DashboardSchemaStructureRules.putDefault(props, "pageSize", 20);
        requireInteger(props, "pageSize", propsPath, 1, 50);
        DashboardSchemaBindingRules.BindingDefinition binding = validateBinding(
                bindings, "directory", bindingsPath, variables,
                DashboardSchemaBindingRules.BindingSource.DEVICE_DIRECTORY);
        requirements.add(adapterRequirement(binding, AdapterCapability.BOUNDED_DEVICE_DIRECTORY_PAGE));
    }

    /** 校验除TEXT外组件的可选title，不为缺失title注入值。 */
    private static void validateOptionalTitle(ObjectNode props, String path) {
        if (props.has("title")) DashboardSchemaValueRules.title(props.get("title"), path + ".title");
    }

    /** 校验必填slot的binding分支与组件声明source一致。 */
    private static DashboardSchemaBindingRules.BindingDefinition validateBinding(
            ObjectNode bindings, String slotName, String path, DashboardSchemaVariableCatalog variables,
            DashboardSchemaBindingRules.BindingSource expectedSource) {
        JsonNode slot = DashboardSchemaStructureRules.required(bindings, slotName, path);
        ObjectNode binding = DashboardSchemaStructureRules.requireObject(slot, path + "." + slotName);
        DashboardSchemaBindingRules.BindingDefinition definition = DashboardSchemaBindingRules.validate(
                binding, path + "." + slotName, variables);
        if (definition.source() != expectedSource) {
            throw invalidReference(path + "." + slotName, "slot的binding source不匹配");
        }
        return definition;
    }

    /** 根据已解析binding建立不带虚假可用结论的数据适配需求。 */
    private static DataAdapterRequirement adapterRequirement(
            DashboardSchemaBindingRules.BindingDefinition binding, AdapterCapability capability) {
        return new DataAdapterRequirement(capability, binding.source(), binding.variableKey(),
                binding.variableType(), binding.modelKey(), binding.propertyKey());
    }

    /** 要求属性型组件使用单设备变量，不把通用CURRENT_VALUE的多设备能力外溢到slot。 */
    private static void requireSingleDevice(
            DashboardSchemaBindingRules.BindingDefinition binding, String path, String componentKind) {
        if (binding.variableType() != DashboardSchemaVariableCatalog.VariableType.DEVICE_SINGLE) {
            throw invalidReference(path, componentKind + "只接受DEVICE_SINGLE变量");
        }
    }

    /** 要求枚举字符串属于封闭集合且不回显外部原文。 */
    private static String requireEnum(JsonNode node, String path, Set<String> allowed) {
        if (node == null || !node.isString()) {
            throw typeMismatch(path, "必须是字符串");
        }
        if (!allowed.contains(node.asString())) throw invalidValue(path, "枚举值不受支持");
        return node.asString();
    }

    /** 要求JSON整数处于闭区间。 */
    private static int requireInteger(
            ObjectNode node, String name, String path, int minimum, int maximum) {
        long value = DashboardSchemaStructureRules.requireInteger(node, name, path);
        if (value < minimum || value > maximum) {
            throw invalidValue(path + "." + name, "整数超出允许范围");
        }
        return (int) value;
    }

    /** 要求对象中的必填字段为数组。 */
    private static ArrayNode requireArray(ObjectNode node, String name, String path) {
        return DashboardSchemaStructureRules.requireArray(
                DashboardSchemaStructureRules.required(node, name, path), path + "." + name);
    }

    /** 要求数组长度位于合同闭区间。 */
    private static void requireSize(ArrayNode node, String path, int minimum, int maximum) {
        if (node.size() < minimum || node.size() > maximum) {
            throw invalidCollection(path, "数量必须在" + minimum + "至" + maximum + "之间");
        }
    }

    /** 创建严格类型错误。 */
    private static DashboardSchemaValidationException typeMismatch(String path, String detail) {
        return reject(DashboardSchemaValidationException.Reason.TYPE_MISMATCH, path, detail);
    }

    /** 创建普通值错误。 */
    private static DashboardSchemaValidationException invalidValue(String path, String detail) {
        return reject(DashboardSchemaValidationException.Reason.INVALID_VALUE, path, detail);
    }

    /** 创建集合数量或唯一性错误。 */
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
}
