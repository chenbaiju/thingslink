import assert from "node:assert/strict";
import test from "node:test";
import { parseDashboardV1Json } from "../dist/dashboard/v1/strict-json.js";
import { DashboardContractViolation } from "../dist/dashboard/v1/violation.js";

const encoder = new TextEncoder();

function bytes(value) {
  return encoder.encode(value);
}

function assertRejected(source, reason, path) {
  assert.throws(
    () => parseDashboardV1Json(source),
    (error) => error instanceof DashboardContractViolation
      && error.reason === reason
      && (path === undefined || error.path === path),
  );
}

function schemaWithNumber(number) {
  return `{"schemaVersion":"tc.dashboard/v1","number":${number}}`;
}

function nestedSchema(arrayLayers) {
  return `{"schemaVersion":"tc.dashboard/v1","value":${"[".repeat(arrayLayers)}0${"]".repeat(arrayLayers)}}`;
}

test("版本化公开入口不暴露半完成解析和值规则", async () => {
  const publicApi = await import("../dist/dashboard/v1/index.js");
  for (const name of [
    "parseDashboardV1Json",
    "validateDashboardV1Schema",
    "validateDashboardBinding",
    "requireConfigNumber",
  ]) {
    assert.equal(Object.hasOwn(publicApi, name), false, name);
  }
});

test("原文字节上限先于内容解析执行", () => {
  const minimum = bytes('{"schemaVersion":"tc.dashboard/v1"}');
  const exact = new Uint8Array(512000);
  exact.fill(0x20);
  exact.set(minimum);
  assert.equal(parseDashboardV1Json(exact).schemaVersion, "tc.dashboard/v1");
  const over = new Uint8Array(512001);
  over.set([0xef, 0xbb, 0xbf]);
  assertRejected(over, "RAW_TOO_LARGE", "$");
});

test("严格UTF-8与BOM分别给出稳定原因", () => {
  assertRejected(Uint8Array.of(0xc3, 0x28), "INVALID_UTF8", "$");
  const valid = bytes('{"schemaVersion":"tc.dashboard/v1"}');
  const bom = new Uint8Array(valid.length + 3);
  bom.set([0xef, 0xbb, 0xbf]);
  bom.set(valid, 3);
  assertRejected(bom, "BOM_NOT_ALLOWED", "$");
  assertRejected(Uint8Array.of(0xef, 0xbb, 0xbf, 0xc3, 0x28), "BOM_NOT_ALLOWED", "$");
});

test("尾随合法值与尾随非法词法保持不同稳定原因", () => {
  assertRejected(bytes('{"schemaVersion":"tc.dashboard/v1"} []'), "TRAILING_VALUE", "$");
  assertRejected(bytes('{"schemaVersion":"tc.dashboard/v1"} true'), "TRAILING_VALUE", "$");
  assertRejected(bytes('{"schemaVersion":"tc.dashboard/v1"} "unterminated'), "TRAILING_VALUE", "$");
  assertRejected(bytes('{"schemaVersion":"tc.dashboard/v1"} "\\uD800"'), "TRAILING_VALUE", "$");
  assertRejected(bytes('{"schemaVersion":"tc.dashboard/v1"} "\\u0000"'), "TRAILING_VALUE", "$");
  assertRejected(bytes('{"schemaVersion":"tc.dashboard/v1"} x'), "INVALID_JSON", "$");
  assertRejected(bytes('{"schemaVersion":"tc.dashboard/v1"} turtle'), "INVALID_JSON", "$");
});

test("属性名在JSON转义解码后按所在对象判重", () => {
  assertRejected(
    bytes('{"schemaVersion":"tc.dashboard/v1","nested":{"kind":1,"\\u006bind":2}}'),
    "DUPLICATE_KEY",
    '$["<unknown>"].kind',
  );
  const separateScopes = parseDashboardV1Json(bytes(
    '{"schemaVersion":"tc.dashboard/v1","left":{"kind":1},"right":{"kind":2}}',
  ));
  assert.equal(separateScopes.left.kind.lexical, "1");
  assert.equal(separateScopes.right.kind.lexical, "2");
});

test("特殊原型属性按普通JSON键处理", () => {
  const root = parseDashboardV1Json(bytes('{"schemaVersion":"tc.dashboard/v1","__proto__":1,"constructor":2}'));
  assert.equal(Object.getPrototypeOf(root), null);
  assert.equal(root.__proto__.lexical, "1");
  assert.equal(root.constructor.lexical, "2");
  assertRejected(bytes('{"schemaVersion":"tc.dashboard/v1","__proto__":1,"__proto__":2}'), "DUPLICATE_KEY", '$["<unknown>"]');
});

