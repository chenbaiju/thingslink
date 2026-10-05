package com.things.link.dashboard.application.publication;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 发布候选仍需由外部领域或宿主逐项证明的类型化资格需求。
 *
 * <p>这些值只来自已通过完整内部语义校验的Schema；类型存在不表示外部事实已经成立。</p>
 */
public sealed interface DashboardPublicationEligibilityRequirement permits
        DashboardPublicationEligibilityRequirement.HostComponent,
        DashboardPublicationEligibilityRequirement.BuiltinResource,
        DashboardPublicationEligibilityRequirement.ModelReference,
        DashboardPublicationEligibilityRequirement.DefaultDevice,
        DashboardPublicationEligibilityRequirement.ModelProperty,
        DashboardPublicationEligibilityRequirement.HistoricalProperty,
        DashboardPublicationEligibilityRequirement.ModelGaugeRange,
        DashboardPublicationEligibilityRequirement.DataAdapter {

    /** 宿主必须支持的组件kind闭集。 */
    enum ComponentKind {
        /** 纯文本。 */ TEXT,
        /** 内置图片。 */ IMAGE,
        /** 当前值卡片。 */ VALUE_CARD,
        /** 设备状态。 */ STATUS,
        /** 数值仪表。 */ GAUGE,
        /** 历史折线图。 */ LINE_CHART,
        /** 表格。 */ TABLE,
        /** JSON视图。 */ JSON_VIEW,
        /** 告警列表。 */ ALARM_LIST,
        /** 设备选择器。 */ DEVICE_SELECTOR
    }

    /** Schema变量类型闭集。 */
    enum VariableType {
        /** 单设备变量。 */ DEVICE_SINGLE,
        /** 多设备变量。 */ DEVICE_MULTI,
        /** 时间范围变量。 */ TIME_RANGE,
        /** 文本枚举变量。 */ TEXT_ENUM
    }

    /** 物模型属性dataType闭集。 */
    enum PropertyDataType {
        /** 数值。 */ NUMBER,
        /** 文本。 */ TEXT,
        /** 开关。 */ SWITCH,
        /** 枚举。 */ ENUM,
        /** 对象。 */ OBJECT,
        /** 列表。 */ LIST
    }

    /** binding来源闭集。 */
    enum BindingSource {
        /** 当前属性值。 */ CURRENT_VALUE,
        /** 历史属性序列。 */ HISTORY_SERIES,
        /** 设备状态。 */ DEVICE_STATUS,
        /** 告警列表。 */ ALARM_LIST,
        /** 设备目录。 */ DEVICE_DIRECTORY,
        /** 文本枚举标签。 */ ENUM_TEXT
    }

    /** 后续数据适配器必须提供的有界能力闭集。 */
    enum AdapterCapability {
        /** 单属性当前值。 */ CURRENT_VALUE,
        /** 单设备状态。 */ DEVICE_STATUS,
        /** 复合属性快照。 */ COMPOSITE_SNAPSHOT,
        /** 有界历史序列。 */ HISTORY_SERIES,
        /** 列表属性快照。 */ LIST_SNAPSHOT,
        /** 有界设备值批次。 */ BOUNDED_DEVICE_VALUES,
        /** 有界告警分页。 */ BOUNDED_ALARM_PAGE,
        /** 有界设备目录分页。 */ BOUNDED_DEVICE_DIRECTORY_PAGE
    }

    /** 历史读取粒度闭集。 */
    enum HistoryGranularity {
        /** 原始点。 */ RAW,
        /** 一分钟。 */ ONE_MINUTE,
        /** 一小时。 */ ONE_HOUR,
        /** 一天。 */ ONE_DAY
    }

    /** 历史聚合方式闭集。 */
    enum HistoryAggregation {
        /** 平均值。 */ AVG,
        /** 最小值。 */ MIN,
        /** 最大值。 */ MAX,
        /** 求和。 */ SUM,
        /** 计数。 */ COUNT
    }

    /**
     * 宿主组件及有限兼容范围需求。
     *
     * @param kind 组件kind
     * @param componentVersion 精确组件版本
     * @param minimumHostVersionInclusive 最低包含宿主版本
     * @param maximumHostVersionExclusive 最高排除宿主版本
     */
    record HostComponent(ComponentKind kind, String componentVersion,
                         String minimumHostVersionInclusive, String maximumHostVersionExclusive)
            implements DashboardPublicationEligibilityRequirement {
        /** 冻结已验证的描述符投影。 */
        public HostComponent {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(componentVersion, "componentVersion");
            Objects.requireNonNull(minimumHostVersionInclusive, "minimumHostVersionInclusive");
            Objects.requireNonNull(maximumHostVersionExclusive, "maximumHostVersionExclusive");
        }
    }

    /**
     * 内置公开资源及精确字节摘要需求。
     *
     * @param resourceId 内置资源标识
     * @param digest 精确资源摘要
     */
    record BuiltinResource(String resourceId, String digest)
            implements DashboardPublicationEligibilityRequirement {
        /** 冻结已验证的资源投影。 */
        public BuiltinResource {
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(digest, "digest");
        }
    }

    /**
     * 同项目不可变模型版本及其精确摘要和运行配置档案需求。
     *
     * @param modelKey Schema模型别名
     * @param versionId 不可变版本ID
     * @param digestAlgorithm 摘要算法
     * @param digest 摘要
     * @param profile 模型运行配置档案
     */
    record ModelReference(String modelKey, UUID versionId, String digestAlgorithm, String digest, String profile)
            implements DashboardPublicationEligibilityRequirement {
        /** 冻结已验证的模型投影。 */
        public ModelReference {
            Objects.requireNonNull(modelKey, "modelKey");
            Objects.requireNonNull(versionId, "versionId");
            Objects.requireNonNull(digestAlgorithm, "digestAlgorithm");
            Objects.requireNonNull(digest, "digest");
            Objects.requireNonNull(profile, "profile");
        }
    }

    /**
     * 变量默认设备的存在、模型匹配及授权需求。
     *
     * @param variableKey 变量key
     * @param variableType 变量类型
     * @param modelKey 模型别名
     * @param deviceId 默认设备ID
     */
    record DefaultDevice(String variableKey, VariableType variableType, String modelKey, UUID deviceId)
            implements DashboardPublicationEligibilityRequirement {
        /** 冻结已验证的默认设备投影。 */
        public DefaultDevice {
            Objects.requireNonNull(variableKey, "variableKey");
            Objects.requireNonNull(variableType, "variableType");
            Objects.requireNonNull(modelKey, "modelKey");
            Objects.requireNonNull(deviceId, "deviceId");
        }
    }

    /**
     * 冻结模型顶层属性及允许dataType需求。
     *
     * @param modelKey 模型别名
     * @param propertyKey 顶层属性key
     * @param allowedDataTypes 允许dataType闭集
     */
    record ModelProperty(String modelKey, String propertyKey, Set<PropertyDataType> allowedDataTypes)
            implements DashboardPublicationEligibilityRequirement {
        /** 防御复制dataType集合。 */
        public ModelProperty {
            Objects.requireNonNull(modelKey, "modelKey");
            Objects.requireNonNull(propertyKey, "propertyKey");
            allowedDataTypes = Set.copyOf(allowedDataTypes);
        }
    }

    /**
     * 数值属性的有限历史读取能力需求。
     *
     * @param variableKey 设备变量key
     * @param modelKey 模型别名
     * @param propertyKey 属性key
     * @param timeRangeVariableKey 时间范围变量key
     * @param granularity 粒度
     * @param aggregation 聚合
     */
    record HistoricalProperty(String variableKey, String modelKey, String propertyKey,
                              String timeRangeVariableKey, HistoryGranularity granularity,
                              HistoryAggregation aggregation)
            implements DashboardPublicationEligibilityRequirement {
        /** 冻结已验证的历史读取投影。 */
        public HistoricalProperty {
            Objects.requireNonNull(variableKey, "variableKey");
            Objects.requireNonNull(modelKey, "modelKey");
            Objects.requireNonNull(propertyKey, "propertyKey");
            Objects.requireNonNull(timeRangeVariableKey, "timeRangeVariableKey");
            Objects.requireNonNull(granularity, "granularity");
            Objects.requireNonNull(aggregation, "aggregation");
        }
    }

    /**
     * 仪表所需的冻结模型数值量程需求。
     *
     * @param modelKey 模型别名
     * @param propertyKey 数值属性key
     */
    record ModelGaugeRange(String modelKey, String propertyKey)
            implements DashboardPublicationEligibilityRequirement {
        /** 冻结已验证的量程投影。 */
        public ModelGaugeRange {
            Objects.requireNonNull(modelKey, "modelKey");
            Objects.requireNonNull(propertyKey, "propertyKey");
        }
    }

    /**
     * binding运行时所需的数据适配器能力需求。
     *
     * @param capability 适配能力
     * @param source binding来源
     * @param variableKey 变量key
     * @param variableType 变量类型
     * @param modelKey 模型别名或空串
     * @param propertyKey 属性key或空串
     */
    record DataAdapter(AdapterCapability capability, BindingSource source, String variableKey,
                       VariableType variableType, String modelKey, String propertyKey)
            implements DashboardPublicationEligibilityRequirement {
        /** 冻结已验证的数据适配投影。 */
        public DataAdapter {
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(variableKey, "variableKey");
            Objects.requireNonNull(variableType, "variableType");
            Objects.requireNonNull(modelKey, "modelKey");
            Objects.requireNonNull(propertyKey, "propertyKey");
        }
    }
}
