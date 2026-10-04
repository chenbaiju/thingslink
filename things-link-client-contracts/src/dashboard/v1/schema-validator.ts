import {
  dashboardV1Contract,
  type DashboardComponentKind,
  type DashboardPropertyDataType,
} from "./generated.js";
import {
  createStrictJsonInteger,
  createStrictJsonNumber,
  isStrictJsonNumber,
  parseDashboardV1Json,
  type StrictJsonNumber,
  type StrictJsonObject,
  type StrictJsonValue,
} from "./strict-json.js";
import {
  requireCanonicalUuid,
  compareConfigNumbers,
  requireConfigNumber,
  requireJsonInteger,
  requireLocalKey,
  requirePropertyKey,
  requireSemVer,
  requireSha256,
  requireShortText,
  requireTextContent,
  requireTitle,
} from "./value-rules.js";
import { DashboardContractViolation } from "./violation.js";
import type { DashboardSchemaV1 } from "./types.js";

/** 已完成全部内部Schema语义但仍不证明任何外部事实或发布资格的范围标记。 */
export const DASHBOARD_SCHEMA_SCOPE = "COMPLETE_INTERNAL_SCHEMA_SEMANTICS" as const;

/** 包内完整内部Schema语义结果；外部需求解析前仍不可保存、发布或渲染。 */
export interface DashboardSchemaValidationResult {
  /** 已完成结构、默认、引用、布局和十组件组合语义的范围。 */
  readonly scope: typeof DASHBOARD_SCHEMA_SCOPE;
  /** 已完成本阶段默认化且递归冻结的内部JSON树。 */
  readonly normalizedRoot: StrictJsonObject;
  /** 完整内部语义产生、仍须外部端口逐项证明的需求。 */
  readonly unresolvedRequirements: readonly DashboardUnresolvedRequirement[];
}

/** 版本化公开入口返回的完整内部语义结果。 */
export interface DashboardSchemaV1ValidationResult {
  /** 已完成结构、默认、引用、布局和十组件组合语义的范围。 */
  readonly scope: typeof DASHBOARD_SCHEMA_SCOPE;
  /** 可供编辑器和宿主读取、在类型及运行时均递归只读的规范Schema。 */
  readonly schema: DashboardSchemaV1;
  /** 保留Java/Jackson数值语义和规范字段顺序的精确紧凑JSON。 */
  readonly normalizedJson: string;
  /** 仍须由模型、资源、宿主、授权和数据适配端口逐项证明的需求。 */
  readonly unresolvedRequirements: readonly DashboardUnresolvedRequirement[];
}

/** 完整内部Schema语义结束后仍未决的外部需求联合。 */
export type DashboardUnresolvedRequirement =
  | HostComponentRequirement
  | BuiltinResourceRequirement
  | ModelReferenceRequirement
  | DefaultDeviceRequirement
  | ModelPropertyRequirement
  | HistoricalPropertyRequirement
  | ModelGaugeRangeRequirement
  | DataAdapterRequirement;

/** 宿主必须实现精确组件版本及兼容范围的需求。 */
export interface HostComponentRequirement {
  /** 需求判别类型。 */ readonly requirementType: "HOST_COMPONENT";
  /** 组件kind。 */ readonly kind: DashboardComponentKind;
  /** 精确组件版本。 */ readonly componentVersion: "1.0.0" | "1.0.1";
  /** 一项包含下界、一项排除上界的宿主范围。 */ readonly hostRange: Readonly<{ minInclusive: string; maxExclusive: string }>;
}

/** 内置资源ID和摘要必须由宿主证明的需求。 */
export interface BuiltinResourceRequirement {
  /** 需求判别类型。 */ readonly requirementType: "BUILTIN_RESOURCE";
  /** 内置资源ID。 */ readonly resourceId: string;
  /** 资源摘要。 */ readonly digest: string;
}

/** 模型引用存在性、摘要和Profile必须由领域端口证明的需求。 */
export interface ModelReferenceRequirement {
  /** 需求判别类型。 */ readonly requirementType: "MODEL_REFERENCE";
  /** Schema模型别名。 */ readonly modelKey: string;
  /** 模型版本ID。 */ readonly versionId: string;
  /** 摘要算法。 */ readonly digestAlgorithm: "PG_JSONB_TEXT_V1_SHA256";
  /** 模型摘要。 */ readonly digest: string;
  /** 复合属性Profile。 */ readonly profile: "TC_PROPERTY_COMPOSITE_V1";
}

/** 默认设备存在、模型匹配和运行时授权重验需求。 */
export interface DefaultDeviceRequirement {
  /** 需求判别类型。 */ readonly requirementType: "DEFAULT_DEVICE";
  /** 设备变量key。 */ readonly variableKey: string;
  /** 单设备或多设备变量类型。 */ readonly variableType: "DEVICE_SINGLE" | "DEVICE_MULTI";
  /** 变量模型key。 */ readonly modelKey: string;
  /** 默认设备ID。 */ readonly deviceId: string;
}

/** 顶层模型属性及允许dataType需求。 */
export interface ModelPropertyRequirement {
  /** 需求判别类型。 */ readonly requirementType: "MODEL_PROPERTY";
  /** Schema模型别名。 */ readonly modelKey: string;
  /** 顶层属性key。 */ readonly propertyKey: string;
  /** 当前slot允许的属性dataType闭集。 */ readonly allowedDataTypes: readonly DashboardPropertyDataType[];
}

/** 数值历史属性和精确查询参数需求。 */
export interface HistoricalPropertyRequirement {
  /** 需求判别类型。 */ readonly requirementType: "HISTORICAL_PROPERTY";
  /** 单设备变量key。 */ readonly variableKey: string;
  /** Schema模型别名。 */ readonly modelKey: string;
  /** 顶层属性key。 */ readonly propertyKey: string;
  /** 时间范围变量key。 */ readonly timeRangeVariableKey: string;
  /** 历史粒度。 */ readonly granularity: string;
  /** 聚合方式。 */ readonly aggregation: string;
}

/** GAUGE模型量程需求。 */
export interface ModelGaugeRangeRequirement {
  /** 需求判别类型。 */ readonly requirementType: "MODEL_GAUGE_RANGE";
  /** Schema模型别名。 */ readonly modelKey: string;
  /** 顶层数值属性key。 */ readonly propertyKey: string;
}

/** 后续数据适配器能力类型。 */
export type AdapterCapability = "CURRENT_VALUE" | "DEVICE_STATUS" | "COMPOSITE_SNAPSHOT" | "HISTORY_SERIES"
  | "LIST_SNAPSHOT" | "BOUNDED_DEVICE_VALUES" | "BOUNDED_ALARM_PAGE" | "BOUNDED_DEVICE_DIRECTORY_PAGE";

/** 数据源必须有可执行有界适配器的需求。 */
export interface DataAdapterRequirement {
  /** 需求判别类型。 */ readonly requirementType: "DATA_ADAPTER";
  /** 适配器能力。 */ readonly capability: AdapterCapability;
  /** Binding来源。 */ readonly source: BindingSource;
  /** 变量key。 */ readonly variableKey: string;
  /** 变量类型。 */ readonly variableType: VariableType;
  /** 设备变量模型key。 */ readonly modelKey: string;
  /** 属性key。 */ readonly propertyKey: string;
}

/** Binding校验后的内部引用投影。 */
export interface DashboardBindingProjection {
  /** 六种Binding来源。 */
  readonly source: BindingSource;
  /** 主设备或文本变量key。 */
  readonly variableKey: string;
  /** 已解析变量类型。 */
  readonly variableType: VariableType;
  /** 设备变量引用的模型key，非设备变量为空字符串。 */
  readonly modelKey: string;
  /** 属性Binding的顶层属性key。 */
  readonly propertyKey: string;
  /** 历史Binding的时间范围变量key。 */
  readonly timeRangeVariableKey: string;
  /** 历史粒度。 */
  readonly granularity: string;
  /** 历史聚合。 */
  readonly aggregation: string;
}

/**
 * 执行Dashboard v1完整内部结构、默认、引用、布局和十组件组合语义校验。
 *
 * <p>此入口不会证明模型存在、摘要匹配、属性dataType、租户授权、运行时授权或适配器能力，
 * 因而结果仍不能证明宿主兼容或发布资格。</p>
 *
 * @param source 未经解码的原始字节。
 * @returns 包内完整内部语义结果及未决外部需求。
 */