test("非法JSON数字与数字token边界保持稳定", () => {
  for (const number of ["01", "1.", ".1", "+1", "NaN", "Infinity", "1e"]) {
    assertRejected(bytes(schemaWithNumber(number)), "INVALID_JSON", '$["<unknown>"]');
  }
  const accepted = parseDashboardV1Json(bytes(schemaWithNumber("1".repeat(64))));
  assert.equal(accepted.number.lexical.length, 64);
  assert.equal(parseDashboardV1Json(bytes(schemaWithNumber(`-${"1".repeat(63)}`))).number.lexical.length, 64);
  assertRejected(bytes(schemaWithNumber("1".repeat(65))), "NUMBER_TOO_LONG", '$["<unknown>"]');
  assertRejected(bytes(schemaWithNumber(`${"1".repeat(64)}e13`)), "NUMBER_TOO_LONG", '$["<unknown>"]');
  assertRejected(bytes(schemaWithNumber("1".repeat(1001))), "NUMBER_TOO_LONG", '$["<unknown>"]');
});

test("指数允许边界并拒绝位数、前导零和绝对值越界", () => {
  for (const number of ["1e0", "1e+9", "1E-12", "1.25e12"]) {
    assert.equal(parseDashboardV1Json(bytes(schemaWithNumber(number))).number.lexical, number);
  }
  for (const number of ["1e00", "1e01", "1e013", "1e13", "1e-13", "1e999999999"]) {
    assertRejected(bytes(schemaWithNumber(number)), "INVALID_EXPONENT", '$["<unknown>"]');
  }
});

test("Unicode区分未转义控制字符与解码后禁止标量", () => {
  assertRejected(bytes('{"schemaVersion":"tc.dashboard/v1","value":"\0"}'), "INVALID_JSON", "$.value");
  assertRejected(bytes('{"schemaVersion":"tc.dashboard/v1","value":"\\u0000"}'), "INVALID_UNICODE", "$.value");
  assertRejected(bytes('{"schemaVersion":"tc.dashboard/v1","value":"\\uD800"}'), "INVALID_UNICODE", "$.value");
  assertRejected(bytes('{"schemaVersion":"tc.dashboard/v1","\\uDC00":true}'), "INVALID_UNICODE", "$");
  assert.equal(parseDashboardV1Json(bytes('{"schemaVersion":"tc.dashboard/v1","value":"😀"}')).value, "😀");
  assert.equal(parseDashboardV1Json(bytes('{"schemaVersion":"tc.dashboard/v1","value":"�"}')).value, "�");
});

test("根对象计第一层并执行16层嵌套上限", () => {
  assert.equal(parseDashboardV1Json(bytes(nestedSchema(15))).schemaVersion, "tc.dashboard/v1");
  assertRejected(bytes(nestedSchema(16)), "DEPTH_EXCEEDED");
});

test("对象根和版本识别使用稳定原因", () => {
  for (const source of ["", "   ", "[]", "null", "true", "1", '"text"', '"unterminated', '"\\uD800"']) {
    assertRejected(bytes(source), "ROOT_MUST_BE_OBJECT", "$");
  }
  for (const source of ["x", "+1"]) {
    assertRejected(bytes(source), "INVALID_JSON", "$");
  }
  for (const source of ["{}", '{"schemaVersion":null}', '{"schemaVersion":1}']) {
    assertRejected(bytes(source), "VERSION_REQUIRED", "$.schemaVersion");
  }
  assertRejected(bytes('{"schemaVersion":"tc.dashboard/v2"}'), "VERSION_UNSUPPORTED", "$.schemaVersion");
});

test("解析树、数组和数字token均在运行时冻结", () => {
  const root = parseDashboardV1Json(bytes('{"schemaVersion":"tc.dashboard/v1","nested":{"values":[1]}}'));
  assert.equal(Object.isFrozen(root), true);
  assert.equal(Object.isFrozen(root.nested), true);
  assert.equal(Object.isFrozen(root.nested.values), true);
  assert.equal(Object.isFrozen(root.nested.values[0]), true);
  assert.throws(() => { root.nested.values.push(2); }, TypeError);
});

test("解析前复制输入字节并且结果不持有调用方缓冲区", () => {
  const source = bytes('{"schemaVersion":"tc.dashboard/v1","value":"stable"}');
  const root = parseDashboardV1Json(source);
  source.fill(0x20);
  assert.equal(root.value, "stable");
});
