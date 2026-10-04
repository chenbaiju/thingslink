import type { DashboardCanvasMode, DashboardComponentKind } from "./generated.js";

/** Dashboard合同中的局部稳定键。 */
export type LocalKey = string;
/** Dashboard合同中的顶层模型属性键。 */
export type PropertyKey = string;
/** Dashboard合同中的规范UUID文本。 */
export type Uuid = string;
/** Dashboard合同中的小写十六进制SHA-256摘要。 */
export type Sha256 = string;
/** Dashboard合同中的三段数字语义版本。 */
export type SemVer = string;
/** Dashboard合同中的十进制配置数值。 */
export type ConfigNumber = number;
/** 组件标题或页面标题。 */
export type Title = string;
/** 组件短文本。 */
export type ShortText = string;
/** 静态文本组件正文。 */
export type TextContent = string;
/** 禁止声明任意字段的只读空对象。 */
export type EmptyObject = Readonly<Record<string, never>>;

/** 看板模型版本的冻结引用。 */
export interface DashboardModelReference {
  /** Schema内部模型键。 */
  readonly key: LocalKey;
  /** 已发布模型版本UUID。 */
  readonly versionId: Uuid;
  /** PostgreSQL JSONB文本摘要算法。 */
  readonly digestAlgorithm: "PG_JSONB_TEXT_V1_SHA256";
  /** 已发布模型版本的SHA-256摘要。 */
  readonly digest: Sha256;
  /** 复合属性解释Profile。 */
  readonly profile: "TC_PROPERTY_COMPOSITE_V1";
}

/** 设备变量通用字段。 */
export interface DashboardDeviceVariableBase {
  /** 全文唯一变量键。 */
  readonly key: LocalKey;
  /** 面向用户的变量标题。 */
  readonly title: Title;
  /** 使用者是否必须完成选择。 */
  readonly required: boolean;
  /** 引用根models中的模型键。 */
  readonly modelKey: LocalKey;
}

/** 单设备变量。 */
export interface DashboardSingleDeviceVariable extends DashboardDeviceVariableBase {
  /** 变量判别类型。 */
  readonly type: "DEVICE_SINGLE";
  /** 可选默认设备UUID。 */
  readonly defaultDeviceId?: Uuid;
}

/** 多设备变量。 */
export interface DashboardMultiDeviceVariable extends DashboardDeviceVariableBase {
  /** 变量判别类型。 */
  readonly type: "DEVICE_MULTI";
  /** 运行时选择数量上限。 */
  readonly maxItems: number;
  /** 规范化后的默认设备UUID列表。 */
  readonly defaultDeviceIds: readonly Uuid[];
}

/** 时间范围预设。 */
export type DashboardTimeRangePreset = "LAST_1_HOUR" | "LAST_24_HOURS" | "LAST_7_DAYS";

/** 时间范围变量。 */
export interface DashboardTimeRangeVariable {
  /** 全文唯一变量键。 */
  readonly key: LocalKey;
  /** 变量判别类型。 */
  readonly type: "TIME_RANGE";
  /** 面向用户的变量标题。 */
  readonly title: Title;
  /** 使用者是否必须完成选择。 */
  readonly required: boolean;
  /** 规范化后的默认时间预设。 */
  readonly defaultPreset: DashboardTimeRangePreset;
  /** 不重复的允许时间预设。 */
  readonly allowedPresets: readonly DashboardTimeRangePreset[];
}

/** 文本枚举选项。 */
export interface DashboardTextEnumOption {
  /** 在变量内唯一的选项值。 */
  readonly value: LocalKey;
  /** 面向用户的选项标签。 */
  readonly label: Title;
}

/** 文本枚举变量。 */
export interface DashboardTextEnumVariable {
  /** 全文唯一变量键。 */
  readonly key: LocalKey;
  /** 变量判别类型。 */
  readonly type: "TEXT_ENUM";
  /** 面向用户的变量标题。 */
  readonly title: Title;
  /** 使用者是否必须完成选择。 */
  readonly required: boolean;
  /** 非空且值不重复的选项。 */
  readonly options: readonly DashboardTextEnumOption[];
  /** 可选默认选项值。 */
  readonly defaultValue?: LocalKey;
}

