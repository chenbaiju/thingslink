import assert from "node:assert/strict";
import test from "node:test";
import { validateDashboardV1Schema } from "../dist/dashboard/v1/schema-validator.js";
import { DashboardContractViolation } from "../dist/dashboard/v1/violation.js";

const encoder = new TextEncoder();
const digest = "a".repeat(64);

function current(variableKey, propertyKey = "temperature") {
  return `{"source":"CURRENT_VALUE","device":{"variableKey":"${variableKey}"},"propertyKey":"${propertyKey}"}`;
}

function history(propertyKey = "temperature", aggregation = "AVG") {
  return `{"source":"HISTORY_SERIES","device":{"variableKey":"single"},"propertyKey":"${propertyKey}","timeRangeVariableKey":"range","granularity":"ONE_MINUTE","aggregation":"${aggregation}"}`;
}

function component(id, kind, props, bindings, x = 0) {
  return `{"id":"${id}","kind":"${kind}","componentVersion":"1.0.0","layout":{"x":${x},"y":0,"w":1,"h":1},"props":${props},"bindings":${bindings}}`;
}

function schema(components, variables = defaultVariables()) {
  return `{"schemaVersion":"tc.dashboard/v1","presentation":{"mode":"RESPONSIVE_GRID"},"models":[{"key":"pump_model","versionId":"00000000-0000-0000-0000-000000000001","digestAlgorithm":"PG_JSONB_TEXT_V1_SHA256","digest":"${digest}","profile":"TC_PROPERTY_COMPOSITE_V1"}],"variables":${variables},"pages":[{"id":"main","title":"主页","components":[${components}]}]}`;
}

function defaultVariables() {
  return `[{"key":"single","type":"DEVICE_SINGLE","title":"单设备","modelKey":"pump_model"},{"key":"multi","type":"DEVICE_MULTI","title":"多设备","modelKey":"pump_model"},{"key":"multi_alt","type":"DEVICE_MULTI","title":"另一设备组","modelKey":"pump_model"},{"key":"range","type":"TIME_RANGE","title":"时间"},{"key":"choice","type":"TEXT_ENUM","title":"选择","options":[{"value":"normal","label":"正常"}]}]`;
}

function validate(source) {
  return validateDashboardV1Schema(encoder.encode(source));
}

function assertRejected(componentSource, reason, path) {
  assert.throws(
    () => validate(schema(componentSource)),
    (error) => error instanceof DashboardContractViolation && error.reason === reason
      && (path === undefined || error.path === path),
  );
}

