import assert from "node:assert/strict";
import test from "node:test";
import { parseDashboardV1Json } from "../dist/dashboard/v1/strict-json.js";
import {
  requireCanonicalUuid,
  requireConfigNumber,
  requireJsonInteger,
  requireLocalKey,
  requirePropertyKey,
  requireSemVer,
  requireSha256,
  requireShortText,
  requireTextContent,
  requireTitle,
} from "../dist/dashboard/v1/value-rules.js";
import { DashboardContractViolation } from "../dist/dashboard/v1/violation.js";

const encoder = new TextEncoder();

function numberToken(lexical) {
  return parseDashboardV1Json(encoder.encode(`{"schemaVersion":"tc.dashboard/v1","number":${lexical}}`)).number;
}

function assertRuleRejected(operation, reason, path) {
  assert.throws(
    operation,
    (error) => error instanceof DashboardContractViolation && error.reason === reason && error.path === path,
  );
}

test("LocalKey与PropertyKey执行各自ASCII闭集和精确长度", () => {
  assert.equal(requireLocalKey(`a_${"1".repeat(62)}`, "$.key").length, 64);
  assert.equal(requirePropertyKey("Temperature-1", "$.propertyKey"), "Temperature-1");
  for (const invalid of ["Upper", "a".repeat(65), "a\n"]) {
    assertRuleRejected(() => requireLocalKey(invalid, "$.key"), "INVALID_VALUE", "$.key");
  }
  assertRuleRejected(() => requirePropertyKey("payload.temperature", "$.propertyKey"), "INVALID_VALUE", "$.propertyKey");
  assertRuleRejected(() => requireLocalKey(numberToken("1"), "$.key"), "TYPE_MISMATCH", "$.key");
});

test("UUID、SHA-256与SemVer只接受规范文本", () => {
  assert.equal(requireCanonicalUuid("123e4567-e89b-12d3-a456-426614174000", "$.id"), "123e4567-e89b-12d3-a456-426614174000");
  assert.equal(requireSha256("a".repeat(64), "$.digest").length, 64);
  assert.equal(requireSemVer("65535.0.1", "$.version"), "65535.0.1");
  for (const invalid of ["1.0", "01.0.0", "1.0.0-alpha", "65536.0.0", "1.0.0\n"]) {
    assertRuleRejected(() => requireSemVer(invalid, "$.version"), "INVALID_VALUE", "$.version");
  }
  assertRuleRejected(() => requireCanonicalUuid("123E4567-e89b-12d3-a456-426614174000", "$.id"), "INVALID_VALUE", "$.id");
  assertRuleRejected(() => requireCanonicalUuid("123e4567-e89b-12d3-a456-426614174000\n", "$.id"), "INVALID_VALUE", "$.id");
  assertRuleRejected(() => requireSha256("A".repeat(64), "$.digest"), "INVALID_VALUE", "$.digest");
  assertRuleRejected(() => requireSha256(`${"a".repeat(64)}\n`, "$.digest"), "INVALID_VALUE", "$.digest");
  assertRuleRejected(() => requirePropertyKey("Temperature-1\n", "$.propertyKey"), "INVALID_VALUE", "$.propertyKey");
});

test("Title精确复刻JDK21空白并按Unicode码点计数", () => {
  assert.equal(Array.from(requireTitle("😀".repeat(80), "$.title")).length, 80);
  for (const invalid of ["😀".repeat(81), " \t", "标题\u0085", "\u2000"]) {
    assertRuleRejected(() => requireTitle(invalid, "$.title"), "INVALID_VALUE", "$.title");
  }
  for (const allowed of ["\u00a0", "\u2007", "\u202f", "�"]) {
    assert.equal(requireTitle(allowed, "$.title"), allowed);
  }
});

test("ShortText与TextContent执行不同控制字符白名单", () => {
  assert.equal(requireShortText("", "$.alt"), "");
  assert.equal(Array.from(requireShortText("文".repeat(256), "$.alt")).length, 256);
  assertRuleRejected(() => requireShortText("文".repeat(257), "$.alt"), "INVALID_VALUE", "$.alt");
  assertRuleRejected(() => requireShortText("a\tb", "$.alt"), "INVALID_VALUE", "$.alt");
  assert.equal(requireTextContent("a\tb\nc", "$.content"), "a\tb\nc");
  assert.equal(Array.from(requireTextContent("😀".repeat(4096), "$.content")).length, 4096);
  assertRuleRejected(() => requireTextContent("x\ry", "$.content"), "INVALID_VALUE", "$.content");
  assertRuleRejected(() => requireTextContent("a".repeat(4097), "$.content"), "INVALID_VALUE", "$.content");
});

test("ConfigNumber使用原始十进制词法执行范围和有效位", () => {
  for (const lexical of [
    "0", "-0", "1e-12", "-1e-12", "1e12", "-1000000000000", "1.23000000000000",
    "1.2345678901234500", "1000000000000000e-3",
  ]) {
    assert.equal(requireConfigNumber(numberToken(lexical), "$.number").lexical, lexical);
  }
  for (const lexical of ["0.0000000000001", "1000000000001", "1.234567890123456", "1.0000000000000001", "1000000000000.00001", "0.00000000000099999999999999999"]) {
    assertRuleRejected(() => requireConfigNumber(numberToken(lexical), "$.number"), "INVALID_NUMBER", "$.number");
  }
  assert.equal(Number("1.0000000000000001"), 1, "反例必须证明IEEE-754会丢失合同所需精度");
  assert.equal(Number("1000000000000.00001"), 1_000_000_000_000, "反例必须证明边界外小数会被舍入回边界");
});

test("整数规则保留1、1.0与1e0的词法区别", () => {
  assert.equal(requireJsonInteger(numberToken("1"), "$.count", 0n, 2n), 1n);
  assertRuleRejected(() => requireJsonInteger(numberToken("1.0"), "$.count"), "TYPE_MISMATCH", "$.count");
  assertRuleRejected(() => requireJsonInteger(numberToken("1e0"), "$.count"), "TYPE_MISMATCH", "$.count");
  assertRuleRejected(() => requireJsonInteger(numberToken("3"), "$.count", 0n, 2n), "INVALID_VALUE", "$.count");
  assert.equal(requireJsonInteger(numberToken("123456789012345678901234567890"), "$.count"), 123456789012345678901234567890n);
});

test("普通JSON对象不能伪造解析器内部数字token", () => {
  const forged = parseDashboardV1Json(encoder.encode(
    '{"schemaVersion":"tc.dashboard/v1","number":{"type":"JSON_NUMBER","lexical":"1"}}',
  )).number;
  assertRuleRejected(() => requireConfigNumber(forged, "$.number"), "TYPE_MISMATCH", "$.number");
  assertRuleRejected(() => requireJsonInteger(forged, "$.number"), "TYPE_MISMATCH", "$.number");
});

test("未知或超长属性名不会被拒绝错误路径回显", () => {
  const secret = "sensitive_" + "x".repeat(200);
  assert.throws(
    () => parseDashboardV1Json(encoder.encode(`{"schemaVersion":"tc.dashboard/v1","${secret}":1,"${secret}":2}`)),
    (error) => error instanceof DashboardContractViolation
      && error.reason === "DUPLICATE_KEY"
      && error.path === '$["<unknown>"]'
      && !error.message.includes(secret),
  );
});