export function validateDashboardV1Schema(source: Uint8Array): DashboardSchemaValidationResult {
  const root = requireObject(thaw(parseDashboardV1Json(source)), "$", ROOT_FIELDS);
  rejectNulls(root, "$");
  requireExactString(root, "schemaVersion", "$", "tc.dashboard/v1");
  const presentation = requireObject(required(root, "presentation", "$"), "$.presentation");
  root.models ??= [];
  root.variables ??= [];
  const models = requireArray(root.models, "$.models");
  const variables = requireArray(root.variables, "$.variables");
  const pages = requireArray(required(root, "pages", "$"), "$.pages");
  const canvas = validatePresentation(presentation);
  const requirements: DashboardUnresolvedRequirement[] = [];
  const modelKeys = validateModels(models, requirements);
  const catalog = validateVariables(variables, modelKeys);
  collectDefaultDeviceRequirements(variables, catalog, requirements);
  validatePages(pages, canvas, catalog, requirements);
  const normalized = freezeJson(root) as StrictJsonObject;
  if (new TextEncoder().encode(stringifyJson(normalized)).length > 512_000) {
    reject("NORMALIZED_TOO_LARGE", "$", "注入默认值后超过512000字节上限");
  }
  return Object.freeze({
    scope: DASHBOARD_SCHEMA_SCOPE,
    normalizedRoot: normalized,
    unresolvedRequirements: Object.freeze(requirements.map((requirement) => deepFreezeRequirement(requirement))),
  });
}

/**
 * 校验并规范化Dashboard v1原始字节，返回唯一版本化公共结果。
 *
 * <p>该入口只完成内部Schema语义。调用方必须继续解析unresolvedRequirements，不能把成功结果
 * 当作模型、资源、授权、适配器或发布资格已经成立。</p>
 *
 * @param source 未经解码的原始UTF-8字节。
 * @returns 深冻结Schema、精确规范JSON、范围和有序未决需求。
 * @throws DashboardContractViolation 原文或内部Schema语义不符合合同时抛出。
 */
export function validateDashboardSchemaV1(source: Uint8Array): DashboardSchemaV1ValidationResult {
  const internal = validateDashboardV1Schema(source);
  const normalizedJson = stringifyJson(internal.normalizedRoot);
  return Object.freeze({
    scope: internal.scope,
    schema: materializePublicSchema(internal.normalizedRoot),
    normalizedJson,
    unresolvedRequirements: internal.unresolvedRequirements,
  });
}

/**
 * 使用已规范化变量列表校验一个独立Binding分支。
 *
 * <p>该辅助入口只供后续组件slot规则消费，不会推导属性dataType、授权或适配能力。</p>
 *
 * @param binding 待校验Binding节点。
 * @param path 稳定内部路径。
 * @param normalizedVariables 已由结构管线校验的变量列表。
 * @returns 冻结的内部引用投影。
 */
export function validateDashboardBinding(
  binding: StrictJsonValue,
  path: string,
  normalizedVariables: StrictJsonValue,
): DashboardBindingProjection {
  const variables = requireArray(thaw(normalizedVariables), "$.variables");
  const catalog = catalogFromNormalizedVariables(variables);
  return Object.freeze(validateBinding(requireObject(thaw(binding), path), path, catalog));
}

/** 根对象允许字段。 */
const ROOT_FIELDS = new Set(["schemaVersion", "presentation", "models", "variables", "pages"]);
/** 十组件kind闭集。 */
const COMPONENT_KINDS = new Set(dashboardV1Contract.components.map(({ kind }) => kind));
/** 三个时间预设闭集。 */
const TIME_PRESETS = ["LAST_1_HOUR", "LAST_24_HOURS", "LAST_7_DAYS"] as const;
/** 四种变量类型。 */
type VariableType = "DEVICE_SINGLE" | "DEVICE_MULTI" | "TIME_RANGE" | "TEXT_ENUM";
/** 六种Binding来源。 */
type BindingSource = "CURRENT_VALUE" | "HISTORY_SERIES" | "DEVICE_STATUS" | "ALARM_LIST" | "DEVICE_DIRECTORY" | "ENUM_TEXT";
/** 内部变量索引条目。 */
interface VariableDefinition { readonly key: string; readonly type: VariableType; readonly modelKey: string; }
/** 可变JSON对象，仅用于隔离副本上的默认注入。 */
interface MutableJsonObject { [key: string]: MutableJsonValue | undefined; }
/** 可变JSON值，仅存在于校验调用栈内部。 */
type MutableJsonValue = null | boolean | string | StrictJsonNumber | MutableJsonValue[] | MutableJsonObject;
/** 画布模式。 */
type CanvasMode = "RESPONSIVE_GRID" | "FIXED_SCREEN";
/** 矩形投影。 */
interface Rectangle { readonly x: number; readonly y: number; readonly w: number; readonly h: number; }
/** Java结构内核接受的最小有符号long整数。 */
const JAVA_LONG_MIN = -9_223_372_036_854_775_808n;
/** Java结构内核接受的最大有符号long整数。 */
const JAVA_LONG_MAX = 9_223_372_036_854_775_807n;

/** 校验两种presentation分支并注入唯一默认值。 */
function validatePresentation(node: MutableJsonObject): CanvasMode {
  const mode = requireEnum(required(node, "mode", "$.presentation"), "$.presentation.mode", ["RESPONSIVE_GRID", "FIXED_SCREEN"]);
  node.theme ??= "LIGHT";
  requireEnum(node.theme, "$.presentation.theme", ["LIGHT", "DARK"]);
  if (mode === "RESPONSIVE_GRID") {
    requireClosed(node, "$.presentation", new Set(["mode", "theme", "columns", "rowHeight", "gap"]));
    node.columns ??= number("24");
    node.rowHeight ??= number("8");
    node.gap ??= number("8");
    requireExactInteger(node, "columns", "$.presentation", 24n);
    requireExactInteger(node, "rowHeight", "$.presentation", 8n);
    requireExactInteger(node, "gap", "$.presentation", 8n);
  } else {
    requireClosed(node, "$.presentation", new Set(["mode", "theme", "width", "height", "scaleMode"]));
    node.width ??= number("1920");
    node.height ??= number("1080");
    node.scaleMode ??= "FIT";
    requireExactInteger(node, "width", "$.presentation", 1920n);
    requireExactInteger(node, "height", "$.presentation", 1080n);
    requireEnum(node.scaleMode, "$.presentation.scaleMode", ["FIT"]);
  }
  return mode;
}

/** 校验模型引用结构、固定常量与双重唯一性。 */
function validateModels(models: MutableJsonValue[], requirements: DashboardUnresolvedRequirement[]): Set<string> {
  requireSize(models, "$.models", 0, 20);
  const keys = new Set<string>();
  const versionIds = new Set<string>();
  models.forEach((value, index) => {
    const path = `$.models[${index}]`;
    const model = requireObject(value, path, new Set(["key", "versionId", "digestAlgorithm", "digest", "profile"]));
    const key = requireLocalKey(asStrict(required(model, "key", path)), `${path}.key`);
    const versionId = requireCanonicalUuid(asStrict(required(model, "versionId", path)), `${path}.versionId`);
    const digestAlgorithm = requireString(required(model, "digestAlgorithm", path), `${path}.digestAlgorithm`);
    const digest = requireSha256(asStrict(required(model, "digest", path)), `${path}.digest`);
    const profile = requireString(required(model, "profile", path), `${path}.profile`);
    if (digestAlgorithm !== "PG_JSONB_TEXT_V1_SHA256" || profile !== "TC_PROPERTY_COMPOSITE_V1") {
      reject("INVALID_VALUE", path, "模型摘要算法或Profile不受支持");
    }
    if (keys.has(key) || versionIds.has(versionId)) reject("INVALID_COLLECTION", path, "模型key和versionId都必须全文唯一");
    keys.add(key);
    versionIds.add(versionId);
    requirements.push({
      requirementType: "MODEL_REFERENCE",
      modelKey: key,
      versionId,
      digestAlgorithm: "PG_JSONB_TEXT_V1_SHA256",
      digest,
      profile: "TC_PROPERTY_COMPOSITE_V1",
    });
  });
  return keys;
}