/** Dashboard v1四种变量的封闭联合。 */
export type DashboardVariable =
  | DashboardSingleDeviceVariable
  | DashboardMultiDeviceVariable
  | DashboardTimeRangeVariable
  | DashboardTextEnumVariable;

/** 指向设备变量的内部引用。 */
export interface DashboardDeviceReference {
  /** DEVICE_SINGLE或DEVICE_MULTI变量键，具体允许基数由slot决定。 */
  readonly variableKey: LocalKey;
}

/** 当前属性值binding。 */
export interface DashboardCurrentValueBinding {
  /** Binding判别来源。 */
  readonly source: "CURRENT_VALUE";
  /** 提供当前值的设备变量引用。 */
  readonly device: DashboardDeviceReference;
  /** 顶层模型属性键。 */
  readonly propertyKey: PropertyKey;
}

/** 历史时间粒度。 */
export type DashboardHistoryGranularity = "RAW" | "ONE_MINUTE" | "ONE_HOUR" | "ONE_DAY";
/** 历史聚合方法。 */
export type DashboardHistoryAggregation = "AVG" | "MIN" | "MAX" | "SUM" | "COUNT";

/** 历史属性序列binding。 */
export interface DashboardHistorySeriesBinding {
  /** Binding判别来源。 */
  readonly source: "HISTORY_SERIES";
  /** 必须引用DEVICE_SINGLE变量的设备引用。 */
  readonly device: DashboardDeviceReference;
  /** 顶层模型属性键。 */
  readonly propertyKey: PropertyKey;
  /** 必须引用TIME_RANGE变量的键。 */
  readonly timeRangeVariableKey: LocalKey;
  /** 历史读取粒度。 */
  readonly granularity: DashboardHistoryGranularity;
  /** 历史聚合方法。 */
  readonly aggregation: DashboardHistoryAggregation;
}

/** 设备状态binding。 */
export interface DashboardDeviceStatusBinding {
  /** Binding判别来源。 */
  readonly source: "DEVICE_STATUS";
  /** 必须引用DEVICE_SINGLE变量的设备引用。 */
  readonly device: DashboardDeviceReference;
}

/** 告警条件状态。 */
export type DashboardAlarmConditionState = "PENDING" | "ACTIVE" | "CLEARED";
/** 告警确认状态。 */
export type DashboardAlarmAckState = "UNACKNOWLEDGED" | "ACKNOWLEDGED";
/** 告警严重度。 */
export type DashboardAlarmSeverity = "CRITICAL" | "MAJOR" | "MINOR" | "WARNING" | "INFO";

/** 告警列表binding。 */
export interface DashboardAlarmListBinding {
  /** Binding判别来源。 */
  readonly source: "ALARM_LIST";
  /** 单设备或多设备变量引用。 */
  readonly devices: DashboardDeviceReference;
  /** 非空且不重复的条件状态过滤。 */
  readonly conditionStates: readonly DashboardAlarmConditionState[];
  /** 非空且不重复的确认状态过滤。 */
  readonly ackStates: readonly DashboardAlarmAckState[];
  /** 非空且不重复的严重度过滤。 */
  readonly severities: readonly DashboardAlarmSeverity[];
}

/** 设备目录binding。 */
export interface DashboardDeviceDirectoryBinding {
  /** Binding判别来源。 */
  readonly source: "DEVICE_DIRECTORY";
  /** DEVICE_SINGLE或DEVICE_MULTI变量键。 */
  readonly variableKey: LocalKey;
}

/** 文本枚举标签binding。 */
export interface DashboardEnumTextBinding {
  /** Binding判别来源。 */
  readonly source: "ENUM_TEXT";
  /** TEXT_ENUM变量键。 */
  readonly variableKey: LocalKey;
}

/** Dashboard v1六种binding的封闭联合。 */
export type DashboardBinding =
  | DashboardCurrentValueBinding
  | DashboardHistorySeriesBinding
  | DashboardDeviceStatusBinding
  | DashboardAlarmListBinding
  | DashboardDeviceDirectoryBinding
  | DashboardEnumTextBinding;

