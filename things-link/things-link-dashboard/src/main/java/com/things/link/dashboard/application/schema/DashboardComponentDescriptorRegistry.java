package com.things.link.dashboard.application.schema;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 十种内置只读组件、两个精确版本的包内描述符注册表。
 *
 * <p>描述符表达X-02b2c1及ADR0145已经冻结的静态能力，不代表宿主实现、资源或物模型事实已经存在。</p>
 */
final class DashboardComponentDescriptorRegistry {
    /** 首批组件共同使用的精确版本。 */
    private static final String COMPONENT_VERSION = "1.0.0";
    /** 首批组件只声明当前已有证据的单宿主版本区间，避免推定未来1.x自动兼容。 */
    private static final HostRange INITIAL_HOST_RANGE = new HostRange("1.0.0", "1.0.1");
    /** 首批十种组件均支持两种冻结画布。 */
    private static final Set<CanvasMode> BOTH_CANVASES = Set.of(
            CanvasMode.RESPONSIVE_GRID, CanvasMode.FIXED_SCREEN);
    /** kind与精确版本联合索引的唯一描述符集合。 */
    private static final Map<DescriptorKey, ComponentDescriptor> DESCRIPTORS = createDescriptors();

    /** 注册表不允许实例化。 */
    private DashboardComponentDescriptorRegistry() {
    }

    /**
     * 返回全部二十个不可变描述符。
     *
     * @return 按登记顺序保存的描述符快照
     */
    static Collection<ComponentDescriptor> descriptors() {
        return DESCRIPTORS.values();
    }

    /**
     * 按kind和精确版本取得描述符，不允许选择最近版本。
     *
     * @param kind 组件kind
     * @param componentVersion 组件精确版本
     * @param path 组件JSON路径
     * @return 唯一描述符
     */
    static ComponentDescriptor require(String kind, String componentVersion, String path) {
        ComponentDescriptor descriptor = DESCRIPTORS.get(new DescriptorKey(kind, componentVersion));
        if (descriptor == null) {
            throw new DashboardSchemaValidationException(DashboardSchemaValidationException.Reason.INVALID_VALUE,
                    path, "组件kind或精确版本不受支持");
        }
        return descriptor;
    }