test("完整Schema接入十组件并投影类型化未决需求", () => {
  const components = [
    component("text", "TEXT", "{}", "{}", 0),
    component("image", "IMAGE", `{"resourceId":"empty_state","resourceDigest":"${digest}","alt":"暂无"}`, "{}", 1),
    component("card", "VALUE_CARD", "{}", `{"value":${current("single")}}`, 2),
    component("status", "STATUS", "{}", '{"status":{"source":"DEVICE_STATUS","device":{"variableKey":"single"}}}', 3),
    component("gauge", "GAUGE", "{}", `{"value":${current("single")}}`, 4),
    component("line", "LINE_CHART", '{"series":[{"id":"temperature","label":"温度"}]}', `{"series":[{"id":"temperature","value":${history()}}]}`, 5),
    component("table", "TABLE", '{"mode":"LIST_VALUE"}', `{"value":${current("single", "payload")}}`, 6),
    component("json", "JSON_VIEW", "{}", `{"value":${current("single", "payload")}}`, 7),
    component("alarms", "ALARM_LIST", "{}", '{"alarms":{"source":"ALARM_LIST","devices":{"variableKey":"multi"},"conditionStates":["ACTIVE"],"ackStates":["UNACKNOWLEDGED"],"severities":["MAJOR"]}}', 8),
    component("selector", "DEVICE_SELECTOR", "{}", '{"directory":{"source":"DEVICE_DIRECTORY","variableKey":"multi"}}', 9),
  ].join(",");
  const result = validate(schema(components));
  const patched = validate(schema(components).replaceAll('"componentVersion":"1.0.0"', '"componentVersion":"1.0.1"'));
  assert.deepEqual(patched.normalizedRoot.pages[0].components.map(c => ({ ...c, componentVersion: "1.0.0" })), result.normalizedRoot.pages[0].components.map(c => ({ ...c })));
  const patchedHosts = patched.unresolvedRequirements.filter(r => r.requirementType === "HOST_COMPONENT");
  assert.equal(patchedHosts.length, 10);
  for (const requirement of patchedHosts) {
    assert.equal(requirement.componentVersion, "1.0.1");
    assert.deepEqual(requirement.hostRange, { minInclusive: "1.1.0", maxExclusive: "1.1.2" });
  }
  assert.equal(result.scope, "COMPLETE_INTERNAL_SCHEMA_SEMANTICS");
  assert.equal(result.normalizedRoot.pages[0].components.length, 10);
  assert.equal(result.unresolvedRequirements.filter(({ requirementType }) => requirementType === "HOST_COMPONENT").length, 10);
  assert.ok(result.unresolvedRequirements.some(({ requirementType }) => requirementType === "MODEL_REFERENCE"));
  assert.ok(result.unresolvedRequirements.some((requirement) => requirement.requirementType === "BUILTIN_RESOURCE" && requirement.resourceId === "empty_state"));
  assert.ok(result.unresolvedRequirements.some((requirement) => requirement.requirementType === "MODEL_GAUGE_RANGE"));
  assert.ok(result.unresolvedRequirements.some((requirement) => requirement.requirementType === "DATA_ADAPTER" && requirement.capability === "HISTORY_SERIES"));
  assert.equal(Object.isFrozen(result.unresolvedRequirements), true);
  assert.equal(Object.isFrozen(result.unresolvedRequirements.find(({ requirementType }) => requirementType === "HOST_COMPONENT").hostRange), true);
});

test("根模型和默认设备需求保持模型到设备再到组件的顺序", () => {
  const variables = `[{"key":"single","type":"DEVICE_SINGLE","title":"单设备","modelKey":"pump_model","defaultDeviceId":"00000000-0000-0000-0000-000000000101"},{"key":"multi","type":"DEVICE_MULTI","title":"多设备","modelKey":"pump_model","defaultDeviceIds":["00000000-0000-0000-0000-000000000102"]},{"key":"range","type":"TIME_RANGE","title":"时间"},{"key":"choice","type":"TEXT_ENUM","title":"选择","options":[{"value":"normal","label":"正常"}]}]`;
  const result = validate(schema(component("text", "TEXT", "{}", "{}"), variables));
  assert.deepEqual(result.unresolvedRequirements.slice(0, 4).map(({ requirementType }) => requirementType), [
    "MODEL_REFERENCE", "DEFAULT_DEVICE", "DEFAULT_DEVICE", "HOST_COMPONENT",
  ]);
});

test("TEXT静态与动态默认化并拒绝双来源", () => {
  const staticResult = validate(schema(component("text", "TEXT", "{}", "{}")));
  assert.deepEqual(Object.fromEntries(Object.entries(staticResult.normalizedRoot.pages[0].components[0].props)), {
    content: "", align: "LEFT", size: "MEDIUM", tone: "REGULAR",
  });
  const dynamic = validate(schema(component("text", "TEXT", '{"align":"CENTER"}', '{"text":{"source":"ENUM_TEXT","variableKey":"choice"}}')));
  assert.equal("content" in dynamic.normalizedRoot.pages[0].components[0].props, false);
  assertRejected(component("text", "TEXT", '{"content":"静态"}', '{"text":{"source":"ENUM_TEXT","variableKey":"choice"}}'), "INVALID_VALUE", "$.pages[0].components[0]");
  assertRejected(component("text", "TEXT", "{}", '{"text":{"source":"DEVICE_STATUS","device":{"variableKey":"single"}}}'), "INVALID_REFERENCE", "$.pages[0].components[0].bindings.text");
});