/** 响应式栅格组件布局。 */
export interface DashboardResponsiveLayout {
  /** 左上角零基列坐标。 */
  readonly x: number;
  /** 左上角零基行坐标。 */
  readonly y: number;
  /** 组件占用列数。 */
  readonly w: number;
  /** 组件占用行高单位数。 */
  readonly h: number;
}

/** 固定屏组件布局。 */
export interface DashboardFixedLayout {
  /** 左上角零基像素横坐标。 */
  readonly x: number;
  /** 左上角零基像素纵坐标。 */
  readonly y: number;
  /** 组件像素宽度。 */
  readonly w: number;
  /** 组件像素高度。 */
  readonly h: number;
}

/** 组件共同字段。 */
export interface DashboardComponentBase<K extends DashboardComponentKind, L> {
  /** 全文唯一组件ID。 */
  readonly id: LocalKey;
  /** 组件判别种类。 */
  readonly kind: K;
  /** 冻结组件Schema版本。 */
  readonly componentVersion: "1.0.0" | "1.0.1";
  /** 与根presentation画布一致的布局。 */
  readonly layout: L;
}

/** TEXT规范化后的公共显示属性。 */
export interface DashboardTextDisplayProps {
  /** 文本对齐方式。 */
  readonly align: "LEFT" | "CENTER" | "RIGHT";
  /** 文本尺寸。 */
  readonly size: "SMALL" | "MEDIUM" | "LARGE";
  /** 文本色调。 */
  readonly tone: "REGULAR" | "SECONDARY" | "PRIMARY";
}

/** 静态TEXT组件。 */
export interface DashboardStaticTextComponent<L> extends DashboardComponentBase<"TEXT", L> {
  /** 已注入默认值的静态文本属性。 */
  readonly props: DashboardTextDisplayProps & { readonly content: TextContent };
  /** 静态文本不接受binding。 */
  readonly bindings: EmptyObject;
}

/** 动态TEXT组件。 */
export interface DashboardDynamicTextComponent<L> extends DashboardComponentBase<"TEXT", L> {
  /** 动态文本只保留显示属性。 */
  readonly props: DashboardTextDisplayProps;
  /** 动态文本的唯一枚举标签slot。 */
  readonly bindings: Readonly<{ text: DashboardEnumTextBinding }>;
}

/** IMAGE组件。 */
export interface DashboardImageComponent<L> extends DashboardComponentBase<"IMAGE", L> {
  /** 内置资源和显示属性。 */
  readonly props: Readonly<{
    title?: Title;
    resourceId: LocalKey;
    resourceDigest: Sha256;
    alt: ShortText;
    fit: "CONTAIN" | "COVER";
  }>;
  /** IMAGE不接受binding。 */
  readonly bindings: EmptyObject;
}

/** VALUE_CARD组件。 */
export interface DashboardValueCardComponent<L> extends DashboardComponentBase<"VALUE_CARD", L> {
  /** 数值卡显示属性。 */
  readonly props: Readonly<{ title?: Title; precision: number; unitMode: "MODEL" | "NONE" }>;
  /** 单设备当前值slot。 */
  readonly bindings: Readonly<{ value: DashboardCurrentValueBinding }>;
}

/** STATUS组件。 */
export interface DashboardStatusComponent<L> extends DashboardComponentBase<"STATUS", L> {
  /** 状态显示属性。 */
  readonly props: Readonly<{ title?: Title; showLastOnlineAt: boolean }>;
  /** 单设备状态slot。 */
  readonly bindings: Readonly<{ status: DashboardDeviceStatusBinding }>;
}

/** MODEL量程GAUGE属性。 */
export interface DashboardModelGaugeProps {
  /** 可选标题。 */
  readonly title?: Title;
  /** 从模型读取量程。 */
  readonly scaleMode: "MODEL";
  /** 小数精度。 */
  readonly precision: number;
  /** 单位显示模式。 */
  readonly unitMode: "MODEL" | "NONE";
}

