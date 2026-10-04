import assert from "node:assert/strict";
import test from "node:test";
import {
  DASHBOARD_SCHEMA_SCOPE,
  validateDashboardBinding,
  validateDashboardV1Schema,
} from "../dist/dashboard/v1/schema-validator.js";
import { parseDashboardV1Json } from "../dist/dashboard/v1/strict-json.js";
import { requireJsonInteger } from "../dist/dashboard/v1/value-rules.js";
import { DashboardContractViolation } from "../dist/dashboard/v1/violation.js";

const encoder = new TextEncoder();
const digest = "a".repeat(64);

function bytes(value) {
  return encoder.encode(value);
}

function model(index = 1, key = `model_${index}`) {
  return `{"key":"${key}","versionId":"00000000-0000-0000-0000-${String(index).padStart(12, "0")}","digestAlgorithm":"PG_JSONB_TEXT_V1_SHA256","digest":"${digest}","profile":"TC_PROPERTY_COMPOSITE_V1"}`;
}

function component(id, layout, bindings = "{}") {
  return `{"id":"${id}","kind":"TEXT","componentVersion":"1.0.0","layout":${layout},"props":{},"bindings":${bindings}}`;
}

function page(id, components = "") {
  return `{"id":"${id}","title":"页面","components":[${components}]}`;
}

function schema({ presentation = '{"mode":"RESPONSIVE_GRID"}', models = "[]", variables = "[]", pages = `[${page("main")}]`, extra = "" } = {}) {
  return `{"schemaVersion":"tc.dashboard/v1","presentation":${presentation},"models":${models},"variables":${variables},"pages":${pages}${extra}}`;
}

function validate(source) {
  return validateDashboardV1Schema(bytes(source));
}

function assertRejected(source, reason, path) {
  assert.throws(
    () => validate(source),
    (error) => error instanceof DashboardContractViolation && error.reason === reason
      && (path === undefined || error.path === path),
  );
}

function parseBinding(binding) {
  return parseDashboardV1Json(bytes(`{"schemaVersion":"tc.dashboard/v1","value":${binding}}`)).value;
}

function allVariables() {
  return `[{"key":"single","type":"DEVICE_SINGLE","title":"单设备","modelKey":"pump_model"},{"key":"multi","type":"DEVICE_MULTI","title":"多设备","modelKey":"pump_model"},{"key":"range","type":"TIME_RANGE","title":"时间"},{"key":"choice","type":"TEXT_ENUM","title":"枚举","options":[{"value":"normal","label":"正常"}]}]`;
}

function context() {
  return validate(schema({ models: `[${model(1, "pump_model")}]`, variables: allVariables() }));
}

test("响应式画布注入受控数字默认值并允许矩形接边", () => {
  const result = validate(schema({
    pages: `[${page("main", `${component("left", '{"x":0,"y":0,"w":12,"h":8}')},${component("right", '{"x":12,"y":0,"w":12,"h":8}')}`)}]`,
  }));
  assert.equal(result.scope, DASHBOARD_SCHEMA_SCOPE);
  assert.equal(result.normalizedRoot.presentation.theme, "LIGHT");
  assert.equal(requireJsonInteger(result.normalizedRoot.presentation.columns, "$.presentation.columns"), 24n);
  assert.equal(requireJsonInteger(result.normalizedRoot.presentation.rowHeight, "$.presentation.rowHeight"), 8n);
  assert.equal(requireJsonInteger(result.normalizedRoot.presentation.gap, "$.presentation.gap"), 8n);
  assert.equal(Object.isFrozen(result.normalizedRoot.pages), true);
});

test("固定画布接受边界像素并注入冻结参数", () => {
  const result = validate(schema({
    presentation: '{"mode":"FIXED_SCREEN"}',
    pages: `[${page("main", component("pixel", '{"x":1919,"y":1079,"w":1,"h":1}'))}]`,
  }));
  assert.equal(requireJsonInteger(result.normalizedRoot.presentation.width, "$.presentation.width"), 1920n);
  assert.equal(requireJsonInteger(result.normalizedRoot.presentation.height, "$.presentation.height"), 1080n);
  assert.equal(result.normalizedRoot.presentation.scaleMode, "FIT");
});

