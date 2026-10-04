import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { validateDashboardV1Contract } from "../scripts/dashboard-v1-generator.mjs";

const source = JSON.parse(await readFile(new URL("../contracts/dashboard-v1.json", import.meta.url), "utf8"));

function candidate(mutator) {
  const value = structuredClone(source);
  mutator(value);
  return value;
}

test("生成器拒绝组件和属性描述符的额外字段", () => {
  assert.throws(
    () => validateDashboardV1Contract(candidate((value) => { value.components[0].renderer = "Text"; })),
    /组件字段/,
  );
  assert.throws(
    () => validateDashboardV1Contract(candidate((value) => { value.components[0].props.content.note = "unknown"; })),
    /props\.content字段/,
  );
});

test("生成器封闭slot来源并绑定值类型与基数", () => {
  assert.throws(
    () => validateDashboardV1Contract(candidate((value) => { value.components[0].slots.text.sources.push("UNKNOWN"); })),
    /sources必须属于bindingSources闭集/,
  );
  assert.throws(
    () => validateDashboardV1Contract(candidate((value) => { value.components[0].slots.text.sources.push("ENUM_TEXT"); })),
    /sources必须是非空且无重复/,
  );
  assert.throws(
    () => validateDashboardV1Contract(candidate((value) => { value.components[0].slots.text.cardinality = "MULTIPLE"; })),
    /cardinality必须与valueType一致/,
  );
  assert.throws(
    () => validateDashboardV1Contract(candidate((value) => { value.components[5].slots.series.cardinality = "SINGLE"; })),
    /cardinality必须与valueType一致/,
  );
});

test("生成器封闭slot、数据类型和HostRange定义", () => {
  assert.throws(
    () => validateDashboardV1Contract(candidate((value) => { delete value.components[0].slots.text.sources; })),
    /slots\.text字段/,
  );
  assert.throws(
    () => validateDashboardV1Contract(candidate((value) => { value.components[2].supportedDataTypes.values.push("UNKNOWN"); })),
    /propertyDataTypes子集/,
  );
  assert.throws(
    () => validateDashboardV1Contract(candidate((value) => { value.components[2].supportedDataTypes.applicability = "OPTIONAL"; })),
    /applicability不在闭集内/,
  );
  assert.throws(
    () => validateDashboardV1Contract(candidate((value) => { value.components[0].hostRange.patch = "1.0.0"; })),
    /hostRange必须为/,
  );
});

test("新组件补丁保持原语义且不能扩大历史版本范围", () => {
  assert.throws(() => validateDashboardV1Contract(candidate(v => { v.components[0].hostRange.maxExclusive = "1.1.2"; })), /hostRange必须为/);
  assert.throws(() => validateDashboardV1Contract(candidate(v => { v.components[10].props.content.default = "改写"; })), /补丁语义/);
  assert.throws(() => validateDashboardV1Contract(candidate(v => { v.components[10].componentVersion = "1.0.0"; })), /components.kind\/version/);
});