/** EXPLICIT量程GAUGE属性。 */
export interface DashboardExplicitGaugeProps {
  /** 可选标题。 */
  readonly title?: Title;
  /** 使用Schema显式量程。 */
  readonly scaleMode: "EXPLICIT";
  /** 显式最小值。 */
  readonly min: ConfigNumber;
  /** 显式最大值。 */
  readonly max: ConfigNumber;
  /** 小数精度。 */
  readonly precision: number;
  /** 单位显示模式。 */
  readonly unitMode: "MODEL" | "NONE";
}

/** GAUGE组件。 */
export interface DashboardGaugeComponent<L> extends DashboardComponentBase<"GAUGE", L> {
  /** 由scaleMode判别的量程属性。 */
  readonly props: DashboardModelGaugeProps | DashboardExplicitGaugeProps;
  /** 单设备数值当前值slot。 */
  readonly bindings: Readonly<{ value: DashboardCurrentValueBinding }>;
}

/** 折线序列显示定义。 */
export interface DashboardSeriesDefinition {
  /** 组件内唯一序列ID。 */
  readonly id: LocalKey;
  /** 序列标签。 */
  readonly label: Title;
}

/** 折线序列binding条目。 */
export interface DashboardSeriesBindingEntry {
  /** 与同序显示定义一致的ID。 */
  readonly id: LocalKey;
  /** 单设备数值历史序列。 */
  readonly value: DashboardHistorySeriesBinding;
}

/** LINE_CHART组件。 */
export interface DashboardLineChartComponent<L> extends DashboardComponentBase<"LINE_CHART", L> {
  /** 折线图显示属性和一至四个序列定义。 */
  readonly props: Readonly<{ title?: Title; showLegend: boolean; series: readonly DashboardSeriesDefinition[] }>;
  /** 与显示定义同序一一对应的历史序列。 */
  readonly bindings: Readonly<{ series: readonly DashboardSeriesBindingEntry[] }>;
}

/** 表格列显示定义。 */
export interface DashboardColumnDefinition {
  /** 组件内唯一列ID。 */
  readonly id: LocalKey;
  /** 列标题。 */
  readonly label: Title;
}

/** 表格列binding条目。 */
export interface DashboardColumnBindingEntry {
  /** 与同序显示定义一致的ID。 */
  readonly id: LocalKey;
  /** 同一多设备变量下的当前属性值。 */
  readonly value: DashboardCurrentValueBinding;
}

/** LIST_VALUE模式TABLE组件。 */
export interface DashboardListValueTableComponent<L> extends DashboardComponentBase<"TABLE", L> {
  /** 单个LIST属性表格的规范化属性。 */
  readonly props: Readonly<{ title?: Title; mode: "LIST_VALUE"; rowLimit: number }>;
  /** 单设备LIST当前值slot。 */
  readonly bindings: Readonly<{ value: DashboardCurrentValueBinding }>;
}

/** DEVICE_VALUES模式TABLE组件。 */
export interface DashboardDeviceValuesTableComponent<L> extends DashboardComponentBase<"TABLE", L> {
  /** 多设备标量列的规范化属性。 */
  readonly props: Readonly<{
    title?: Title;
    mode: "DEVICE_VALUES";
    rowLimit: number;
    columns: readonly DashboardColumnDefinition[];
  }>;
  /** 与列定义同序一一对应的当前值binding。 */
  readonly bindings: Readonly<{ columns: readonly DashboardColumnBindingEntry[] }>;
}

/** JSON_VIEW组件。 */
export interface DashboardJsonViewComponent<L> extends DashboardComponentBase<"JSON_VIEW", L> {
  /** 复合快照显示属性。 */
  readonly props: Readonly<{ title?: Title; initialExpandDepth: number }>;
  /** 单设备OBJECT或LIST当前值slot。 */
  readonly bindings: Readonly<{ value: DashboardCurrentValueBinding }>;
}