test("IMAGE、VALUE_CARD、STATUS、JSON_VIEW、ALARM_LIST与SELECTOR拒绝关键越界", () => {
  const imageProps = `{"resourceId":"empty_state","resourceDigest":"${digest}","alt":""}`;
  assertRejected(component("image", "IMAGE", imageProps, `{"value":${current("single")}}`), "UNKNOWN_FIELD", "$.pages[0].components[0].bindings");
  assertRejected(component("card", "VALUE_CARD", "{}", `{"value":${current("multi")}}`), "INVALID_REFERENCE", "$.pages[0].components[0].bindings.value");
  assertRejected(component("status", "STATUS", "{}", '{"status":{"source":"DEVICE_STATUS","device":{"variableKey":"multi"}}}'), "INVALID_REFERENCE", "$.pages[0].components[0].bindings.status.device.variableKey");
  assertRejected(component("json", "JSON_VIEW", '{"initialExpandDepth":3}', `{"value":${current("single", "payload")}}`), "INVALID_VALUE", "$.pages[0].components[0].props.initialExpandDepth");
  assertRejected(component("alarms", "ALARM_LIST", '{"pageSize":0}', '{"alarms":{"source":"ALARM_LIST","devices":{"variableKey":"multi"},"conditionStates":["ACTIVE"],"ackStates":["UNACKNOWLEDGED"],"severities":["MAJOR"]}}'), "INVALID_VALUE", "$.pages[0].components[0].props.pageSize");
  assertRejected(component("selector", "DEVICE_SELECTOR", '{"pageSize":51}', '{"directory":{"source":"DEVICE_DIRECTORY","variableKey":"multi"}}'), "INVALID_VALUE", "$.pages[0].components[0].props.pageSize");
});

test("GAUGE保持量程首错、精确十进制比较和外部需求边界", () => {
  const slot = `{"value":${current("single")}}`;
  assertRejected(component("gauge", "GAUGE", '{"min":0}', slot), "INVALID_VALUE", "$.pages[0].components[0].props");
  assertRejected(component("gauge", "GAUGE", '{"scaleMode":"EXPLICIT","min":0}', slot), "REQUIRED_FIELD_MISSING", "$.pages[0].components[0].props.max");
  assertRejected(component("gauge", "GAUGE", '{"scaleMode":"EXPLICIT","min":"bad"}', slot), "REQUIRED_FIELD_MISSING", "$.pages[0].components[0].props.max");
  assertRejected(component("gauge", "GAUGE", '{"scaleMode":"EXPLICIT","min":1.00000000000001,"max":1.00000000000000}', slot), "INVALID_VALUE", "$.pages[0].components[0].props");
  assertRejected(component("gauge", "GAUGE", '{"precision":9223372036854775808,"min":0}', "{}"), "TYPE_MISMATCH", "$.pages[0].components[0].props.precision");
  const explicit = validate(schema(component("gauge", "GAUGE", '{"scaleMode":"EXPLICIT","min":-1.5,"max":2}', slot)));
  assert.equal(explicit.unresolvedRequirements.some(({ requirementType }) => requirementType === "MODEL_GAUGE_RANGE"), false);
});