/** 收集默认设备仍须证明的存在、模型匹配和运行时授权需求。 */
function collectDefaultDeviceRequirements(variables: MutableJsonValue[], catalog: ReadonlyMap<string, VariableDefinition>, requirements: DashboardUnresolvedRequirement[]): void {
  variables.forEach((value, index) => {
    const variable = requireObject(value, `$.variables[${index}]`);
    const key = requireString(required(variable, "key", `$.variables[${index}]`), `$.variables[${index}].key`);
    const definition = catalog.get(key)!;
    if (definition.type === "DEVICE_SINGLE" && typeof variable.defaultDeviceId === "string") {
      requirements.push({ requirementType: "DEFAULT_DEVICE", variableKey: key, variableType: "DEVICE_SINGLE", modelKey: definition.modelKey, deviceId: variable.defaultDeviceId });
    }
    if (definition.type === "DEVICE_MULTI") {
      requireArray(variable.defaultDeviceIds!, `$.variables[${index}].defaultDeviceIds`).forEach((deviceId) => {
        requirements.push({ requirementType: "DEFAULT_DEVICE", variableKey: key, variableType: "DEVICE_MULTI", modelKey: definition.modelKey, deviceId: deviceId as string });
      });
    }
  });
}

/** 先完整建立四变量索引，再供页面Binding执行前向引用。 */
function validateVariables(variables: MutableJsonValue[], modelKeys: ReadonlySet<string>): Map<string, VariableDefinition> {
  requireSize(variables, "$.variables", 0, 20);
  const catalog = new Map<string, VariableDefinition>();
  variables.forEach((value, index) => {
    const path = `$.variables[${index}]`;
    const variable = requireObject(value, path);
    const key = requireLocalKey(asStrict(required(variable, "key", path)), `${path}.key`);
    if (modelKeys.has(key)) reject("INVALID_COLLECTION", `${path}.key`, "变量key不得与model key混用命名空间");
    const type = requireEnum(required(variable, "type", path), `${path}.type`, ["DEVICE_SINGLE", "DEVICE_MULTI", "TIME_RANGE", "TEXT_ENUM"]);
    requireTitle(asStrict(required(variable, "title", path)), `${path}.title`);
    variable.required ??= true;
    requireBoolean(variable.required, `${path}.required`);
    let modelKey = "";
    if (type === "DEVICE_SINGLE") modelKey = validateSingleDevice(variable, path, modelKeys);
    if (type === "DEVICE_MULTI") modelKey = validateMultiDevice(variable, path, modelKeys);
    if (type === "TIME_RANGE") validateTimeRange(variable, path);
    if (type === "TEXT_ENUM") validateTextEnum(variable, path);
    if (catalog.has(key)) reject("INVALID_COLLECTION", `${path}.key`, "变量key必须全文唯一");
    catalog.set(key, { key, type, modelKey });
  });
  return catalog;
}

/** 校验单设备变量。 */
function validateSingleDevice(variable: MutableJsonObject, path: string, modelKeys: ReadonlySet<string>): string {
  requireClosed(variable, path, new Set(["key", "type", "title", "required", "modelKey", "defaultDeviceId"]));
  const modelKey = requireModelKey(variable, path, modelKeys);
  if (variable.defaultDeviceId !== undefined) requireCanonicalUuid(asStrict(variable.defaultDeviceId), `${path}.defaultDeviceId`);
  return modelKey;
}

/** 校验多设备变量、上限、默认集合和去重。 */
function validateMultiDevice(variable: MutableJsonObject, path: string, modelKeys: ReadonlySet<string>): string {
  requireClosed(variable, path, new Set(["key", "type", "title", "required", "modelKey", "maxItems", "defaultDeviceIds"]));
  const modelKey = requireModelKey(variable, path, modelKeys);
  variable.maxItems ??= number("20");
  const maximum = Number(requireStructuralInteger(asStrict(variable.maxItems), `${path}.maxItems`, 1n, 20n));
  variable.defaultDeviceIds ??= [];
  const defaults = requireArray(variable.defaultDeviceIds, `${path}.defaultDeviceIds`);
  requireSize(defaults, `${path}.defaultDeviceIds`, 0, maximum);
  requireUnique(defaults.map((value, index) => requireCanonicalUuid(asStrict(value), `${path}.defaultDeviceIds[${index}]`)), `${path}.defaultDeviceIds`);
  return modelKey;
}

/** 校验时间范围变量默认值、闭集与内部引用。 */
function validateTimeRange(variable: MutableJsonObject, path: string): void {
  requireClosed(variable, path, new Set(["key", "type", "title", "required", "defaultPreset", "allowedPresets"]));
  variable.defaultPreset ??= "LAST_1_HOUR";
  const preset = requireEnum(variable.defaultPreset, `${path}.defaultPreset`, TIME_PRESETS);
  variable.allowedPresets ??= [...TIME_PRESETS];
  const allowed = requireArray(variable.allowedPresets, `${path}.allowedPresets`);
  requireSize(allowed, `${path}.allowedPresets`, 1, TIME_PRESETS.length);
  const values = allowed.map((value, index) => requireEnum(value, `${path}.allowedPresets[${index}]`, TIME_PRESETS));
  requireUnique(values, `${path}.allowedPresets`);
  if (!values.includes(preset)) reject("INVALID_REFERENCE", `${path}.defaultPreset`, "必须引用allowedPresets中的值");
}

/** 校验文本枚举选项与默认引用。 */
function validateTextEnum(variable: MutableJsonObject, path: string): void {
  requireClosed(variable, path, new Set(["key", "type", "title", "required", "options", "defaultValue"]));
  const options = requireArray(required(variable, "options", path), `${path}.options`);
  requireSize(options, `${path}.options`, 1, 20);
  const values = options.map((value, index) => {
    const optionPath = `${path}.options[${index}]`;
    const option = requireObject(value, optionPath, new Set(["value", "label"]));
    const optionValue = requireLocalKey(asStrict(required(option, "value", optionPath)), `${optionPath}.value`);
    requireTitle(asStrict(required(option, "label", optionPath)), `${optionPath}.label`);
    return optionValue;
  });
  requireUnique(values, `${path}.options`);
  if (variable.defaultValue !== undefined) {
    const defaultValue = requireLocalKey(asStrict(variable.defaultValue), `${path}.defaultValue`);
    if (!values.includes(defaultValue)) reject("INVALID_REFERENCE", `${path}.defaultValue`, "必须引用已声明option value");
  }
}

/** 校验页面、组件壳、全局ID和同页布局。 */
function validatePages(
  pages: MutableJsonValue[],
  mode: CanvasMode,
  catalog: ReadonlyMap<string, VariableDefinition>,
  requirements: DashboardUnresolvedRequirement[],
): void {
  requireSize(pages, "$.pages", 1, mode === "RESPONSIVE_GRID" ? 5 : 1);
  const pageIds = new Set<string>();
  const componentIds = new Set<string>();
  pages.forEach((value, pageIndex) => {
    const path = `$.pages[${pageIndex}]`;
    const page = requireObject(value, path, new Set(["id", "title", "components"]));
    const pageId = requireLocalKey(asStrict(required(page, "id", path)), `${path}.id`);
    if (pageIds.has(pageId)) reject("INVALID_COLLECTION", path, "页面id必须全文唯一");
    pageIds.add(pageId);
    requireTitle(asStrict(required(page, "title", path)), `${path}.title`);
    const components = requireArray(required(page, "components", path), `${path}.components`);
    requireSize(components, `${path}.components`, 0, 50);
    const rectangles: Rectangle[] = [];
    components.forEach((componentValue, componentIndex) => {
      const componentPath = `${path}.components[${componentIndex}]`;
      const component = requireObject(componentValue, componentPath,
        new Set(["id", "kind", "componentVersion", "layout", "props", "bindings"]));
      const id = requireLocalKey(asStrict(required(component, "id", componentPath)), `${componentPath}.id`);
      if (componentIds.has(id)) reject("INVALID_COLLECTION", componentPath, "组件id必须全文唯一");
      componentIds.add(id);
      const kind = requireString(required(component, "kind", componentPath), `${componentPath}.kind`);
      const version = requireSemVer(asStrict(required(component, "componentVersion", componentPath)), `${componentPath}.componentVersion`);
      if (!COMPONENT_KINDS.has(kind as never) || !["1.0.0", "1.0.1"].includes(version)) {
        reject("INVALID_VALUE", componentPath, "组件kind或精确版本不受支持");
      }
      const rectangle = validateLayout(requireObject(required(component, "layout", componentPath), `${componentPath}.layout`, new Set(["x", "y", "w", "h"])), `${componentPath}.layout`, mode);
      requireObject(required(component, "props", componentPath), `${componentPath}.props`);
      requireObject(required(component, "bindings", componentPath), `${componentPath}.bindings`);
      validateComponentSemantics(component, componentPath, catalog, requirements);
      if (rectangles.some((previous) => overlaps(rectangle, previous))) reject("INVALID_LAYOUT", `${componentPath}.layout`, "同一页组件矩形不得重叠");
      rectangles.push(rectangle);
    });
  });
}

