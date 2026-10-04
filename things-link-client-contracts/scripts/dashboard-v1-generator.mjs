import { readFile } from "node:fs/promises";

const EXPECTED_KINDS = [
  "TEXT",
  "IMAGE",
  "VALUE_CARD",
  "STATUS",
  "GAUGE",
  "LINE_CHART",
  "TABLE",
  "JSON_VIEW",
  "ALARM_LIST",
  "DEVICE_SELECTOR",
];

const EXPECTED_CANVASES = ["RESPONSIVE_GRID", "FIXED_SCREEN"];
const EXPECTED_ATOMIC_RULES = [
  "localKey",
  "propertyKey",
  "uuid",
  "sha256",
  "semVer",
  "title",
  "shortText",
  "textContent",
  "configNumber",
];
const EXPECTED_ENUM_SETS = {
  canvasModes: EXPECTED_CANVASES,
  themes: ["LIGHT", "DARK"],
  variableTypes: ["DEVICE_SINGLE", "DEVICE_MULTI", "TIME_RANGE", "TEXT_ENUM"],
  timeRangePresets: ["LAST_1_HOUR", "LAST_24_HOURS", "LAST_7_DAYS"],
  bindingSources: ["CURRENT_VALUE", "HISTORY_SERIES", "DEVICE_STATUS", "ALARM_LIST", "DEVICE_DIRECTORY", "ENUM_TEXT"],
  historyGranularities: ["RAW", "ONE_MINUTE", "ONE_HOUR", "ONE_DAY"],
  historyAggregations: ["AVG", "MIN", "MAX", "SUM", "COUNT"],
  alarmConditionStates: ["PENDING", "ACTIVE", "CLEARED"],
  alarmAckStates: ["UNACKNOWLEDGED", "ACKNOWLEDGED"],
  alarmSeverities: ["CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO"],
  textAlignments: ["LEFT", "CENTER", "RIGHT"],
  textSizes: ["SMALL", "MEDIUM", "LARGE"],
  textTones: ["REGULAR", "SECONDARY", "PRIMARY"],
  imageFits: ["CONTAIN", "COVER"],
  unitModes: ["MODEL", "NONE"],
  gaugeScaleModes: ["MODEL", "EXPLICIT"],
  tableModes: ["LIST_VALUE", "DEVICE_VALUES"],
  propertyDataTypes: ["NUMBER", "TEXT", "SWITCH", "ENUM", "OBJECT", "LIST"],
  modelDigestAlgorithms: ["PG_JSONB_TEXT_V1_SHA256"],
  modelProfiles: ["TC_PROPERTY_COMPOSITE_V1"],
};

/**
 * 读取并校验Dashboard v1机器合同，避免生成器静默接受残缺矩阵。
 *
 * @param {URL} contractUrl 机器合同地址。
 * @returns {Promise<Record<string, unknown>>} 已通过结构守卫的机器合同。
 */
export async function readDashboardV1Contract(contractUrl) {
  const source = await readFile(contractUrl, "utf8");
  const contract = JSON.parse(source);
  validateDashboardV1Contract(contract);
  return contract;
}

/**
 * 将机器合同稳定生成TypeScript常量与联合类型。
 *
 * @param {Record<string, unknown>} contract 已校验机器合同。
 * @returns {string} 可提交的生成文件内容。
 */
