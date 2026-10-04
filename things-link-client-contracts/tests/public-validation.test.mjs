import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import {
  DASHBOARD_SCHEMA_SCOPE,
  DashboardContractViolation,
  validateDashboardSchemaV1,
} from "@things-link/client-contracts/dashboard/v1";

/** 浏览器与Node共同使用的UTF-8测试编码器。 */
const encoder = new TextEncoder();

/** 构造包含一个静态TEXT的最小完整Schema。 */
function source() {
  return encoder.encode('{"schemaVersion":"tc.dashboard/v1","presentation":{"mode":"RESPONSIVE_GRID"},"pages":[{"id":"main","title":"主页","components":[{"id":"title","kind":"TEXT","componentVersion":"1.0.0","layout":{"x":0,"y":0,"w":24,"h":8},"props":{},"bindings":{}}]}]}');
}

/** 递归断言公开结果中的全部对象和数组已冻结。 */
function assertDeepFrozen(root) {
  const pending = [root];
  while (pending.length > 0) {
    const value = pending.pop();
    assert.equal(Object.isFrozen(value), true);
    for (const nested of Object.values(value)) {
      if (nested !== null && typeof nested === "object") pending.push(nested);
    }
  }
}

test("高层入口返回深只读类型化Schema、精确JSON和有序未决需求", () => {
  const result = validateDashboardSchemaV1(source());

  assert.equal(result.scope, DASHBOARD_SCHEMA_SCOPE);
  assert.equal(result.schema.presentation.columns, 24);
  assert.equal(result.schema.pages[0].components[0].props.content, "");
  assert.equal(result.normalizedJson,
    '{"schemaVersion":"tc.dashboard/v1","presentation":{"mode":"RESPONSIVE_GRID","theme":"LIGHT","columns":24,"rowHeight":8,"gap":8},"pages":[{"id":"main","title":"主页","components":[{"id":"title","kind":"TEXT","componentVersion":"1.0.0","layout":{"x":0,"y":0,"w":24,"h":8},"props":{"content":"","align":"LEFT","size":"MEDIUM","tone":"REGULAR"},"bindings":{}}]}],"models":[],"variables":[]}');
  assert.deepEqual(result.unresolvedRequirements, [{
    requirementType: "HOST_COMPONENT",
    kind: "TEXT",
    componentVersion: "1.0.0",
    hostRange: { minInclusive: "1.0.0", maxExclusive: "1.0.1" },
  }]);
  assertDeepFrozen(result);
  assert.throws(() => { result.schema.pages[0].components[0].layout.x = 1; }, TypeError);
  assert.throws(() => { result.unresolvedRequirements.push({}); }, TypeError);
});

test("高层入口公开稳定异常类型、原因和路径", () => {
  assert.throws(
    () => validateDashboardSchemaV1(encoder.encode('{"schemaVersion":"tc.dashboard/v1","attacker_field":true}')),
    (error) => error instanceof DashboardContractViolation
      && error.name === "DashboardContractViolation"
      && error.reason === "UNKNOWN_FIELD"
      && error.path === "$"
      && !error.message.includes("attacker_field"),
  );
});

test("版本化包仅导出登记校验器及纯呈现入口，内部Schema辅助保持不可见", async () => {
  const publicApi = await import("@things-link/client-contracts/dashboard/v1");
  assert.deepEqual(Object.keys(publicApi), [
    "CompositeValueError",
    "DASHBOARD_SCHEMA_SCOPE",
    "DashboardContractViolation",
    "ObservationResponseError",
    "compareNumericSemVer",
    "createRuntimeNumber",
    "dashboardV1Contract",
    "decodeAlarmResponse",
    "decodeHistoryResponse",
    "formatDecimal",
    "formatScalar",
    "gaugePosition",
    "historyGeometry",
    "historyRange",
    "historySegments",
    "initiallyExpanded",
    "interactionStateLabel",
    "isHostVersionCompatible",
    "isRuntimeNumber",
    "isStrictJsonNumber",
    "localTablePage",
    "parseCompositeValue",
    "parseDashboardRuntimeResponse",
    "parseDashboardSchemaEnvelope",
    "parseNumericSemVer",
    "runtimeChildren",
    "runtimeContainer",
    "serializeRuntimeValue",
    "utcTimestamp",
    "validateDashboardSchemaV1",
  ]);
  for (const internalName of [
    "parseDashboardV1Json",
    "validateDashboardV1Schema",
    "validateDashboardBinding",
    "requireConfigNumber",
    "requireJsonInteger",
    "createStrictJsonNumber",
  ]) {
    assert.equal(Object.hasOwn(publicApi, internalName), false, internalName);
  }

  const packageJson = JSON.parse(await readFile(new URL("../package.json", import.meta.url), "utf8"));
  assert.deepEqual(Object.keys(packageJson.exports), ["./dashboard/v1"]);
  assert.deepEqual(packageJson.exports["./dashboard/v1"], {
    types: "./dist/dashboard/v1/index.d.ts",
    import: "./dist/dashboard/v1/index.js",
  });
  assert.deepEqual(packageJson.files, ["dist"]);
});
