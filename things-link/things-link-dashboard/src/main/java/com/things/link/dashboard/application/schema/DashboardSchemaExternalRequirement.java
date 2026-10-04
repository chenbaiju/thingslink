package com.things.link.dashboard.application.schema;

import java.util.Set;

/**
 * 纯Schema语义校验无法自行证明、必须由后续领域端口解析的类型化需求。
 *
 * <p>需求存在表示结构已足以定位外部事实，不表示该事实存在、获权或兼容。</p>
 */
sealed interface DashboardSchemaExternalRequirement permits HostComponentRequirement,
        BuiltinResourceRequirement, ModelReferenceRequirement, DefaultDeviceRequirement,
        ModelPropertyRequirement, HistoricalPropertyRequirement, ModelGaugeRangeRequirement,
        DataAdapterRequirement {
}

/**
 * 宿主必须实际实现指定精确组件的需求。
 *
 * @param kind 组件kind
 * @param componentVersion 精确组件版本
 * @param hostCompatibility 描述符声明的有限宿主范围
 */
record HostComponentRequirement(
        DashboardComponentDescriptorRegistry.ComponentKind kind,
        String componentVersion,
        DashboardComponentDescriptorRegistry.HostRange hostCompatibility)
        implements DashboardSchemaExternalRequirement {
}

/**
 * IMAGE引用必须命中宿主内置公开资源及精确摘要的需求。
 *
 * @param resourceId 内置资源标识
 * @param digest 期望资源字节SHA-256
 */
record BuiltinResourceRequirement(String resourceId, String digest)
        implements DashboardSchemaExternalRequirement {
}

/**
 * 根ModelReference必须命中同项目不可变模型版本及精确摘要/Profile的需求。
 *
 * @param modelKey Schema模型别名
 * @param versionId 不可变模型版本ID
 * @param digestAlgorithm 摘要算法标识
 * @param digest 模型版本权威摘要
 * @param profile 复合属性Profile
 */
record ModelReferenceRequirement(
        String modelKey, String versionId, String digestAlgorithm, String digest, String profile)
        implements DashboardSchemaExternalRequirement {
}

/**
 * 设备变量默认值必须存在、匹配声明模型且在运行时重新校验当前授权的需求。
 *
 * @param variableKey 设备变量key
 * @param variableType 单设备或多设备变量类型
 * @param modelKey 变量声明的模型key
 * @param deviceId 默认设备ID
 */
record DefaultDeviceRequirement(
        String variableKey, DashboardSchemaVariableCatalog.VariableType variableType,
        String modelKey, String deviceId)
        implements DashboardSchemaExternalRequirement {
}

/**
 * 属性binding必须命中冻结模型顶层属性及允许dataType的需求。
 *
 * @param modelKey Schema内部模型别名
 * @param propertyKey 顶层属性键
 * @param allowedDataTypes 当前组件slot允许的dataType闭集
 */
record ModelPropertyRequirement(
        String modelKey, String propertyKey,
        Set<DashboardComponentDescriptorRegistry.PropertyDataType> allowedDataTypes)
        implements DashboardSchemaExternalRequirement {
    /** 防止调用方改写允许dataType集合。 */
    ModelPropertyRequirement {
        allowedDataTypes = Set.copyOf(allowedDataTypes);
    }
}

/**
 * 历史序列属性必须为NUMBER且具备精确时间、粒度和聚合适配的需求。
 *
 * @param variableKey 单设备变量key
 * @param modelKey Schema模型别名
 * @param propertyKey 顶层属性key
 * @param timeRangeVariableKey 时间范围变量key
 * @param granularity 历史粒度
 * @param aggregation 聚合方式
 */
record HistoricalPropertyRequirement(
        String variableKey, String modelKey, String propertyKey,
        String timeRangeVariableKey, String granularity, String aggregation)
        implements DashboardSchemaExternalRequirement {
}

/**
 * GAUGE的MODEL量程必须由冻结模型属性提供合法数值边界的需求。
 *
 * @param modelKey Schema内部模型别名
 * @param propertyKey 顶层数值属性键
 */
record ModelGaugeRangeRequirement(String modelKey, String propertyKey)
        implements DashboardSchemaExternalRequirement {
}

/**
 * binding source必须具有后续可执行数据适配器的需求。
 *
 * @param capability 后续适配器必须提供的有界能力
 * @param source 数据源判别类型
 * @param variableKey 被引用变量key
 * @param variableType 已解析的变量类型和设备基数
 * @param modelKey 设备变量所引模型key；非设备变量为空字符串
 * @param propertyKey 顶层属性key；非属性数据源为空字符串
 */
record DataAdapterRequirement(
        AdapterCapability capability,
        DashboardSchemaBindingRules.BindingSource source,
        String variableKey, DashboardSchemaVariableCatalog.VariableType variableType,
        String modelKey, String propertyKey)
        implements DashboardSchemaExternalRequirement {
}

/** 后续数据适配器必须显式实现的有界能力类型。 */
enum AdapterCapability {
    /** 单设备当前属性值读取。 */ CURRENT_VALUE,
    /** 单设备状态读取。 */ DEVICE_STATUS,
    /** 顶层OBJECT或LIST完整快照读取。 */ COMPOSITE_SNAPSHOT,
    /** NUMBER属性的有界历史序列读取。 */ HISTORY_SERIES,
    /** LIST顶层属性的完整有界快照读取。 */ LIST_SNAPSHOT,
    /** 多设备同模型多属性当前值的有界批量读取。 */ BOUNDED_DEVICE_VALUES,
    /** 带冻结过滤条件和显式翻页的有界告警分页读取。 */ BOUNDED_ALARM_PAGE,
    /** 受modelKey约束并显式分页的设备目录读取。 */ BOUNDED_DEVICE_DIRECTORY_PAGE
}