/** 校验十组件精确props、slot、默认值、组合语义并投影外部需求。 */
function validateComponentSemantics(
  component: MutableJsonObject,
  path: string,
  catalog: ReadonlyMap<string, VariableDefinition>,
  requirements: DashboardUnresolvedRequirement[],
): void {
  const kind = requireString(component.kind!, `${path}.kind`) as DashboardComponentKind;
  const props = requireObject(component.props!, `${path}.props`);
  const bindings = requireObject(component.bindings!, `${path}.bindings`);
  const version = requireString(component.componentVersion!, `${path}.componentVersion`) as "1.0.0" | "1.0.1";
  const descriptor = descriptorFor(kind, version);
  requirements.push({
    requirementType: "HOST_COMPONENT",
    kind,
    componentVersion: version,
    hostRange: { minInclusive: descriptor.hostRange.minInclusive, maxExclusive: descriptor.hostRange.maxExclusive },
  });
  if (kind === "TEXT") validateText(props, bindings, path, catalog);
  if (kind === "IMAGE") validateImage(props, bindings, path, requirements);
  if (kind === "VALUE_CARD") validateValueCard(props, bindings, path, catalog, requirements);
  if (kind === "STATUS") validateStatus(props, bindings, path, catalog, requirements);
  if (kind === "GAUGE") validateGauge(props, bindings, path, catalog, requirements);
  if (kind === "LINE_CHART") validateLineChart(props, bindings, path, catalog, requirements);
  if (kind === "TABLE") validateTable(props, bindings, path, catalog, requirements);
  if (kind === "JSON_VIEW") validateJsonView(props, bindings, path, catalog, requirements);
  if (kind === "ALARM_LIST") validateAlarmList(props, bindings, path, catalog, requirements);
  if (kind === "DEVICE_SELECTOR") validateDeviceSelector(props, bindings, path, catalog, requirements);
}

/** 校验TEXT静态与动态来源互斥和显示默认值。 */
function validateText(props: MutableJsonObject, bindings: MutableJsonObject, path: string, catalog: ReadonlyMap<string, VariableDefinition>): void {
  const propsPath = `${path}.props`;
  const bindingsPath = `${path}.bindings`;
  requireClosed(props, propsPath, new Set(["content", "align", "size", "tone"]));
  requireClosed(bindings, bindingsPath, new Set(["text"]));
  const dynamic = bindings.text !== undefined;
  if (dynamic && props.content !== undefined) reject("INVALID_VALUE", path, "TEXT不得同时定义content与text slot");
  if (dynamic) validateSlot(bindings, "text", bindingsPath, catalog, "ENUM_TEXT");
  else {
    putPropDefault(props, "TEXT", "content");
    requireTextContent(asStrict(required(props, "content", propsPath)), `${propsPath}.content`);
  }
  putPropDefault(props, "TEXT", "align");
  putPropDefault(props, "TEXT", "size");
  putPropDefault(props, "TEXT", "tone");
  requireEnum(props.align!, `${propsPath}.align`, dashboardV1Contract.enumSets.textAlignments);
  requireEnum(props.size!, `${propsPath}.size`, dashboardV1Contract.enumSets.textSizes);
  requireEnum(props.tone!, `${propsPath}.tone`, dashboardV1Contract.enumSets.textTones);
}

/** 校验IMAGE资源字段、显示默认和空slot。 */
function validateImage(props: MutableJsonObject, bindings: MutableJsonObject, path: string, requirements: DashboardUnresolvedRequirement[]): void {
  const propsPath = `${path}.props`;
  requireClosed(props, propsPath, new Set(["title", "resourceId", "resourceDigest", "alt", "fit"]));
  requireClosed(bindings, `${path}.bindings`, new Set());
  validateOptionalTitle(props, propsPath);
  const resourceId = requireLocalKey(asStrict(required(props, "resourceId", propsPath)), `${propsPath}.resourceId`);
  const digest = requireSha256(asStrict(required(props, "resourceDigest", propsPath)), `${propsPath}.resourceDigest`);
  requireShortText(asStrict(required(props, "alt", propsPath)), `${propsPath}.alt`);
  putPropDefault(props, "IMAGE", "fit");
  requireEnum(props.fit!, `${propsPath}.fit`, dashboardV1Contract.enumSets.imageFits);
  requirements.push({ requirementType: "BUILTIN_RESOURCE", resourceId, digest });
}

/** 校验VALUE_CARD单设备当前值和显示默认值。 */
function validateValueCard(props: MutableJsonObject, bindings: MutableJsonObject, path: string, catalog: ReadonlyMap<string, VariableDefinition>, requirements: DashboardUnresolvedRequirement[]): void {
  const propsPath = `${path}.props`;
  const bindingsPath = `${path}.bindings`;
  requireClosed(props, propsPath, new Set(["title", "precision", "unitMode"]));
  requireClosed(bindings, bindingsPath, new Set(["value"]));
  validateOptionalTitle(props, propsPath);
  putPropDefault(props, "VALUE_CARD", "precision");
  requireIntegerField(props, "precision", propsPath, 0n, 6n);
  putPropDefault(props, "VALUE_CARD", "unitMode");
  requireEnum(props.unitMode!, `${propsPath}.unitMode`, dashboardV1Contract.enumSets.unitModes);
  const binding = validateSlot(bindings, "value", bindingsPath, catalog, "CURRENT_VALUE");
  requireSingleDevice(binding, `${bindingsPath}.value`);
  addModelProperty(requirements, binding, supportedDataTypes("VALUE_CARD"));
  requirements.push(adapterRequirement(binding, "CURRENT_VALUE"));
}

/** 校验STATUS单设备状态slot和默认值。 */
function validateStatus(props: MutableJsonObject, bindings: MutableJsonObject, path: string, catalog: ReadonlyMap<string, VariableDefinition>, requirements: DashboardUnresolvedRequirement[]): void {
  const propsPath = `${path}.props`;
  const bindingsPath = `${path}.bindings`;
  requireClosed(props, propsPath, new Set(["title", "showLastOnlineAt"]));
  requireClosed(bindings, bindingsPath, new Set(["status"]));
  validateOptionalTitle(props, propsPath);
  putPropDefault(props, "STATUS", "showLastOnlineAt");
  requireBoolean(props.showLastOnlineAt!, `${propsPath}.showLastOnlineAt`);
  const binding = validateSlot(bindings, "status", bindingsPath, catalog, "DEVICE_STATUS");
  requireSingleDevice(binding, `${bindingsPath}.status`);
  requirements.push(adapterRequirement(binding, "DEVICE_STATUS"));
}

