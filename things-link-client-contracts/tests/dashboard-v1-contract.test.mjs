import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { dashboardV1Contract } from "../dist/dashboard/v1/index.js";

const EXPECTED_MATRIX = {
  TEXT: {
    props: { content: { type: "TEXT_CONTENT", required: false, default: "" }, align: { type: "TEXT_ALIGN", required: false, default: "LEFT" }, size: { type: "TEXT_SIZE", required: false, default: "MEDIUM" }, tone: { type: "TEXT_TONE", required: false, default: "REGULAR" } },
    slots: { text: { valueType: "BINDING", required: false, sources: ["ENUM_TEXT"], cardinality: "SINGLE" } }, dataTypes: [],
  },
  IMAGE: {
    props: { title: { type: "TITLE", required: false }, resourceId: { type: "LOCAL_KEY", required: true }, resourceDigest: { type: "SHA256", required: true }, alt: { type: "SHORT_TEXT", required: true }, fit: { type: "IMAGE_FIT", required: false, default: "CONTAIN" } },
    slots: {}, dataTypes: [],
  },
  VALUE_CARD: {
    props: { title: { type: "TITLE", required: false }, precision: { type: "PRECISION", required: false, default: 2 }, unitMode: { type: "UNIT_MODE", required: false, default: "MODEL" } },
    slots: { value: { valueType: "BINDING", required: true, sources: ["CURRENT_VALUE"], cardinality: "SINGLE" } }, dataTypes: ["NUMBER", "TEXT", "SWITCH", "ENUM"],
  },
  STATUS: {
    props: { title: { type: "TITLE", required: false }, showLastOnlineAt: { type: "BOOLEAN", required: false, default: true } },
    slots: { status: { valueType: "BINDING", required: true, sources: ["DEVICE_STATUS"], cardinality: "SINGLE" } }, dataTypes: [],
  },
  GAUGE: {
    props: { title: { type: "TITLE", required: false }, scaleMode: { type: "GAUGE_SCALE_MODE", required: false, default: "MODEL" }, min: { type: "CONFIG_NUMBER", required: false }, max: { type: "CONFIG_NUMBER", required: false }, precision: { type: "PRECISION", required: false, default: 2 }, unitMode: { type: "UNIT_MODE", required: false, default: "MODEL" } },
    slots: { value: { valueType: "BINDING", required: true, sources: ["CURRENT_VALUE"], cardinality: "SINGLE" } }, dataTypes: ["NUMBER"],
  },
  LINE_CHART: {
    props: { title: { type: "TITLE", required: false }, showLegend: { type: "BOOLEAN", required: false, default: true }, series: { type: "SERIES_DEFINITIONS", required: true } },
    slots: { series: { valueType: "BINDING_ARRAY", required: true, sources: ["HISTORY_SERIES"], cardinality: "MULTIPLE" } }, dataTypes: ["NUMBER"],
  },
  TABLE: {
    props: { title: { type: "TITLE", required: false }, mode: { type: "TABLE_MODE", required: true }, rowLimit: { type: "ROW_LIMIT", required: false, default: 20 }, columns: { type: "COLUMN_DEFINITIONS", required: false } },
    slots: { value: { valueType: "BINDING", required: false, sources: ["CURRENT_VALUE"], cardinality: "SINGLE" }, columns: { valueType: "BINDING_ARRAY", required: false, sources: ["CURRENT_VALUE"], cardinality: "MULTIPLE" } }, dataTypes: ["NUMBER", "TEXT", "SWITCH", "ENUM", "LIST"],
  },
  JSON_VIEW: {
    props: { title: { type: "TITLE", required: false }, initialExpandDepth: { type: "EXPAND_DEPTH", required: false, default: 1 } },
    slots: { value: { valueType: "BINDING", required: true, sources: ["CURRENT_VALUE"], cardinality: "SINGLE" } }, dataTypes: ["OBJECT", "LIST"],
  },
  ALARM_LIST: {
    props: { title: { type: "TITLE", required: false }, pageSize: { type: "PAGE_SIZE", required: false, default: 20 }, showClearedAt: { type: "BOOLEAN", required: false, default: true } },
    slots: { alarms: { valueType: "BINDING", required: true, sources: ["ALARM_LIST"], cardinality: "SINGLE" } }, dataTypes: [],
  },
  DEVICE_SELECTOR: {
    props: { title: { type: "TITLE", required: false }, placeholder: { type: "SHORT_TEXT", required: false, default: "请选择设备" }, pageSize: { type: "PAGE_SIZE", required: false, default: 20 } },
    slots: { directory: { valueType: "BINDING", required: true, sources: ["DEVICE_DIRECTORY"], cardinality: "SINGLE" } }, dataTypes: [],
  },
};