    /** 建立十种组件的唯一静态描述符。 */
    private static Map<DescriptorKey, ComponentDescriptor> createDescriptors() {
        Map<DescriptorKey, ComponentDescriptor> descriptors = new LinkedHashMap<>();
        register(descriptors, descriptor(ComponentKind.TEXT,
                Map.of(
                        "content", optional(ValueType.TEXT_CONTENT, ""),
                        "align", optional(ValueType.TEXT_ALIGN, "LEFT"),
                        "size", optional(ValueType.TEXT_SIZE, "MEDIUM"),
                        "tone", optional(ValueType.TEXT_TONE, "REGULAR")),
                Map.of("text", optionalSlot(ValueType.BINDING, DashboardSchemaBindingRules.BindingSource.ENUM_TEXT)),
                DataTypeSupport.notApplicable()));
        register(descriptors, descriptor(ComponentKind.IMAGE,
                Map.of(
                        "title", optional(ValueType.TITLE),
                        "resourceId", required(ValueType.LOCAL_KEY),
                        "resourceDigest", required(ValueType.SHA256),
                        "alt", required(ValueType.SHORT_TEXT),
                        "fit", optional(ValueType.IMAGE_FIT, "CONTAIN")),
                Map.of(), DataTypeSupport.notApplicable()));
        register(descriptors, descriptor(ComponentKind.VALUE_CARD,
                Map.of(
                        "title", optional(ValueType.TITLE),
                        "precision", optional(ValueType.PRECISION, "2"),
                        "unitMode", optional(ValueType.UNIT_MODE, "MODEL")),
                Map.of("value", requiredSlot(ValueType.BINDING,
                        DashboardSchemaBindingRules.BindingSource.CURRENT_VALUE)),
                DataTypeSupport.propertyTyped(Set.of(
                        PropertyDataType.NUMBER, PropertyDataType.TEXT,
                        PropertyDataType.SWITCH, PropertyDataType.ENUM))));
        register(descriptors, descriptor(ComponentKind.STATUS,
                Map.of(
                        "title", optional(ValueType.TITLE),
                        "showLastOnlineAt", optional(ValueType.BOOLEAN, "true")),
                Map.of("status", requiredSlot(ValueType.BINDING,
                        DashboardSchemaBindingRules.BindingSource.DEVICE_STATUS)),
                DataTypeSupport.notApplicable()));
        register(descriptors, descriptor(ComponentKind.GAUGE,
                Map.of(
                        "title", optional(ValueType.TITLE),
                        "scaleMode", optional(ValueType.GAUGE_SCALE_MODE, "MODEL"),
                        "min", optional(ValueType.CONFIG_NUMBER),
                        "max", optional(ValueType.CONFIG_NUMBER),
                        "precision", optional(ValueType.PRECISION, "2"),
                        "unitMode", optional(ValueType.UNIT_MODE, "MODEL")),
                Map.of("value", requiredSlot(ValueType.BINDING,
                        DashboardSchemaBindingRules.BindingSource.CURRENT_VALUE)),
                DataTypeSupport.propertyTyped(Set.of(PropertyDataType.NUMBER))));
        register(descriptors, descriptor(ComponentKind.LINE_CHART,
                Map.of(
                        "title", optional(ValueType.TITLE),
                        "showLegend", optional(ValueType.BOOLEAN, "true"),
                        "series", required(ValueType.SERIES_DEFINITIONS)),
                Map.of("series", requiredSlot(ValueType.BINDING_ARRAY,
                        DashboardSchemaBindingRules.BindingSource.HISTORY_SERIES)),
                DataTypeSupport.propertyTyped(Set.of(PropertyDataType.NUMBER))));
        register(descriptors, descriptor(ComponentKind.TABLE,
                Map.of(
                        "title", optional(ValueType.TITLE),
                        "mode", required(ValueType.TABLE_MODE),
                        "rowLimit", optional(ValueType.ROW_LIMIT, "20"),
                        "columns", optional(ValueType.COLUMN_DEFINITIONS)),
                Map.of(
                        "value", optionalSlot(ValueType.BINDING,
                                DashboardSchemaBindingRules.BindingSource.CURRENT_VALUE),
                        "columns", optionalSlot(ValueType.BINDING_ARRAY,
                                DashboardSchemaBindingRules.BindingSource.CURRENT_VALUE)),
                DataTypeSupport.propertyTyped(Set.of(
                        PropertyDataType.NUMBER, PropertyDataType.TEXT, PropertyDataType.SWITCH,
                        PropertyDataType.ENUM, PropertyDataType.LIST))));
        register(descriptors, descriptor(ComponentKind.JSON_VIEW,
                Map.of(
                        "title", optional(ValueType.TITLE),
                        "initialExpandDepth", optional(ValueType.EXPAND_DEPTH, "1")),
                Map.of("value", requiredSlot(ValueType.BINDING,
                        DashboardSchemaBindingRules.BindingSource.CURRENT_VALUE)),
                DataTypeSupport.propertyTyped(Set.of(PropertyDataType.OBJECT, PropertyDataType.LIST))));
        register(descriptors, descriptor(ComponentKind.ALARM_LIST,
                Map.of(
                        "title", optional(ValueType.TITLE),
                        "pageSize", optional(ValueType.PAGE_SIZE, "20"),
                        "showClearedAt", optional(ValueType.BOOLEAN, "true")),
                Map.of("alarms", requiredSlot(ValueType.BINDING,
                        DashboardSchemaBindingRules.BindingSource.ALARM_LIST)),
                DataTypeSupport.notApplicable()));
        register(descriptors, descriptor(ComponentKind.DEVICE_SELECTOR,
                Map.of(
                        "title", optional(ValueType.TITLE),
                        "placeholder", optional(ValueType.SHORT_TEXT, "请选择设备"),
                        "pageSize", optional(ValueType.PAGE_SIZE, "20")),
                Map.of("directory", requiredSlot(ValueType.BINDING,
                        DashboardSchemaBindingRules.BindingSource.DEVICE_DIRECTORY)),
                DataTypeSupport.notApplicable()));
        if (descriptors.size() != ComponentKind.values().length) {
            throw new IllegalStateException("内置组件描述符必须覆盖全部十种kind");
        }
        for (ComponentDescriptor original : java.util.List.copyOf(descriptors.values())) {
            register(descriptors, new ComponentDescriptor(original.kind(), "1.0.1", original.props(),
                    original.slots(), original.supportedDataTypes(), original.supportedCanvasModes(),
                    new HostRange("1.1.0", "1.1.2")));
        }
        return Map.copyOf(descriptors);
    }