/** 校验GAUGE量程分支、当前值slot和外部量程需求。 */
function validateGauge(props: MutableJsonObject, bindings: MutableJsonObject, path: string, catalog: ReadonlyMap<string, VariableDefinition>, requirements: DashboardUnresolvedRequirement[]): void {
  const propsPath = `${path}.props`;
  const bindingsPath = `${path}.bindings`;
  requireClosed(props, propsPath, new Set(["title", "scaleMode", "min", "max", "precision", "unitMode"]));
  requireClosed(bindings, bindingsPath, new Set(["value"]));
  validateOptionalTitle(props, propsPath);
  putPropDefault(props, "GAUGE", "scaleMode");
  const scaleMode = requireEnum(props.scaleMode!, `${propsPath}.scaleMode`, dashboardV1Contract.enumSets.gaugeScaleModes);
  putPropDefault(props, "GAUGE", "precision");
  requireIntegerField(props, "precision", propsPath, 0n, 6n);
  putPropDefault(props, "GAUGE", "unitMode");
  requireEnum(props.unitMode!, `${propsPath}.unitMode`, dashboardV1Contract.enumSets.unitModes);
  if (scaleMode === "MODEL") {
    if (props.min !== undefined || props.max !== undefined) reject("INVALID_VALUE", propsPath, "MODEL量程禁止声明min或max");
  } else {
    // 与Java先取齐两个必填节点再校验数值一致，缺失字段必须先于另一字段的值错误返回。
    const minimumValue = required(props, "min", propsPath);
    const maximumValue = required(props, "max", propsPath);
    const minimum = requireConfigNumber(asStrict(minimumValue), `${propsPath}.min`);
    const maximum = requireConfigNumber(asStrict(maximumValue), `${propsPath}.max`);
    if (compareConfigNumbers(minimum, maximum) >= 0) reject("INVALID_VALUE", propsPath, "显式量程必须满足min小于max");
  }
  const binding = validateSlot(bindings, "value", bindingsPath, catalog, "CURRENT_VALUE");
  requireSingleDevice(binding, `${bindingsPath}.value`);
  addModelProperty(requirements, binding, supportedDataTypes("GAUGE"));
  if (scaleMode === "MODEL") requirements.push({ requirementType: "MODEL_GAUGE_RANGE", modelKey: binding.modelKey, propertyKey: binding.propertyKey });
  requirements.push(adapterRequirement(binding, "CURRENT_VALUE"));
}

/** 校验JSON_VIEW复合快照slot和展开深度。 */
function validateJsonView(props: MutableJsonObject, bindings: MutableJsonObject, path: string, catalog: ReadonlyMap<string, VariableDefinition>, requirements: DashboardUnresolvedRequirement[]): void {
  const propsPath = `${path}.props`;
  const bindingsPath = `${path}.bindings`;
  requireClosed(props, propsPath, new Set(["title", "initialExpandDepth"]));
  requireClosed(bindings, bindingsPath, new Set(["value"]));
  validateOptionalTitle(props, propsPath);
  putPropDefault(props, "JSON_VIEW", "initialExpandDepth");
  requireIntegerField(props, "initialExpandDepth", propsPath, 0n, 2n);
  const binding = validateSlot(bindings, "value", bindingsPath, catalog, "CURRENT_VALUE");
  requireSingleDevice(binding, `${bindingsPath}.value`);
  addModelProperty(requirements, binding, supportedDataTypes("JSON_VIEW"));
  requirements.push(adapterRequirement(binding, "COMPOSITE_SNAPSHOT"));
}

/** 校验LINE_CHART两侧序列一一对应和完整历史Binding去重。 */
function validateLineChart(props: MutableJsonObject, bindings: MutableJsonObject, path: string, catalog: ReadonlyMap<string, VariableDefinition>, requirements: DashboardUnresolvedRequirement[]): void {
  const propsPath = `${path}.props`;
  const bindingsPath = `${path}.bindings`;
  requireClosed(props, propsPath, new Set(["title", "showLegend", "series"]));
  requireClosed(bindings, bindingsPath, new Set(["series"]));
  validateOptionalTitle(props, propsPath);
  putPropDefault(props, "LINE_CHART", "showLegend");
  requireBoolean(props.showLegend!, `${propsPath}.showLegend`);
  const definitions = requireArray(required(props, "series", propsPath), `${propsPath}.series`);
  const entries = requireArray(required(bindings, "series", bindingsPath), `${bindingsPath}.series`);
  requireSize(definitions, `${propsPath}.series`, 1, 4);
  requireSize(entries, `${bindingsPath}.series`, 1, 4);
  if (definitions.length !== entries.length) reject("INVALID_REFERENCE", `${bindingsPath}.series`, "必须与props.series数量及顺序一一对应");
  const ids = new Set<string>();
  const completeBindings = new Set<string>();
  definitions.forEach((value, index) => {
    const definitionPath = `${propsPath}.series[${index}]`;
    const definition = requireObject(value, definitionPath, new Set(["id", "label"]));
    const id = requireLocalKey(asStrict(required(definition, "id", definitionPath)), `${definitionPath}.id`);
    requireTitle(asStrict(required(definition, "label", definitionPath)), `${definitionPath}.label`);
    if (ids.has(id)) reject("INVALID_COLLECTION", `${propsPath}.series`, "series id不得重复");
    ids.add(id);
    const entryPath = `${bindingsPath}.series[${index}]`;
    const entry = requireObject(entries[index]!, entryPath, new Set(["id", "value"]));
    const bindingId = requireLocalKey(asStrict(required(entry, "id", entryPath)), `${entryPath}.id`);
    if (bindingId !== id) reject("INVALID_REFERENCE", `${entryPath}.id`, "必须与同序props.series id一致");
    const binding = validateSlot(entry, "value", entryPath, catalog, "HISTORY_SERIES");
    requireSingleDevice(binding, `${entryPath}.value`);
    const identity = bindingIdentity(binding);
    if (completeBindings.has(identity)) reject("INVALID_COLLECTION", `${bindingsPath}.series`, "相同完整历史数据Binding不得重复");
    completeBindings.add(identity);
    requirements.push({
      requirementType: "HISTORICAL_PROPERTY",
      variableKey: binding.variableKey,
      modelKey: binding.modelKey,
      propertyKey: binding.propertyKey,
      timeRangeVariableKey: binding.timeRangeVariableKey,
      granularity: binding.granularity,
      aggregation: binding.aggregation,
    });
    requirements.push(adapterRequirement(binding, "HISTORY_SERIES"));
  });
}

/** 校验TABLE模式判别及两种行来源组合。 */
function validateTable(props: MutableJsonObject, bindings: MutableJsonObject, path: string, catalog: ReadonlyMap<string, VariableDefinition>, requirements: DashboardUnresolvedRequirement[]): void {
  const propsPath = `${path}.props`;
  const bindingsPath = `${path}.bindings`;
  requireClosed(props, propsPath, new Set(["title", "mode", "rowLimit", "columns"]));
  validateOptionalTitle(props, propsPath);
  const mode = requireEnum(required(props, "mode", propsPath), `${propsPath}.mode`, dashboardV1Contract.enumSets.tableModes);
  putPropDefault(props, "TABLE", "rowLimit");
  requireIntegerField(props, "rowLimit", propsPath, 1n, 256n);
  if (mode === "LIST_VALUE") {
    if (props.columns !== undefined) reject("INVALID_VALUE", propsPath, "LIST_VALUE禁止声明columns");
    requireClosed(bindings, bindingsPath, new Set(["value"]));
    const binding = validateSlot(bindings, "value", bindingsPath, catalog, "CURRENT_VALUE");
    requireSingleDevice(binding, `${bindingsPath}.value`);
    addModelProperty(requirements, binding, ["LIST"]);
    requirements.push(adapterRequirement(binding, "LIST_SNAPSHOT"));
    return;
  }
  requireClosed(bindings, bindingsPath, new Set(["columns"]));
  const definitions = requireArray(required(props, "columns", propsPath), `${propsPath}.columns`);
  const entries = requireArray(required(bindings, "columns", bindingsPath), `${bindingsPath}.columns`);
  requireSize(definitions, `${propsPath}.columns`, 1, 10);
  requireSize(entries, `${bindingsPath}.columns`, 1, 10);
  if (definitions.length !== entries.length) reject("INVALID_REFERENCE", `${bindingsPath}.columns`, "必须与props.columns数量及顺序一一对应");
  const ids = new Set<string>();
  let sharedVariableKey: string | undefined;
  definitions.forEach((value, index) => {
    const definitionPath = `${propsPath}.columns[${index}]`;
    const definition = requireObject(value, definitionPath, new Set(["id", "label"]));
    const id = requireLocalKey(asStrict(required(definition, "id", definitionPath)), `${definitionPath}.id`);
    requireTitle(asStrict(required(definition, "label", definitionPath)), `${definitionPath}.label`);
    if (ids.has(id)) reject("INVALID_COLLECTION", `${propsPath}.columns`, "column id不得重复");
    ids.add(id);
    const entryPath = `${bindingsPath}.columns[${index}]`;
    const entry = requireObject(entries[index]!, entryPath, new Set(["id", "value"]));
    const bindingId = requireLocalKey(asStrict(required(entry, "id", entryPath)), `${entryPath}.id`);
    if (bindingId !== id) reject("INVALID_REFERENCE", `${entryPath}.id`, "必须与同序props.columns id一致");
    const binding = validateSlot(entry, "value", entryPath, catalog, "CURRENT_VALUE");
    if (binding.variableType !== "DEVICE_MULTI") reject("INVALID_REFERENCE", `${entryPath}.value`, "DEVICE_VALUES只接受DEVICE_MULTI变量");
    if (sharedVariableKey !== undefined && sharedVariableKey !== binding.variableKey) reject("INVALID_REFERENCE", `${bindingsPath}.columns`, "所有列必须引用同一DEVICE_MULTI变量");
    sharedVariableKey = binding.variableKey;
    addModelProperty(requirements, binding, ["NUMBER", "TEXT", "SWITCH", "ENUM"]);
    requirements.push(adapterRequirement(binding, "BOUNDED_DEVICE_VALUES"));
  });
}