const EXPECTED_CONTRACT_SHA256 = "19a94ee8614d9cf9d3495c6f803dbe0b3bf73425a614ee8147136a4df841177d";

test("机器合同全部叶值保持显式审阅快照", async () => {
  const source = await readFile(new URL("../contracts/dashboard-v1.json", import.meta.url));
  assert.equal(createHash("sha256").update(source).digest("hex"), EXPECTED_CONTRACT_SHA256);
});

test("机器合同冻结Schema版本、拒绝原因和资源上限", () => {
  assert.equal(dashboardV1Contract.schemaVersion, "tc.dashboard/v1");
  assert.deepEqual(dashboardV1Contract.hostRange, { minInclusive: "1.0.0", maxExclusive: "1.0.1" });
  assert.deepEqual(dashboardV1Contract.rejectionReasons.parse, [
    "RAW_TOO_LARGE", "INVALID_UTF8", "BOM_NOT_ALLOWED", "INVALID_JSON", "DUPLICATE_KEY",
    "NUMBER_TOO_LONG", "INVALID_EXPONENT", "INVALID_UNICODE", "DEPTH_EXCEEDED", "TRAILING_VALUE",
    "ROOT_MUST_BE_OBJECT", "VERSION_REQUIRED", "VERSION_UNSUPPORTED",
  ]);
  assert.deepEqual(dashboardV1Contract.rejectionReasons.validation, [
    "UNKNOWN_FIELD", "NULL_NOT_ALLOWED", "REQUIRED_FIELD_MISSING", "TYPE_MISMATCH", "INVALID_VALUE",
    "INVALID_NUMBER", "INVALID_COLLECTION", "INVALID_LAYOUT", "INVALID_REFERENCE", "NORMALIZED_TOO_LARGE",
  ]);
  assert.deepEqual(dashboardV1Contract.limits.parser, {
    rawBytes: 512000,
    normalizedBytes: 512000,
    maximumDepth: 16,
    numericTokenAsciiBytes: 64,
    maximumAbsoluteExponent: 12,
    exponentDigitsMinimum: 1,
    exponentDigitsMaximum: 2,
    exponentLeadingZeroAllowed: false,
  });
  assert.equal(dashboardV1Contract.limits.collections.lineSeriesMaximum, 4);
  assert.equal(dashboardV1Contract.limits.collections.tableColumnsMaximum, 10);
  assert.equal(dashboardV1Contract.limits.collections.componentsPerPageMaximum, 50);
});