    /** 创建一个使用首版共同版本、画布和宿主范围的描述符。 */
    private static ComponentDescriptor descriptor(
            ComponentKind kind, Map<String, FieldDescriptor> props,
            Map<String, SlotDescriptor> slots, DataTypeSupport dataTypes) {
        return new ComponentDescriptor(kind, COMPONENT_VERSION, Map.copyOf(props), Map.copyOf(slots),
                dataTypes, BOTH_CANVASES, INITIAL_HOST_RANGE);
    }

    /** 把描述符登记到联合键索引并在初始化时拒绝重复。 */
    private static void register(Map<DescriptorKey, ComponentDescriptor> registry, ComponentDescriptor descriptor) {
        DescriptorKey key = new DescriptorKey(descriptor.kind().name(), descriptor.componentVersion());
        if (registry.putIfAbsent(key, descriptor) != null) {
            throw new IllegalStateException("内置组件描述符kind与版本不得重复");
        }
    }

    /** 创建必填props字段元数据。 */
    private static FieldDescriptor required(ValueType type) {
        return new FieldDescriptor(type, true, Optional.empty());
    }

    /** 创建无默认值的可选props字段元数据。 */
    private static FieldDescriptor optional(ValueType type) {
        return new FieldDescriptor(type, false, Optional.empty());
    }

    /** 创建有唯一默认值的可选props字段元数据。 */
    private static FieldDescriptor optional(ValueType type, String defaultLiteral) {
        return new FieldDescriptor(type, false, Optional.of(defaultLiteral));
    }

    /** 创建必填单slot或数组slot元数据。 */
    private static SlotDescriptor requiredSlot(
            ValueType type, DashboardSchemaBindingRules.BindingSource source) {
        return new SlotDescriptor(type, true, Set.of(source));
    }

    /** 创建可选单slot或数组slot元数据。 */
    private static SlotDescriptor optionalSlot(
            ValueType type, DashboardSchemaBindingRules.BindingSource source) {
        return new SlotDescriptor(type, false, Set.of(source));
    }

    /** 十种冻结组件kind。 */
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