export function renderDashboardV1Contract(contract) {
  const json = JSON.stringify(contract, null, 2);
  return `/* 此文件由 scripts/generate-dashboard-v1.mjs 生成，请勿手工修改。 */\n\n` +
    `/** 递归冻结机器合同，避免宿主进程改写共享注册表。 */\n` +
    `function deepFreeze<const T>(value: T): T {\n` +
    `  if (value !== null && typeof value === "object" && !Object.isFrozen(value)) {\n` +
    `    for (const nested of Object.values(value as Record<string, unknown>)) {\n` +
    `      deepFreeze(nested);\n` +
    `    }\n` +
    `    Object.freeze(value);\n` +
    `  }\n` +
    `  return value;\n` +
    `}\n\n` +
    `export const dashboardV1Contract = deepFreeze(${json} as const);\n\n` +
    `/** Dashboard v1机器合同的只读字面量类型。 */\n` +
    `export type DashboardV1Contract = typeof dashboardV1Contract;\n\n` +
    `/** Dashboard v1支持的组件种类。 */\n` +
    `export type DashboardComponentKind = DashboardV1Contract["components"][number]["kind"];\n\n` +
    `/** Dashboard v1解析阶段稳定拒绝原因。 */\n` +
    `export type DashboardParseRejectionReason = DashboardV1Contract["rejectionReasons"]["parse"][number];\n\n` +
    `/** Dashboard v1语义校验阶段稳定拒绝原因。 */\n` +
    `export type DashboardValidationRejectionReason = DashboardV1Contract["rejectionReasons"]["validation"][number];\n\n` +
    `/** Dashboard v1全部稳定拒绝原因。 */\n` +
    `export type DashboardRejectionReason = DashboardParseRejectionReason | DashboardValidationRejectionReason;\n\n` +
    `/** Dashboard v1支持的画布模式。 */\n` +
    `export type DashboardCanvasMode = keyof DashboardV1Contract["limits"]["canvases"];\n\n` +
    `/** Dashboard v1组件支持的模型属性数据类型。 */\n` +
    `export type DashboardPropertyDataType = Exclude<DashboardV1Contract["components"][number]["supportedDataTypes"]["values"][number], never>;\n`;
}

/**
 * 校验机器合同维持十组件、版本、画布和描述符的完整闭集。
 *
 * @param {unknown} candidate 待校验内容。
 */