test("机器合同完整覆盖九类原子值规则和枚举闭集", () => {
  assert.deepEqual(Object.keys(dashboardV1Contract.atomicValueRules), [
    "localKey", "propertyKey", "uuid", "sha256", "semVer", "title", "shortText", "textContent", "configNumber",
  ]);
  assert.equal(dashboardV1Contract.atomicValueRules.localKey.pattern, "^[a-z][a-z0-9_]{0,63}$");
  assert.equal(dashboardV1Contract.atomicValueRules.title.maximumCodePoints, 80);
  assert.deepEqual(dashboardV1Contract.atomicValueRules.textContent.allowedControlCodePoints, [9, 10]);
  assert.equal(dashboardV1Contract.atomicValueRules.configNumber.maximumSignificantDecimalDigitsAfterTrailingZeroRemoval, 15);
  assert.deepEqual(dashboardV1Contract.enumSets.variableTypes, ["DEVICE_SINGLE", "DEVICE_MULTI", "TIME_RANGE", "TEXT_ENUM"]);
  assert.deepEqual(dashboardV1Contract.enumSets.bindingSources, [
    "CURRENT_VALUE", "HISTORY_SERIES", "DEVICE_STATUS", "ALARM_LIST", "DEVICE_DIRECTORY", "ENUM_TEXT",
  ]);
  assert.deepEqual(dashboardV1Contract.enumSets.propertyDataTypes, ["NUMBER", "TEXT", "SWITCH", "ENUM", "OBJECT", "LIST"]);
});

test("机器合同精确覆盖十组件描述符矩阵", () => {
  assert.deepEqual(dashboardV1Contract.components.map(({ kind, componentVersion }) => `${kind}/${componentVersion}`), ["1.0.0", "1.0.1"].flatMap(version => Object.keys(EXPECTED_MATRIX).map(kind => `${kind}/${version}`)));
  for (const descriptor of dashboardV1Contract.components) {
    const expected = EXPECTED_MATRIX[descriptor.kind];
    assert.deepEqual(descriptor.props, expected.props, `${descriptor.kind} props`);
    assert.deepEqual(descriptor.slots, expected.slots, `${descriptor.kind} slots`);
    assert.deepEqual(descriptor.supportedDataTypes.values, expected.dataTypes, `${descriptor.kind} dataTypes`);
    assert.equal(descriptor.supportedDataTypes.applicability, expected.dataTypes.length === 0 ? "NOT_APPLICABLE" : "PROPERTY_TYPED");
    assert.deepEqual(descriptor.supportedCanvasModes, ["RESPONSIVE_GRID", "FIXED_SCREEN"]);
    assert.ok(["1.0.0", "1.0.1"].includes(descriptor.componentVersion));
    assert.deepEqual(descriptor.hostRange, descriptor.componentVersion === "1.0.0" ? dashboardV1Contract.hostRange : { minInclusive: "1.1.0", maxExclusive: "1.1.2" });
  }
});

test("两种画布分别冻结页面、边界和默认展示参数", () => {
  const responsive = dashboardV1Contract.limits.canvases.RESPONSIVE_GRID;
  assert.deepEqual(responsive.presentationDefaults, { theme: "LIGHT", columns: 24, rowHeight: 8, gap: 8 });
  assert.equal(responsive.layout.rightEdgeMaximum, 24);
  assert.equal(responsive.layout.bottomEdgeMaximum, 1000);
  const fixed = dashboardV1Contract.limits.canvases.FIXED_SCREEN;
  assert.deepEqual(fixed.presentationDefaults, { theme: "LIGHT", width: 1920, height: 1080, scaleMode: "FIT" });
  assert.equal(fixed.pageMinimum, 1);
  assert.equal(fixed.pageMaximum, 1);
  assert.equal(fixed.layout.rightEdgeMaximum, 1920);
  assert.equal(fixed.layout.bottomEdgeMaximum, 1080);
});


test("导出的机器合同全部对象和数组在运行时递归冻结", () => {
  const pending = [dashboardV1Contract];
  while (pending.length > 0) {
    const value = pending.pop();
    assert.equal(Object.isFrozen(value), true);
    for (const nested of Object.values(value)) {
      if (nested !== null && typeof nested === "object") pending.push(nested);
    }
  }
  assert.throws(() => dashboardV1Contract.components.push({}), TypeError);
  assert.throws(() => { dashboardV1Contract.components[0].props.align.default = "RIGHT"; }, TypeError);
});