    /** 描述符使用的props或slot机器值类型。 */
    enum ValueType {
        /** Title原子类型。 */ TITLE,
        /** TextContent原子类型。 */ TEXT_CONTENT,
        /** ShortText原子类型。 */ SHORT_TEXT,
        /** LocalKey原子类型。 */ LOCAL_KEY,
        /** SHA-256原子类型。 */ SHA256,
        /** ConfigNumber原子类型。 */ CONFIG_NUMBER,
        /** 严格JSON boolean。 */ BOOLEAN,
        /** 0至6显示精度整数。 */ PRECISION,
        /** 1至50分页大小整数。 */ PAGE_SIZE,
        /** 0至2展开深度整数。 */ EXPAND_DEPTH,
        /** 1至256表格行数整数。 */ ROW_LIMIT,
        /** 文本对齐枚举。 */ TEXT_ALIGN,
        /** 文本字号枚举。 */ TEXT_SIZE,
        /** 文本色调枚举。 */ TEXT_TONE,
        /** 图片填充枚举。 */ IMAGE_FIT,
        /** 单位模式枚举。 */ UNIT_MODE,
        /** 仪表量程模式枚举。 */ GAUGE_SCALE_MODE,
        /** 表格模式枚举。 */ TABLE_MODE,
        /** 折线序列描述数组。 */ SERIES_DEFINITIONS,
        /** 表格列描述数组。 */ COLUMN_DEFINITIONS,
        /** 单个binding对象。 */ BINDING,
        /** binding对象数组。 */ BINDING_ARRAY
    }

    /** 组件属性事实使用的六种物模型dataType。 */
    enum PropertyDataType {
        /** 数值属性。 */ NUMBER,
        /** 文本属性。 */ TEXT,
        /** 开关属性。 */ SWITCH,
        /** 枚举属性。 */ ENUM,
        /** 对象属性。 */ OBJECT,
        /** 列表属性。 */ LIST
    }

    /** 两种冻结画布模式。 */
    enum CanvasMode {
        /** 二十四列响应式网格。 */ RESPONSIVE_GRID,
        /** 1920乘1080固定画布。 */ FIXED_SCREEN
    }

    /** supportedDataTypes字段是否适用于当前组件。 */
    enum DataTypeApplicability {
        /** 组件消费模型属性并声明允许dataType集合。 */ PROPERTY_TYPED,
        /** 组件不消费模型属性，dataType概念不适用。 */ NOT_APPLICABLE
    }

    /**
     * props字段机器元数据。
     *
     * @param type 值类型
     * @param required 是否必填
     * @param defaultLiteral 省略时的唯一默认文本，无默认时为空
     */
    record FieldDescriptor(ValueType type, boolean required, Optional<String> defaultLiteral) { }

    /**
     * slot机器元数据。
     *
     * @param type 单binding或binding数组
     * @param required 是否必填
     * @param acceptedSources 允许的binding判别source
     */
    record SlotDescriptor(ValueType type, boolean required,
                          Set<DashboardSchemaBindingRules.BindingSource> acceptedSources) {
        /** 防止调用方通过可变集合改写注册表语义。 */
        SlotDescriptor {
            acceptedSources = Set.copyOf(acceptedSources);
        }
    }

    /**
     * 明确区分属性dataType集合与不适用状态。
     *
     * @param applicability dataType适用性
     * @param allowedTypes 属性型组件允许的类型；不适用时为空
     */
    record DataTypeSupport(DataTypeApplicability applicability, Set<PropertyDataType> allowedTypes) {
        /** 复制集合并守住适用性与集合的一致关系。 */
        DataTypeSupport {
            allowedTypes = Set.copyOf(allowedTypes);
            if (applicability == DataTypeApplicability.PROPERTY_TYPED && allowedTypes.isEmpty()
                    || applicability == DataTypeApplicability.NOT_APPLICABLE && !allowedTypes.isEmpty()) {
                throw new IllegalArgumentException("dataType适用性与允许集合不一致");
            }
        }

        /** 返回一个属性型dataType声明。 */
        static DataTypeSupport propertyTyped(Set<PropertyDataType> allowedTypes) {
            return new DataTypeSupport(DataTypeApplicability.PROPERTY_TYPED, allowedTypes);
        }

        /** 返回一个明确不适用属性dataType的声明。 */
        static DataTypeSupport notApplicable() {
            return new DataTypeSupport(DataTypeApplicability.NOT_APPLICABLE, Set.of());
        }
    }