test("模型集合边界、双重唯一和冻结常量", () => {
  const twenty = Array.from({ length: 20 }, (_, index) => model(index + 1)).join(",");
  assert.equal(validate(schema({ models: `[${twenty}]` })).normalizedRoot.models.length, 20);
  assertRejected(schema({ models: `[${model(1)},${model(1)}]` }), "INVALID_COLLECTION");
  assertRejected(schema({ models: `[${model(1)},${model(1, "other")}]` }), "INVALID_COLLECTION");
  assertRejected(schema({ models: `[${model(1).replace('"digest":', '"missing":')}]` }), "UNKNOWN_FIELD");
  assertRejected(schema({ models: `[${model(1).replace("PG_JSONB_TEXT_V1_SHA256", "SHA256")}]` }), "INVALID_VALUE");
  const badAlgorithmMissingDigest = model(1).replace("PG_JSONB_TEXT_V1_SHA256", "SHA256").replace(`,"digest":"${digest}"`, "");
  assertRejected(schema({ models: `[${badAlgorithmMissingDigest}]` }), "REQUIRED_FIELD_MISSING", "$.models[0].digest");
  assertRejected(schema({ models: `[${Array.from({ length: 21 }, (_, index) => model(index + 1)).join(",")}]` }), "INVALID_COLLECTION");
});

test("页面组件集合、全局ID、边界、接边和重叠规则", () => {
  assertRejected(schema({ pages: "[]" }), "INVALID_COLLECTION", "$.pages");
  assertRejected(schema({ pages: `[${Array.from({ length: 6 }, (_, index) => page(`page_${index}`)).join(",")}]` }), "INVALID_COLLECTION");
  assertRejected(schema({ presentation: '{"mode":"FIXED_SCREEN"}', pages: `[${page("a")},${page("b")}]` }), "INVALID_COLLECTION");
  assertRejected(schema({ pages: `[${page("same")},${page("same")}]` }), "INVALID_COLLECTION", "$.pages[1]");
  assertRejected(schema({ pages: `[${page("a", component("same", '{"x":0,"y":0,"w":1,"h":1}'))},${page("b", component("same", '{"x":0,"y":0,"w":1,"h":1}'))}]` }), "INVALID_COLLECTION", "$.pages[1].components[0]");
  assertRejected(schema({ pages: `[${page("main", component("bad", '{"x":23,"y":0,"w":2,"h":1}'))}]` }), "INVALID_LAYOUT");
  assertRejected(schema({ pages: `[${page("main", `${component("a", '{"x":0,"y":0,"w":12,"h":8}')},${component("b", '{"x":11,"y":0,"w":12,"h":8}')}`)}]` }), "INVALID_LAYOUT");
  assertRejected(schema({ pages: `[${page("main", component("bad", '{"x":0.0,"y":0,"w":1,"h":1}'))}]` }), "TYPE_MISMATCH");
  assertRejected(schema({ pages: `[${page("main", component("bad", '{"x":99999999999999999999,"y":0,"w":1,"h":1}'))}]` }), "TYPE_MISMATCH", "$.pages[0].components[0].layout.x");
  assertRejected(schema({ pages: `[${page("main", component("bad", '{"x":0,"y":0,"w":1,"h":1,"z":1}'))}]` }), "UNKNOWN_FIELD");
  const fiftyOne = Array.from({ length: 51 }, (_, index) => component(`c_${index}`, `{"x":${index % 24},"y":${Math.floor(index / 24)},"w":1,"h":1}`)).join(",");
  assertRejected(schema({ pages: `[${page("main", fiftyOne)}]` }), "INVALID_COLLECTION");
});