/** 校验ALARM_LIST过滤slot页和有界分页默认。 */
function validateAlarmList(props: MutableJsonObject, bindings: MutableJsonObject, path: string, catalog: ReadonlyMap<string, VariableDefinition>, requirements: DashboardUnresolvedRequirement[]): void {
  const propsPath = `${path}.props`;
  const bindingsPath = `${path}.bindings`;
  requireClosed(props, propsPath, new Set(["title", "pageSize", "showClearedAt"]));
  requireClosed(bindings, bindingsPath, new Set(["alarms"]));
  validateOptionalTitle(props, propsPath);
  putPropDefault(props, "ALARM_LIST", "pageSize");
  requireIntegerField(props, "pageSize", propsPath, 1n, 50n);
  putPropDefault(props, "ALARM_LIST", "showClearedAt");
  requireBoolean(props.showClearedAt!, `${propsPath}.showClearedAt`);
  const binding = validateSlot(bindings, "alarms", bindingsPath, catalog, "ALARM_LIST");
  requirements.push(adapterRequirement(binding, "BOUNDED_ALARM_PAGE"));
}

/** 校验DEVICE_SELECTOR目录slot页与短文本默认。 */
function validateDeviceSelector(props: MutableJsonObject, bindings: MutableJsonObject, path: string, catalog: ReadonlyMap<string, VariableDefinition>, requirements: DashboardUnresolvedRequirement[]): void {
  const propsPath = `${path}.props`;
  const bindingsPath = `${path}.bindings`;
  requireClosed(props, propsPath, new Set(["title", "placeholder", "pageSize"]));
  requireClosed(bindings, bindingsPath, new Set(["directory"]));
  validateOptionalTitle(props, propsPath);
  putPropDefault(props, "DEVICE_SELECTOR", "placeholder");
  requireShortText(asStrict(props.placeholder!), `${propsPath}.placeholder`);
  putPropDefault(props, "DEVICE_SELECTOR", "pageSize");
  requireIntegerField(props, "pageSize", propsPath, 1n, 50n);
  const binding = validateSlot(bindings, "directory", bindingsPath, catalog, "DEVICE_DIRECTORY");
  requirements.push(adapterRequirement(binding, "BOUNDED_DEVICE_DIRECTORY_PAGE"));
}

/** 校验可选title但不为其注入默认。 */
function validateOptionalTitle(props: MutableJsonObject, path: string): void {
  if (props.title !== undefined) requireTitle(asStrict(props.title), `${path}.title`);
}

/** 校验slot存在、对象类型、Binding分支及期望source。 */
function validateSlot(bindings: MutableJsonObject, name: string, path: string, catalog: ReadonlyMap<string, VariableDefinition>, expected: BindingSource): DashboardBindingProjection {
  const slotPath = `${path}.${name}`;
  const binding = validateBinding(requireObject(required(bindings, name, path), slotPath), slotPath, catalog);
  if (binding.source !== expected) reject("INVALID_REFERENCE", slotPath, "slot的Binding source不匹配");
  return binding;
}

/** 要求属性型组件使用单设备变量。 */
function requireSingleDevice(binding: DashboardBindingProjection, path: string): void {
  if (binding.variableType !== "DEVICE_SINGLE") reject("INVALID_REFERENCE", path, "只接受DEVICE_SINGLE变量");
}

/** 为缺失组件属性注入机器描述符中的唯一默认值。 */
function putPropDefault(props: MutableJsonObject, kind: DashboardComponentKind, name: string): void {
  if (props[name] !== undefined) return;
  const descriptor = descriptorFor(kind);
  const definition = (descriptor.props as Readonly<Record<string, Readonly<Record<string, unknown>>>>)[name];
  if (definition === undefined || !("default" in definition)) throw new Error(`${kind}.${name}缺少机器默认值`);
  const value = definition.default;
  if (typeof value === "number") props[name] = createStrictJsonInteger(String(value));
  else if (typeof value === "string" || typeof value === "boolean") props[name] = value;
  else throw new Error(`${kind}.${name}机器默认值类型不受支持`);
}

/** 返回机器合同中的组件描述符。 */
function descriptorFor(kind: DashboardComponentKind, version = "1.0.0"): (typeof dashboardV1Contract.components)[number] {
  const descriptor = dashboardV1Contract.components.find((candidate) => candidate.kind === kind && candidate.componentVersion === version);
  if (descriptor === undefined) throw new Error("组件描述符登记不一致");
  return descriptor;
}

/** 返回机器描述符声明的属性dataType闭集。 */
function supportedDataTypes(kind: DashboardComponentKind): readonly DashboardPropertyDataType[] {
  return descriptorFor(kind).supportedDataTypes.values as readonly DashboardPropertyDataType[];
}

/** 要求对象字段为指定闭区间JSON整数。 */
function requireIntegerField(object: MutableJsonObject, name: string, path: string, minimum: bigint, maximum: bigint): bigint {
  return requireStructuralInteger(asStrict(required(object, name, path)), `${path}.${name}`, minimum, maximum);
}

/** 添加模型属性未决需求。 */
function addModelProperty(requirements: DashboardUnresolvedRequirement[], binding: DashboardBindingProjection, allowedDataTypes: readonly DashboardPropertyDataType[]): void {
  requirements.push({ requirementType: "MODEL_PROPERTY", modelKey: binding.modelKey, propertyKey: binding.propertyKey, allowedDataTypes: [...allowedDataTypes] });
}

/** 创建数据适配器未决需求。 */
function adapterRequirement(binding: DashboardBindingProjection, capability: AdapterCapability): DataAdapterRequirement {
  return {
    requirementType: "DATA_ADAPTER",
    capability,
    source: binding.source,
    variableKey: binding.variableKey,
    variableType: binding.variableType,
    modelKey: binding.modelKey,
    propertyKey: binding.propertyKey,
  };
}

/** 形成完整Binding去重身份。 */
function bindingIdentity(binding: DashboardBindingProjection): string {
  return [binding.source, binding.variableKey, binding.variableType, binding.modelKey, binding.propertyKey,
    binding.timeRangeVariableKey, binding.granularity, binding.aggregation].join("\u0000");
}

/** 校验画布矩形整数、范围和边界。 */
function validateLayout(layout: MutableJsonObject, path: string, mode: CanvasMode): Rectangle {
  const maxX = mode === "RESPONSIVE_GRID" ? 23 : 1919;
  const maxY = mode === "RESPONSIVE_GRID" ? 999 : 1079;
  const maxW = mode === "RESPONSIVE_GRID" ? 24 : 1920;
  const maxH = mode === "RESPONSIVE_GRID" ? 1000 : 1080;
  const x = Number(requireStructuralInteger(asStrict(required(layout, "x", path)), `${path}.x`, 0n, BigInt(maxX)));
  const y = Number(requireStructuralInteger(asStrict(required(layout, "y", path)), `${path}.y`, 0n, BigInt(maxY)));
  const w = Number(requireStructuralInteger(asStrict(required(layout, "w", path)), `${path}.w`, 1n, BigInt(maxW)));
  const h = Number(requireStructuralInteger(asStrict(required(layout, "h", path)), `${path}.h`, 1n, BigInt(maxH)));
  if (x + w > maxW || y + h > maxH) reject("INVALID_LAYOUT", path, "组件矩形越过画布边界");
  return { x, y, w, h };
}

/** 判断两个矩形是否存在正面积交叠。 */
function overlaps(left: Rectangle, right: Rectangle): boolean {
  return left.x < right.x + right.w && right.x < left.x + left.w
    && left.y < right.y + right.h && right.y < left.y + left.h;
}