    /**
     * 有限宿主兼容区间。
     *
     * @param minInclusive 最低包含宿主版本
     * @param maxExclusive 最高排除宿主版本
     */
    record HostRange(String minInclusive, String maxExclusive) {
        /** 初始化时即校验有限范围，避免静态描述符携带反向或无效版本。 */
        HostRange {
            HostVersion minimum = HostVersion.parse(minInclusive);
            HostVersion maximum = HostVersion.parse(maxExclusive);
            if (minimum.compareTo(maximum) >= 0) {
                throw new IllegalArgumentException("宿主兼容范围必须具有严格上界");
            }
        }

        /**
         * 按三个数字段判断宿主版本是否落在半开区间。
         *
         * @param hostVersion 待检查宿主版本
         * @return 语法合法且范围命中时为true
         */
        boolean contains(String hostVersion) {
            try {
                HostVersion candidate = HostVersion.parse(hostVersion);
                return candidate.compareTo(HostVersion.parse(minInclusive)) >= 0
                        && candidate.compareTo(HostVersion.parse(maxExclusive)) < 0;
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
    }

    /**
     * 宿主SemVer的三段数字投影，防止用字典序比较版本。
     *
     * @param major 主版本段
     * @param minor 次版本段
     * @param patch 补丁版本段
     */
    private record HostVersion(int major, int minor, int patch) implements Comparable<HostVersion> {
        /**
         * 严格解析三段无前导零SemVer。
         *
         * @param value 版本文本
         * @return 数字版本
         */
        private static HostVersion parse(String value) {
            if (value == null) throw new IllegalArgumentException("宿主版本不合法");
            String[] segments = value.split("\\.", -1);
            if (segments.length != 3) throw new IllegalArgumentException("宿主版本不合法");
            return new HostVersion(parseSegment(segments[0]), parseSegment(segments[1]), parseSegment(segments[2]));
        }

        /** 解析一个0至65535的无前导零数字段。 */
        private static int parseSegment(String value) {
            if (value.isEmpty() || value.length() > 5 || value.length() > 1 && value.startsWith("0")
                    || !value.chars().allMatch(character -> character >= '0' && character <= '9')) {
                throw new IllegalArgumentException("宿主版本不合法");
            }
            int segment = Integer.parseInt(value);
            if (segment > 65_535) throw new IllegalArgumentException("宿主版本不合法");
            return segment;
        }

        /** {@inheritDoc} */
        @Override
        public int compareTo(HostVersion other) {
            int majorComparison = Integer.compare(major, other.major);
            if (majorComparison != 0) return majorComparison;
            int minorComparison = Integer.compare(minor, other.minor);
            if (minorComparison != 0) return minorComparison;
            return Integer.compare(patch, other.patch);
        }
    }

    /**
     * 一个内置组件的完整包内机器描述符。
     *
     * @param kind 组件kind
     * @param componentVersion 精确组件版本
     * @param props props字段闭集及类型
     * @param slots binding slot闭集及类型
     * @param supportedDataTypes 支持的物模型属性类型或明确不适用
     * @param supportedCanvasModes 支持画布闭集
     * @param hostCompatibility 有限宿主兼容区间
     */
    record ComponentDescriptor(
            ComponentKind kind, String componentVersion,
            Map<String, FieldDescriptor> props, Map<String, SlotDescriptor> slots,
            DataTypeSupport supportedDataTypes, Set<CanvasMode> supportedCanvasModes,
            HostRange hostCompatibility) {
        /** 防止调用方通过可变容器改写唯一注册表。 */
        ComponentDescriptor {
            props = Map.copyOf(props);
            slots = Map.copyOf(slots);
            supportedCanvasModes = Set.copyOf(supportedCanvasModes);
        }
    }

    /**
     * 注册表联合身份。
     *
     * @param kind 组件kind
     * @param componentVersion 精确组件版本
     */
    private record DescriptorKey(String kind, String componentVersion) { }
}