test("完整组件语义拒绝未知slot并保留壳类型守卫", () => {
  assertRejected(schema({ pages: `[${page("main", component("probe", '{"x":0,"y":0,"w":1,"h":1}', '{"futureSlot":{"any":"value"}}'))}]` }), "UNKNOWN_FIELD");
  assertRejected(schema({ pages: `[${page("main", component("bad", '{"x":0,"y":0,"w":1,"h":1}').replace('"props":{}', '"props":[]'))}]` }), "TYPE_MISMATCH");
  assertRejected(schema({ pages: `[${page("main", component("bad", '{"x":0,"y":0,"w":1,"h":1}').replace('"kind":"TEXT"', '"kind":"UNKNOWN"'))}]` }), "INVALID_VALUE", "$.pages[0].components[0]");
  assertRejected(schema({ pages: `[${page("main", component("bad", '{"x":0,"y":0,"w":1,"h":1}').replace("1.0.0", "1.0.2"))}]` }), "INVALID_VALUE", "$.pages[0].components[0]");
  const unknownWithMalformedVersion = component("bad", '{"x":0,"y":0,"w":1,"h":1}')
    .replace('"kind":"TEXT"', '"kind":"UNKNOWN"').replace("1.0.0", "1.0");
  assertRejected(schema({ pages: `[${page("main", unknownWithMalformedVersion)}]` }), "INVALID_VALUE", "$.pages[0].components[0].componentVersion");
  const unknownWithoutVersion = component("bad", '{"x":0,"y":0,"w":1,"h":1}')
    .replace('"kind":"TEXT"', '"kind":"UNKNOWN"').replace(',"componentVersion":"1.0.0"', "");
  assertRejected(schema({ pages: `[${page("main", unknownWithoutVersion)}]` }), "REQUIRED_FIELD_MISSING", "$.pages[0].components[0].componentVersion");
});

test("四类变量按完整数组建立索引并注入默认值", () => {
  const result = context();
  const variables = result.normalizedRoot.variables;
  assert.equal(variables[0].required, true);
  assert.equal(requireJsonInteger(variables[1].maxItems, "$.variables[1].maxItems"), 20n);
  assert.deepEqual(variables[1].defaultDeviceIds, []);
  assert.equal(variables[2].defaultPreset, "LAST_1_HOUR");
  assert.deepEqual(variables[2].allowedPresets, ["LAST_1_HOUR", "LAST_24_HOURS", "LAST_7_DAYS"]);
});

test("变量上限、命名空间、默认集合和内部引用按稳定原因拒绝", () => {
  const enums = Array.from({ length: 21 }, (_, index) => `{"key":"v_${index}","type":"TEXT_ENUM","title":"枚举","options":[{"value":"x","label":"值"}]}`).join(",");
  assertRejected(schema({ models: `[${model(1, "pump_model")}]`, variables: `[${enums}]` }), "INVALID_COLLECTION");
  assertRejected(schema({ models: `[${model(1, "pump_model")}]`, variables: '[{"key":"pump_model","type":"TEXT_ENUM","title":"枚举","options":[{"value":"x","label":"值"}]}]' }), "INVALID_COLLECTION");
  assertRejected(schema({ models: `[${model(1, "pump_model")}]`, variables: '[{"key":"device","type":"DEVICE_SINGLE","title":"设备","modelKey":"missing"}]' }), "INVALID_REFERENCE");
  const duplicateDevices = `[{"key":"devices","type":"DEVICE_MULTI","title":"设备","modelKey":"pump_model","maxItems":2,"defaultDeviceIds":["00000000-0000-0000-0000-000000000001","00000000-0000-0000-0000-000000000001"]}]`;
  assertRejected(schema({ models: `[${model(1, "pump_model")}]`, variables: duplicateDevices }), "INVALID_COLLECTION");
  const oversizedLong = '[{"key":"devices","type":"DEVICE_MULTI","title":"设备","modelKey":"pump_model","maxItems":99999999999999999999}]';
  assertRejected(schema({ models: `[${model(1, "pump_model")}]`, variables: oversizedLong }), "TYPE_MISMATCH", "$.variables[0].maxItems");
  const badRange = '[{"key":"range","type":"TIME_RANGE","title":"时间","defaultPreset":"LAST_24_HOURS","allowedPresets":["LAST_1_HOUR"]}]';
  assertRejected(schema({ variables: badRange }), "INVALID_REFERENCE");
  const badEnum = '[{"key":"choice","type":"TEXT_ENUM","title":"枚举","options":[{"value":"x","label":"值"}],"defaultValue":"missing"}]';
  assertRejected(schema({ variables: badEnum }), "INVALID_REFERENCE");
});