/** ALARM_LIST组件。 */
export interface DashboardAlarmListComponent<L> extends DashboardComponentBase<"ALARM_LIST", L> {
  /** 有界告警页显示属性。 */
  readonly props: Readonly<{ title?: Title; pageSize: number; showClearedAt: boolean }>;
  /** 告警列表slot。 */
  readonly bindings: Readonly<{ alarms: DashboardAlarmListBinding }>;
}

/** DEVICE_SELECTOR组件。 */
export interface DashboardDeviceSelectorComponent<L> extends DashboardComponentBase<"DEVICE_SELECTOR", L> {
  /** 有界设备目录显示属性。 */
  readonly props: Readonly<{ title?: Title; placeholder: ShortText; pageSize: number }>;
  /** 设备目录slot。 */
  readonly bindings: Readonly<{ directory: DashboardDeviceDirectoryBinding }>;
}

/** 指定布局坐标系下十组件的封闭联合。 */
export type DashboardComponent<L> =
  | DashboardStaticTextComponent<L>
  | DashboardDynamicTextComponent<L>
  | DashboardImageComponent<L>
  | DashboardValueCardComponent<L>
  | DashboardStatusComponent<L>
  | DashboardGaugeComponent<L>
  | DashboardLineChartComponent<L>
  | DashboardListValueTableComponent<L>
  | DashboardDeviceValuesTableComponent<L>
  | DashboardJsonViewComponent<L>
  | DashboardAlarmListComponent<L>
  | DashboardDeviceSelectorComponent<L>;

/** 指定布局坐标系的页面。 */
export interface DashboardPage<L> {
  /** 全文唯一页面ID。 */
  readonly id: LocalKey;
  /** 页面标题。 */
  readonly title: Title;
  /** 页面内不重叠组件。 */
  readonly components: readonly DashboardComponent<L>[];
}

/** 响应式栅格展示设置。 */
export interface DashboardResponsivePresentation {
  /** 画布判别模式。 */
  readonly mode: "RESPONSIVE_GRID";
  /** 颜色主题。 */
  readonly theme: "LIGHT" | "DARK";
  /** 固定栅格列数24。 */
  readonly columns: 24;
  /** 固定行高8。 */
  readonly rowHeight: 8;
  /** 固定组件间距8。 */
  readonly gap: 8;
}

/** 固定屏展示设置。 */
export interface DashboardFixedPresentation {
  /** 画布判别模式。 */
  readonly mode: "FIXED_SCREEN";
  /** 颜色主题。 */
  readonly theme: "LIGHT" | "DARK";
  /** 固定画布宽度1920。 */
  readonly width: 1920;
  /** 固定画布高度1080。 */
  readonly height: 1080;
  /** 固定缩放策略。 */
  readonly scaleMode: "FIT";
}

/** Dashboard v1根对象共同字段。 */
export interface DashboardSchemaBase {
  /** 冻结Schema判别版本。 */
  readonly schemaVersion: "tc.dashboard/v1";
  /** 规范化后的模型引用列表。 */
  readonly models: readonly DashboardModelReference[];
  /** 规范化后的四类变量列表。 */
  readonly variables: readonly DashboardVariable[];
}

/** 响应式栅格Dashboard v1 Schema。 */
export interface DashboardResponsiveSchemaV1 extends DashboardSchemaBase {
  /** 响应式展示设置。 */
  readonly presentation: DashboardResponsivePresentation;
  /** 一至五个响应式页面。 */
  readonly pages: readonly DashboardPage<DashboardResponsiveLayout>[];
}

/** 固定屏Dashboard v1 Schema。 */
export interface DashboardFixedSchemaV1 extends DashboardSchemaBase {
  /** 固定屏展示设置。 */
  readonly presentation: DashboardFixedPresentation;
  /** 唯一固定屏页面。 */
  readonly pages: readonly [DashboardPage<DashboardFixedLayout>];
}

/** Dashboard v1完整规范化Schema封闭联合。 */
export type DashboardSchemaV1 = DashboardResponsiveSchemaV1 | DashboardFixedSchemaV1;

/** Dashboard v1组件画布模式别名。 */
export type DashboardComponentCanvasMode = DashboardCanvasMode;