/** 从已规范化变量重建Binding所需索引。 */
function catalogFromNormalizedVariables(variables: MutableJsonValue[]): Map<string, VariableDefinition> {
  const catalog = new Map<string, VariableDefinition>();
  variables.forEach((value, index) => {
    const variable = requireObject(value, `$.variables[${index}]`);
    const key = requireString(required(variable, "key", `$.variables[${index}]`), `$.variables[${index}].key`);
    const type = requireEnum(required(variable, "type", `$.variables[${index}]`), `$.variables[${index}].type`, ["DEVICE_SINGLE", "DEVICE_MULTI", "TIME_RANGE", "TEXT_ENUM"]);
    const modelKey = typeof variable.modelKey === "string" ? variable.modelKey : "";
    catalog.set(key, { key, type, modelKey });
  });
  return catalog;
}

/** 校验六种Binding精确分支。 */
function validateBinding(binding: MutableJsonObject, path: string, catalog: ReadonlyMap<string, VariableDefinition>): DashboardBindingProjection {
  const source = requireEnum(required(binding, "source", path), `${path}.source`, ["CURRENT_VALUE", "HISTORY_SERIES", "DEVICE_STATUS", "ALARM_LIST", "DEVICE_DIRECTORY", "ENUM_TEXT"]);
  if (source === "CURRENT_VALUE") {
    requireClosed(binding, path, new Set(["source", "device", "propertyKey"]));
    const device = requireDevice(binding, "device", path, catalog, ["DEVICE_SINGLE", "DEVICE_MULTI"]);
    const propertyKey = requirePropertyKey(asStrict(required(binding, "propertyKey", path)), `${path}.propertyKey`);
    return projection(source, device, propertyKey);
  }
  if (source === "HISTORY_SERIES") {
    requireClosed(binding, path, new Set(["source", "device", "propertyKey", "timeRangeVariableKey", "granularity", "aggregation"]));
    const device = requireDevice(binding, "device", path, catalog, ["DEVICE_SINGLE"]);
    const propertyKey = requirePropertyKey(asStrict(required(binding, "propertyKey", path)), `${path}.propertyKey`);
    const timeKey = requireLocalKey(asStrict(required(binding, "timeRangeVariableKey", path)), `${path}.timeRangeVariableKey`);
    requireVariable(timeKey, `${path}.timeRangeVariableKey`, catalog, ["TIME_RANGE"]);
    const granularity = requireEnum(required(binding, "granularity", path), `${path}.granularity`, ["RAW", "ONE_MINUTE", "ONE_HOUR", "ONE_DAY"]);
    const aggregation = requireEnum(required(binding, "aggregation", path), `${path}.aggregation`, ["AVG", "MIN", "MAX", "SUM", "COUNT"]);
    return projection(source, device, propertyKey, timeKey, granularity, aggregation);
  }
  if (source === "DEVICE_STATUS") {
    requireClosed(binding, path, new Set(["source", "device"]));
    return projection(source, requireDevice(binding, "device", path, catalog, ["DEVICE_SINGLE"]));
  }
  if (source === "ALARM_LIST") {
    requireClosed(binding, path, new Set(["source", "devices", "conditionStates", "ackStates", "severities"]));
    const device = requireDevice(binding, "devices", path, catalog, ["DEVICE_SINGLE", "DEVICE_MULTI"]);
    requireEnumArray(binding, "conditionStates", path, ["PENDING", "ACTIVE", "CLEARED"]);
    requireEnumArray(binding, "ackStates", path, ["UNACKNOWLEDGED", "ACKNOWLEDGED"]);
    requireEnumArray(binding, "severities", path, ["CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO"]);
    return projection(source, device);
  }
  if (source === "DEVICE_DIRECTORY") {
    requireClosed(binding, path, new Set(["source", "variableKey"]));
    const key = requireLocalKey(asStrict(required(binding, "variableKey", path)), `${path}.variableKey`);
    return projection(source, requireVariable(key, `${path}.variableKey`, catalog, ["DEVICE_SINGLE", "DEVICE_MULTI"]));
  }
  requireClosed(binding, path, new Set(["source", "variableKey"]));
  const key = requireLocalKey(asStrict(required(binding, "variableKey", path)), `${path}.variableKey`);
  return projection(source, requireVariable(key, `${path}.variableKey`, catalog, ["TEXT_ENUM"]));
}

/** 校验DeviceReference封闭结构和变量基数。 */
function requireDevice(binding: MutableJsonObject, name: string, path: string, catalog: ReadonlyMap<string, VariableDefinition>, allowed: readonly VariableType[]): VariableDefinition {
  const referencePath = `${path}.${name}`;
  const reference = requireObject(required(binding, name, path), referencePath, new Set(["variableKey"]));
  const key = requireLocalKey(asStrict(required(reference, "variableKey", referencePath)), `${referencePath}.variableKey`);
  return requireVariable(key, `${referencePath}.variableKey`, catalog, allowed);
}

/** 要求变量存在且属于允许类型。 */
function requireVariable(key: string, path: string, catalog: ReadonlyMap<string, VariableDefinition>, allowed: readonly VariableType[]): VariableDefinition {
  const definition = catalog.get(key);
  if (definition === undefined || !allowed.includes(definition.type)) reject("INVALID_REFERENCE", path, "变量不存在或类型不符合Binding");
  return definition;
}

/** 校验非空、不重复、属于闭集的枚举数组。 */
function requireEnumArray(binding: MutableJsonObject, name: string, path: string, allowed: readonly string[]): void {
  const arrayPath = `${path}.${name}`;
  const values = requireArray(required(binding, name, path), arrayPath);
  requireSize(values, arrayPath, 1, allowed.length);
  requireUnique(values.map((value, index) => requireEnum(value, `${arrayPath}[${index}]`, allowed)), arrayPath);
}

/** 创建Binding内部投影。 */
function projection(source: BindingSource, variable: VariableDefinition, propertyKey = "", timeRangeVariableKey = "", granularity = "", aggregation = ""): DashboardBindingProjection {
  return { source, variableKey: variable.key, variableType: variable.type, modelKey: variable.modelKey, propertyKey, timeRangeVariableKey, granularity, aggregation };
}

/** 要求设备变量引用已声明模型key。 */
function requireModelKey(variable: MutableJsonObject, path: string, modelKeys: ReadonlySet<string>): string {
  const key = requireLocalKey(asStrict(required(variable, "modelKey", path)), `${path}.modelKey`);
  if (!modelKeys.has(key)) reject("INVALID_REFERENCE", `${path}.modelKey`, "引用的model key不存在");
  return key;
}

/** 要求节点为对象并可选执行字段闭集。 */
function requireObject(value: MutableJsonValue, path: string, allowed?: ReadonlySet<string>): MutableJsonObject {
  if (!isObject(value)) reject("TYPE_MISMATCH", path, "必须是对象");
  if (allowed !== undefined) requireClosed(value, path, allowed);
  return value;
}

/** 拒绝对象未知字段且不回显字段名。 */
function requireClosed(value: MutableJsonObject, path: string, allowed: ReadonlySet<string>): void {
  if (Object.keys(value).some((key) => !allowed.has(key))) reject("UNKNOWN_FIELD", path, "包含合同未声明字段");
}

/** 递归拒绝任意显式null，未知属性统一使用星号路径。 */
function rejectNulls(value: MutableJsonValue, path: string): void {
  if (value === null) reject("NULL_NOT_ALLOWED", path, "不接受null");
  if (Array.isArray(value)) value.forEach((item, index) => rejectNulls(item, `${path}[${index}]`));
  else if (isObject(value)) Object.values(value).forEach((item) => {
    if (item !== undefined) rejectNulls(item, `${path}.*`);
  });
}

/** 取得必填字段并区分缺失和null。 */
function required(object: MutableJsonObject, name: string, path: string): MutableJsonValue {
  const value = object[name];
  if (value === undefined) reject("REQUIRED_FIELD_MISSING", `${path}.${name}`, "为必填字段");
  if (value === null) reject("NULL_NOT_ALLOWED", `${path}.${name}`, "不接受null");
  return value;
}

/** 要求节点为数组。 */
function requireArray(value: MutableJsonValue, path: string): MutableJsonValue[] {
  if (!Array.isArray(value)) reject("TYPE_MISMATCH", path, "必须是数组");
  return value;
}