export function validateDashboardV1Contract(candidate) {
  if (!isRecord(candidate)) {
    throw new Error("Dashboard v1合同根节点必须是对象");
  }
  requireEqual(candidate.formatVersion, "tc.dashboard-client-contract/v1", "formatVersion");
  requireEqual(candidate.schemaVersion, "tc.dashboard/v1", "schemaVersion");
  requireEqual(candidate.componentVersion, "1.0.0", "componentVersion");
  validateHostRange(candidate.hostRange, "hostRange");

  if (!isRecord(candidate.rejectionReasons)) {
    throw new Error("rejectionReasons必须是对象");
  }
  requireUniqueNonEmptyStrings(candidate.rejectionReasons.parse, "rejectionReasons.parse");
  requireUniqueNonEmptyStrings(candidate.rejectionReasons.validation, "rejectionReasons.validation");

  if (!isRecord(candidate.atomicValueRules)) {
    throw new Error("atomicValueRules必须完整声明原子值规则");
  }
  requireExactMembers(Object.keys(candidate.atomicValueRules), EXPECTED_ATOMIC_RULES, "atomicValueRules");
  for (const ruleName of EXPECTED_ATOMIC_RULES) {
    if (!isRecord(candidate.atomicValueRules[ruleName])) {
      throw new Error(`atomicValueRules.${ruleName}必须是对象`);
    }
  }
  if (!isRecord(candidate.enumSets)) {
    throw new Error("enumSets必须完整声明枚举闭集");
  }
  requireExactMembers(Object.keys(candidate.enumSets), Object.keys(EXPECTED_ENUM_SETS), "enumSets");
  for (const [name, values] of Object.entries(EXPECTED_ENUM_SETS)) {
    requireExactMembers(candidate.enumSets[name], values, `enumSets.${name}`);
  }
  if (!isRecord(candidate.valueRanges)) {
    throw new Error("valueRanges必须声明组件和变量的有界数值规则");
  }

  if (!isRecord(candidate.limits) || !isRecord(candidate.limits.parser)
      || !isRecord(candidate.limits.collections) || !isRecord(candidate.limits.canvases)) {
    throw new Error("limits必须完整声明parser、collections和canvases");
  }
  requireExactMembers(Object.keys(candidate.limits.canvases), EXPECTED_CANVASES, "limits.canvases");

  if (!Array.isArray(candidate.components)) {
    throw new Error("components必须是数组");
  }
  requireExactMembers(candidate.components.map((component) => isRecord(component) ? `${component.kind}/${component.componentVersion}` : undefined), EXPECTED_KINDS.flatMap(kind => [ `${kind}/1.0.0`, `${kind}/1.0.1` ]), "components.kind/version");
  for (const component of candidate.components) {
    if (!isRecord(component)) {
      throw new Error("components成员必须是对象");
    }
    requireExactMembers(
      Object.keys(component),
      ["kind", "componentVersion", "props", "slots", "supportedDataTypes", "supportedCanvasModes", "hostRange"],
      `${String(component.kind)}组件字段`,
    );
    if (!["1.0.0", "1.0.1"].includes(component.componentVersion)) throw new Error("未知组件版本");
    if (!isRecord(component.props) || !isRecord(component.slots)) {
      throw new Error(`${String(component.kind)}必须声明props和slots对象`);
    }
    validatePropertyDescriptors(component.props, `${String(component.kind)}.props`);
    validateSlotDescriptors(component.slots, `${String(component.kind)}.slots`, candidate.enumSets.bindingSources);
    if (!isRecord(component.supportedDataTypes)) {
      throw new Error(`${String(component.kind)}.supportedDataTypes必须是对象`);
    }
    requireExactMembers(
      Object.keys(component.supportedDataTypes),
      ["applicability", "values"],
      `${String(component.kind)}.supportedDataTypes字段`,
    );
    const applicability = component.supportedDataTypes.applicability;
    const values = component.supportedDataTypes.values;
    if (!Array.isArray(values) || new Set(values).size !== values.length
        || !values.every((value) => candidate.enumSets.propertyDataTypes.includes(value))) {
      throw new Error(`${String(component.kind)}.supportedDataTypes.values必须是无重复的propertyDataTypes子集`);
    }
    if (applicability !== "NOT_APPLICABLE" && applicability !== "PROPERTY_TYPED") {
      throw new Error(`${String(component.kind)}.supportedDataTypes.applicability不在闭集内`);
    }
    if (applicability === "NOT_APPLICABLE" && values.length !== 0) {
      throw new Error(`${String(component.kind)}不适用数据类型时values必须为空`);
    }
    if (applicability === "PROPERTY_TYPED" && values.length === 0) {
      throw new Error(`${String(component.kind)}按属性定型时values不能为空`);
    }
    requireExactMembers(component.supportedCanvasModes, EXPECTED_CANVASES, `${String(component.kind)}.supportedCanvasModes`);
    if (component.componentVersion === "1.0.1") {
      const original = candidate.components.find(c => c.kind === component.kind && c.componentVersion === "1.0.0");
      const semantic = c => JSON.stringify({ ...c, componentVersion: null, hostRange: null });
      if (!original || semantic(component) !== semantic(original)) throw new Error("组件补丁语义必须与原版本相同");
    }
    validateHostRange(component.hostRange, `${String(component.kind)}.hostRange`, component.componentVersion);
    if (component.componentVersion === "1.0.0" && JSON.stringify(component.hostRange) !== JSON.stringify(candidate.hostRange)) {
      throw new Error(`${String(component.kind)}.hostRange必须等于根HostRange`);
    }
  }
}

/**
 * 校验prop描述符字段闭集、类型与必填标志。
 *
 * @param {Record<string, unknown>} fields prop描述符映射。
 * @param {string} path 错误定位路径。
 */
function validatePropertyDescriptors(fields, path) {
  for (const [name, definition] of Object.entries(fields)) {
    if (name.length === 0 || !isRecord(definition) || typeof definition.type !== "string"
        || typeof definition.required !== "boolean") {
      throw new Error(`${path}.${name}必须声明type和required`);
    }
    const expectedKeys = Object.hasOwn(definition, "default") ? ["type", "required", "default"] : ["type", "required"];
    requireExactMembers(Object.keys(definition), expectedKeys, `${path}.${name}字段`);
  }
}

