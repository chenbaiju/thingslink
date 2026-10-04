import assert from "node:assert/strict";
import test from "node:test";
import {
  compareNumericSemVer,
  dashboardV1Contract,
  isHostVersionCompatible,
  parseNumericSemVer,
} from "../dist/dashboard/v1/index.js";

test("数字SemVer严格拒绝前导零、缺段、后缀和越界段", () => {
  assert.deepEqual(parseNumericSemVer("10.2.3"), { major: 10, minor: 2, patch: 3 });
  for (const value of [
    "1.2", "01.2.3", "1.02.3", "1.2.03", "1.2.3-alpha", "1.2.3+build", "1.0.0\n", "65536.0.0",
    "١.٠.٠", "１.０.０",
  ]) {
    assert.equal(parseNumericSemVer(value), null, value);
  }
});

test("数字SemVer比较不使用字符串字典序", () => {
  const ten = parseNumericSemVer("10.0.0");
  const two = parseNumericSemVer("2.0.0");
  assert.ok(ten !== null && two !== null);
  assert.ok(compareNumericSemVer(ten, two) > 0);
});

test("宿主兼容范围包含下界并排除上界", () => {
  const range = dashboardV1Contract.hostRange;
  assert.equal(isHostVersionCompatible("1.0.0", range), true);
  assert.equal(isHostVersionCompatible("1.0.1", range), false);
  assert.equal(isHostVersionCompatible("0.9.99", range), false);
  assert.equal(isHostVersionCompatible("1.0.0-alpha", range), false);
  assert.equal(isHostVersionCompatible("1.0.0", { minInclusive: "2.0.0", maxExclusive: "1.0.0" }), false);
});