/** 要求节点为字符串。 */
function requireString(value: MutableJsonValue, path: string): string {
  if (typeof value !== "string") reject("TYPE_MISMATCH", path, "必须是字符串");
  return value;
}

/** 要求节点为boolean。 */
function requireBoolean(value: MutableJsonValue, path: string): boolean {
  if (typeof value !== "boolean") reject("TYPE_MISMATCH", path, "必须是boolean");
  return value;
}

/** 要求枚举字符串属于闭集，不回显外部值。 */
function requireEnum<const T extends string>(value: MutableJsonValue, path: string, allowed: readonly T[]): T {
  const text = requireString(value, path);
  if (!allowed.includes(text as T)) reject("INVALID_VALUE", path, "枚举值不受支持");
  return text as T;
}

/** 要求字段等于固定字符串。 */
function requireExactString(object: MutableJsonObject, name: string, path: string, expected: string): void {
  if (requireString(required(object, name, path), `${path}.${name}`) !== expected) reject("INVALID_VALUE", `${path}.${name}`, "固定值不受支持");
}

/** 要求字段等于固定JSON整数。 */
function requireExactInteger(object: MutableJsonObject, name: string, path: string, expected: bigint): void {
  if (requireStructuralInteger(asStrict(required(object, name, path)), `${path}.${name}`) !== expected) reject("INVALID_VALUE", `${path}.${name}`, "固定整数值不受支持");
}

/** 复刻Java结构层先要求可转long、再执行合同范围的整数顺序。 */
function requireStructuralInteger(value: StrictJsonValue, path: string, minimum?: bigint, maximum?: bigint): bigint {
  const integer = requireJsonInteger(value, path);
  if (integer < JAVA_LONG_MIN || integer > JAVA_LONG_MAX) reject("TYPE_MISMATCH", path, "必须是可转long的JSON整数");
  if (minimum !== undefined && integer < minimum || maximum !== undefined && integer > maximum) {
    reject("INVALID_VALUE", path, "整数超出允许范围");
  }
  return integer;
}

/** 要求集合数量在闭区间内。 */
function requireSize(value: readonly unknown[], path: string, minimum: number, maximum: number): void {
  if (value.length < minimum || value.length > maximum) reject("INVALID_COLLECTION", path, `数量必须在${minimum}至${maximum}之间`);
}

/** 要求字符串集合无重复。 */
function requireUnique(values: readonly string[], path: string): void {
  if (new Set(values).size !== values.length) reject("INVALID_COLLECTION", path, "集合成员不得重复");
}

/** 创建内部默认数字token。 */
function number(lexical: string): StrictJsonNumber {
  return createStrictJsonInteger(lexical);
}

/** 判断内部值为普通JSON对象而非数字token。 */
function isObject(value: MutableJsonValue): value is MutableJsonObject {
  return value !== null && typeof value === "object" && !Array.isArray(value) && !isStrictJsonNumber(asStrict(value));
}

/** 将只读解析树复制为默认注入专用可变树。 */
function thaw(value: StrictJsonValue): MutableJsonValue {
  if (Array.isArray(value)) return value.map((item) => thaw(item));
  if (value !== null && typeof value === "object") {
    if (isStrictJsonNumber(value)) return value;
    const result: MutableJsonObject = Object.create(null);
    Object.entries(value).forEach(([key, item]) => { result[key] = thaw(item); });
    return result;
  }
  return value;
}

/** 递归按Jackson整数/BigDecimal输出语义规范数字并冻结完整JSON树。 */
function freezeJson(value: MutableJsonValue): StrictJsonValue {
  if (Array.isArray(value)) return Object.freeze(value.map((item) => freezeJson(item)));
  if (isStrictJsonNumber(asStrict(value))) {
    const numberValue = value as StrictJsonNumber;
    const lexical = normalizeJacksonNumber(numberValue.lexical);
    return lexical === numberValue.lexical ? numberValue : createStrictJsonNumber(lexical);
  }
  if (isObject(value)) {
    const result: Record<string, StrictJsonValue> = Object.create(null);
    Object.entries(value).forEach(([key, item]) => { if (item !== undefined) result[key] = freezeJson(item); });
    return Object.freeze(result);
  }
  return value;
}

/** 把保留词法的内部树投影为公开DashboardSchemaV1并递归冻结。 */
function materializePublicSchema(value: StrictJsonObject): DashboardSchemaV1 {
  return materializePublicValue(value) as DashboardSchemaV1;
}

/**
 * 把内部数字token转换为公开number，其精确持久表示仍由同一结果的normalizedJson承载。
 *
 * <p>全部Schema数值在前置规则中已限制到JS可用范围；若内部规则回归导致非有限值，立即作为
 * 编程错误失败，不能向调用方返回与normalizedJson分叉的树。</p>
 */
function materializePublicValue(value: StrictJsonValue): unknown {
  if (isStrictJsonNumber(value)) {
    const numberValue = Number(value.lexical);
    if (!Number.isFinite(numberValue)) throw new Error("规范Schema数字无法投影为有限number");
    return numberValue;
  }
  if (Array.isArray(value)) return Object.freeze(value.map((item) => materializePublicValue(item)));
  if (value !== null && typeof value === "object") {
    const result: Record<string, unknown> = Object.create(null);
    Object.entries(value).forEach(([key, item]) => { result[key] = materializePublicValue(item); });
    return Object.freeze(result);
  }
  return value;
}

/**
 * 复刻Jackson整数节点与启用USE_BIG_DECIMAL_FOR_FLOATS后的紧凑输出。
 *
 * <p>校验阶段始终使用原始token；此转换只在全部内部语义通过后创建结果树，因而不会把
 * {@code 1e0} 提前改成整数或抹去ConfigNumber的原始有效位。</p>
 */
function normalizeJacksonNumber(lexical: string): string {
  if (!/[.eE]/.test(lexical)) return lexical === "-0" ? "0" : lexical;
  const match = /^(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(lexical);
  if (match === null) throw new Error("严格解析器产生了非法数字token");
  const fraction = match[3] ?? "";
  const exponent = Number(match[4] ?? "0");
  const rawDigits = `${match[2]}${fraction}`;
  const significantDigits = rawDigits.replace(/^0+/, "") || "0";
  const negative = match[1] === "-" && significantDigits !== "0";
  const scale = fraction.length - exponent;
  const precision = significantDigits.length;
  const adjustedExponent = precision - scale - 1;
  let magnitude: string;
  if (scale >= 0 && adjustedExponent >= -6) {
    if (scale === 0) {
      magnitude = significantDigits;
    } else if (scale >= precision) {
      magnitude = `0.${"0".repeat(scale - precision)}${significantDigits}`;
    } else {
      const point = precision - scale;
      magnitude = `${significantDigits.slice(0, point)}.${significantDigits.slice(point)}`;
    }
  } else {
    const coefficient = precision === 1
      ? significantDigits
      : `${significantDigits[0]}.${significantDigits.slice(1)}`;
    magnitude = `${coefficient}E${adjustedExponent >= 0 ? "+" : ""}${adjustedExponent}`;
  }
  return negative ? `-${magnitude}` : magnitude;
}

/** 以原始数字词法紧凑序列化完整规范化树。 */
function stringifyJson(value: StrictJsonValue): string {
  if (value === null || typeof value === "boolean") return String(value);
  if (typeof value === "string") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map((item) => stringifyJson(item)).join(",")}]`;
  if (isStrictJsonNumber(value)) return value.lexical;
  return `{${Object.entries(value).map(([key, item]) => `${JSON.stringify(key)}:${stringifyJson(item)}`).join(",")}}`;
}

/** 递归冻结一项类型化外部需求及其数组字段。 */
function deepFreezeRequirement<T extends DashboardUnresolvedRequirement>(requirement: T): T {
  Object.values(requirement).forEach((value) => {
    if (value !== null && typeof value === "object") Object.freeze(value);
  });
  return Object.freeze(requirement);
}

/** 将内部可变值视作只读值交给无副作用值规则。 */
function asStrict(value: MutableJsonValue): StrictJsonValue {
  return value as StrictJsonValue;
}

/** 抛出稳定合同拒绝结果。 */
function reject(reason: ConstructorParameters<typeof DashboardContractViolation>[0], path: string, detail: string): never {
  throw new DashboardContractViolation(reason, path, detail);
}