/**
 * 校验slot描述符的闭集、来源和数组基数保持一致。
 *
 * @param {Record<string, unknown>} fields slot描述符映射。
 * @param {string} path 错误定位路径。
 * @param {unknown} bindingSources 合同允许的Binding来源。
 */
function validateSlotDescriptors(fields, path, bindingSources) {
  for (const [name, definition] of Object.entries(fields)) {
    if (name.length === 0 || !isRecord(definition) || typeof definition.valueType !== "string"
        || typeof definition.required !== "boolean") {
      throw new Error(`${path}.${name}必须声明valueType和required`);
    }
    requireExactMembers(
      Object.keys(definition),
      ["valueType", "required", "sources", "cardinality"],
      `${path}.${name}字段`,
    );
    requireUniqueNonEmptyStrings(definition.sources, `${path}.${name}.sources`);
    if (!definition.sources.every((source) => bindingSources.includes(source))) {
      throw new Error(`${path}.${name}.sources必须属于bindingSources闭集`);
    }
    const expectedCardinality = definition.valueType === "BINDING"
      ? "SINGLE"
      : definition.valueType === "BINDING_ARRAY"
        ? "MULTIPLE"
        : null;
    if (expectedCardinality === null || definition.cardinality !== expectedCardinality) {
      throw new Error(`${path}.${name}.cardinality必须与valueType一致`);
    }
  }
}

/**
 * 校验HostRange采用一项包含下界、一项排除上界的版本范围。
 *
 * @param {unknown} candidate 待校验范围。
 * @param {string} path 错误定位路径。
 */
function validateHostRange(candidate, path, version = "1.0.0") {
  const minimum = version === "1.0.0" ? "1.0.0" : "1.1.0";
  const maximum = version === "1.0.0" ? "1.0.1" : "1.1.2";
  if (!isRecord(candidate)
      || !hasExactMembers(Object.keys(candidate), ["minInclusive", "maxExclusive"])
      || candidate.minInclusive !== minimum || candidate.maxExclusive !== maximum) {
    throw new Error(`${path}必须为[${minimum},${maximum})`);
  }
}

/**
 * 校验数组是非空且无重复的字符串集合。
 *
 * @param {unknown} candidate 待校验数组。
 * @param {string} path 错误定位路径。
 */
function requireUniqueNonEmptyStrings(candidate, path) {
  if (!Array.isArray(candidate) || candidate.length === 0
      || !candidate.every((value) => typeof value === "string" && value.length > 0)
      || new Set(candidate).size !== candidate.length) {
    throw new Error(`${path}必须是非空且无重复的字符串数组`);
  }
}

/**
 * 校验实际数组与期望闭集完全一致且无重复。
 *
 * @param {unknown} actual 实际成员。
 * @param {readonly string[]} expected 期望成员。
 * @param {string} path 错误定位路径。
 */
function requireExactMembers(actual, expected, path) {
  if (!hasExactMembers(actual, expected)) {
    throw new Error(`${path}必须精确覆盖${expected.join(",")}`);
  }
}

/** 比较两个数组是否为无重复的同一成员闭集。 */
function hasExactMembers(actual, expected) {
  return Array.isArray(actual) && actual.length === expected.length
    && new Set(actual).size === actual.length
    && expected.every((value) => actual.includes(value));
}

/**
 * 校验字段与固定合同值相等。
 *
 * @param {unknown} actual 实际值。
 * @param {unknown} expected 期望值。
 * @param {string} path 错误定位路径。
 */
function requireEqual(actual, expected, path) {
  if (actual !== expected) {
    throw new Error(`${path}必须为${String(expected)}`);
  }
}

/**
 * 判断未知值是否为非空对象记录。
 *
 * @param {unknown} candidate 待判断值。
 * @returns {candidate is Record<string, unknown>} 对象记录判定结果。
 */
function isRecord(candidate) {
  return candidate !== null && typeof candidate === "object" && !Array.isArray(candidate);
}