test("最终结果按Jackson整数和BigDecimal语义规范数字且不改变校验前词法", () => {
  const slot = `{"value":${current("single")}}`;
  const negativeZero = validate(schema(component("gauge", "GAUGE", '{"scaleMode":"EXPLICIT","min":-0,"max":1e0}', slot)));
  assert.equal(negativeZero.normalizedRoot.pages[0].components[0].props.min.lexical, "0");
  assert.equal(negativeZero.normalizedRoot.pages[0].components[0].props.max.lexical, "1");

  const exponentAndScale = validate(schema(component("gauge", "GAUGE", '{"scaleMode":"EXPLICIT","min":1e-7,"max":1.2300e2}', slot)));
  assert.equal(exponentAndScale.normalizedRoot.pages[0].components[0].props.min.lexical, "1E-7");
  assert.equal(exponentAndScale.normalizedRoot.pages[0].components[0].props.max.lexical, "123.00");

  assertRejected(component("gauge", "GAUGE", '{"scaleMode":"EXPLICIT","min":0,"max":2,"precision":2e0}', slot), "TYPE_MISMATCH", "$.pages[0].components[0].props.precision");
});

test("LINE_CHART逐索引映射并按完整历史身份去重", () => {
  const props = '{"series":[{"id":"first","label":"第一"},{"id":"second","label":"第二"}]}';
  const distinct = `{"series":[{"id":"first","value":${history("temperature", "AVG")}},{"id":"second","value":${history("temperature", "MAX")}}]}`;
  const result = validate(schema(component("line", "LINE_CHART", props, distinct)));
  assert.equal(result.normalizedRoot.pages[0].components[0].props.showLegend, true);
  assert.equal(result.unresolvedRequirements.filter(({ requirementType }) => requirementType === "HISTORICAL_PROPERTY").length, 2);
  const duplicate = `{"series":[{"id":"first","value":${history("temperature", "AVG")}},{"id":"second","value":${history("temperature", "AVG")}}]}`;
  assertRejected(component("line", "LINE_CHART", props, duplicate), "INVALID_COLLECTION", "$.pages[0].components[0].bindings.series");
  const wrongOrder = `{"series":[{"id":"second","value":${history() }},{"id":"first","value":${history("pressure")}}]}`;
  assertRejected(component("line", "LINE_CHART", props, wrongOrder), "INVALID_REFERENCE", "$.pages[0].components[0].bindings.series[0].id");
});

test("TABLE两模式保持分支顺序、列映射和共同多设备变量", () => {
  const list = validate(schema(component("table", "TABLE", '{"mode":"LIST_VALUE"}', `{"value":${current("single", "payload")}}`)));
  assert.equal(list.normalizedRoot.pages[0].components[0].props.rowLimit.lexical, "20");
  assert.ok(list.unresolvedRequirements.some((requirement) => requirement.requirementType === "MODEL_PROPERTY" && requirement.allowedDataTypes.length === 1 && requirement.allowedDataTypes[0] === "LIST"));
  assertRejected(component("table", "TABLE", '{"mode":"LIST_VALUE","columns":[]}', "{}"), "INVALID_VALUE", "$.pages[0].components[0].props");
  const props = '{"mode":"DEVICE_VALUES","columns":[{"id":"first","label":"一"},{"id":"second","label":"二"}]}';
  const valid = `{"columns":[{"id":"first","value":${current("multi", "temperature")}},{"id":"second","value":${current("multi", "pressure")}}]}`;
  const result = validate(schema(component("table", "TABLE", props, valid)));
  assert.equal(result.unresolvedRequirements.filter(({ requirementType }) => requirementType === "MODEL_PROPERTY").length, 2);
  const mixed = `{"columns":[{"id":"first","value":${current("multi", "temperature")}},{"id":"second","value":${current("multi_alt", "pressure")}}]}`;
  assertRejected(component("table", "TABLE", props, mixed), "INVALID_REFERENCE", "$.pages[0].components[0].bindings.columns");
});

test("组件语义错误先于与前项布局重叠", () => {
  const first = component("first", "TEXT", "{}", "{}", 0);
  const invalidOverlapping = component("second", "VALUE_CARD", '{"precision":7}', "{}", 0);
  assertRejected(`${first},${invalidOverlapping}`, "INVALID_VALUE", "$.pages[0].components[1].props.precision");
});