test("六种Binding支持前向变量引用并返回内部投影", () => {
  const variables = context().normalizedRoot.variables;
  const cases = [
    ['{"source":"CURRENT_VALUE","device":{"variableKey":"multi"},"propertyKey":"temperature"}', "CURRENT_VALUE"],
    ['{"source":"HISTORY_SERIES","device":{"variableKey":"single"},"propertyKey":"temperature","timeRangeVariableKey":"range","granularity":"ONE_MINUTE","aggregation":"AVG"}', "HISTORY_SERIES"],
    ['{"source":"DEVICE_STATUS","device":{"variableKey":"single"}}', "DEVICE_STATUS"],
    ['{"source":"ALARM_LIST","devices":{"variableKey":"multi"},"conditionStates":["ACTIVE"],"ackStates":["UNACKNOWLEDGED"],"severities":["MAJOR"]}', "ALARM_LIST"],
    ['{"source":"DEVICE_DIRECTORY","variableKey":"multi"}', "DEVICE_DIRECTORY"],
    ['{"source":"ENUM_TEXT","variableKey":"choice"}', "ENUM_TEXT"],
  ];
  for (const [binding, source] of cases) {
    assert.equal(validateDashboardBinding(parseBinding(binding), "$.binding", variables).source, source);
  }
});

test("页面先于变量出现时Binding仍可引用后置变量", () => {
  const binding = '{"source":"CURRENT_VALUE","device":{"variableKey":"single"},"propertyKey":"temperature"}';
  const valueCard = component("probe", '{"x":0,"y":0,"w":1,"h":1}', `{"value":${binding}}`).replace('"kind":"TEXT"', '"kind":"VALUE_CARD"');
  const source = `{"schemaVersion":"tc.dashboard/v1","presentation":{"mode":"RESPONSIVE_GRID"},"pages":[${page("main", valueCard)}],"variables":${allVariables()},"models":[${model(1, "pump_model")}]}`;
  const result = validate(source);
  const normalizedBinding = result.normalizedRoot.pages[0].components[0].bindings.value;
  assert.equal(validateDashboardBinding(normalizedBinding, "$.binding", result.normalizedRoot.variables).variableKey, "single");
});

test("Binding闭集、基数、枚举数组和必填字段执行精确分支", () => {
  const variables = context().normalizedRoot.variables;
  const rejectBinding = (binding, reason) => assert.throws(
    () => validateDashboardBinding(parseBinding(binding), "$.binding", variables),
    (error) => error instanceof DashboardContractViolation && error.reason === reason,
  );
  rejectBinding('{"source":"HISTORY_SERIES","device":{"variableKey":"multi"},"propertyKey":"temperature","timeRangeVariableKey":"range","granularity":"ONE_MINUTE","aggregation":"AVG"}', "INVALID_REFERENCE");
  rejectBinding('{"source":"DEVICE_STATUS","device":{"variableKey":"multi"}}', "INVALID_REFERENCE");
  rejectBinding('{"source":"DEVICE_DIRECTORY","variableKey":"range"}', "INVALID_REFERENCE");
  rejectBinding('{"source":"ENUM_TEXT","variableKey":"single"}', "INVALID_REFERENCE");
  rejectBinding('{"source":"ALARM_LIST","devices":{"variableKey":"multi"},"conditionStates":[],"ackStates":["UNACKNOWLEDGED"],"severities":["MAJOR"]}', "INVALID_COLLECTION");
  rejectBinding('{"source":"CURRENT_VALUE","device":{"variableKey":"single","deviceId":"hidden"},"propertyKey":"temperature"}', "UNKNOWN_FIELD");
  rejectBinding('{"source":"CURRENT_VALUE","device":{"variableKey":"single"},"extra":true}', "UNKNOWN_FIELD");
  rejectBinding('{"source":"INVALID_SECRET","extra":true}', "INVALID_VALUE");
});

test("首错顺序先处理根闭集，并先完成变量分支再判断重复key", () => {
  assertRejected(schema({ pages: '[{"id":"main","title":null,"components":[]}]', extra: ',"unknown":true' }), "UNKNOWN_FIELD", "$");
  const variables = `[{"key":"same","type":"TEXT_ENUM","title":"枚举","options":[{"value":"x","label":"值"}]},{"key":"same","type":"DEVICE_SINGLE","title":"设备","modelKey":"missing"}]`;
  assertRejected(schema({ variables }), "INVALID_REFERENCE");
});