test("最终默认注入后的512000字节边界只在完整组件语义后执行", () => {
  const baseResult = validate(normalizationBoundarySchema(0));
  const baseBytes = normalizedBytes(baseResult.normalizedRoot);
  const exactContent = 512000 - baseBytes;
  assert.ok(exactContent >= 0 && exactContent <= 124 * 4096);
  const exact = normalizationBoundarySchema(exactContent);
  const overflow = normalizationBoundarySchema(exactContent + 1);
  const normalizedGauge = validate(exact).normalizedRoot.pages[0].components[0];
  assert.equal(normalizedGauge.props.min.lexical, "0");
  assert.equal(normalizedGauge.props.max.lexical, "123.00");
  assert.equal(normalizedBytes(validate(exact).normalizedRoot), 512000);
  assert.ok(encoder.encode(overflow).length <= 512000);
  assert.throws(
    () => validate(overflow),
    (error) => error instanceof DashboardContractViolation && error.reason === "NORMALIZED_TOO_LARGE" && error.path === "$",
  );
});

function normalizationBoundarySchema(contentBytes) {
  let remaining = contentBytes;
  const pages = [];
  for (let pageIndex = 0; pageIndex < 5; pageIndex++) {
    const components = [];
    for (let index = 0; index < 25; index++) {
      if (pageIndex === 0 && index === 0) {
        components.push(component("gauge_boundary", "GAUGE", '{"scaleMode":"EXPLICIT","min":-0,"max":1.2300e2}', `{"value":${current("single")}}`, 0));
        continue;
      }
      const length = Math.min(remaining, 4096);
      remaining -= length;
      components.push(component(`text_${pageIndex}_${index}`, "TEXT", `{"content":"${"a".repeat(length)}"}`, "{}", index % 24).replace('"y":0', `"y":${Math.floor(index / 24)}`));
    }
    pages.push(`{"id":"page_${pageIndex}","title":"页","components":[${components.join(",")}]}`);
  }
  const models = `[{"key":"pump_model","versionId":"00000000-0000-0000-0000-000000000001","digestAlgorithm":"PG_JSONB_TEXT_V1_SHA256","digest":"${digest}","profile":"TC_PROPERTY_COMPOSITE_V1"}]`;
  const variables = '[{"key":"single","type":"DEVICE_SINGLE","title":"单设备","modelKey":"pump_model"}]';
  return `{"schemaVersion":"tc.dashboard/v1","presentation":{"mode":"RESPONSIVE_GRID"},"models":${models},"variables":${variables},"pages":[${pages.join(",")}]}`;
}

function normalizedBytes(value) {
  return encoder.encode(stringify(value)).length;
}

function stringify(value) {
  if (value === null || typeof value === "boolean") return String(value);
  if (typeof value === "string") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(stringify).join(",")}]`;
  if (value.type === "JSON_NUMBER" && typeof value.lexical === "string") return value.lexical;
  return `{${Object.entries(value).map(([key, nested]) => `${JSON.stringify(key)}:${stringify(nested)}`).join(",")}}`;
}

test("新补丁精确投影新宿主范围并保留旧版本需求", () => {
  const source = schema(component("text", "TEXT", "{}", "{}"));
  const oldResult = validate(source);
  const newResult = validate(source.replaceAll('"componentVersion":"1.0.0"', '"componentVersion":"1.0.1"'));
  const host = result => result.unresolvedRequirements.find(r => r.requirementType === "HOST_COMPONENT");
  assert.deepEqual(host(oldResult).hostRange, { minInclusive: "1.0.0", maxExclusive: "1.0.1" });
  assert.equal(host(newResult).componentVersion, "1.0.1");
  assert.deepEqual(host(newResult).hostRange, { minInclusive: "1.1.0", maxExclusive: "1.1.2" });
  assert.deepEqual(newResult.normalizedRoot.pages[0].components[0].props, oldResult.normalizedRoot.pages[0].components[0].props);
});
