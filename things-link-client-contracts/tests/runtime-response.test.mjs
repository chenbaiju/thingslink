import assert from 'node:assert/strict';
import test from 'node:test';
import { parseDashboardRuntimeResponse, isStrictJsonNumber } from '../dist/dashboard/v1/index.js';
const bytes = text => new TextEncoder().encode(text);
test('runtime NUMBER preserves source precision and does not inherit configuration numeric limits', () => {
  for (const lexical of ['9007199254740993.123456789', '1e100', '1e-1000', `0.${'0'.repeat(100)}1`]) {
    const value = parseDashboardRuntimeResponse(bytes(`{"value":${lexical}}`)).value;
    assert.ok(isStrictJsonNumber(value)); assert.equal(value.lexical, lexical);
  }
  assert.equal(isStrictJsonNumber(parseDashboardRuntimeResponse(bytes('{"value":{"type":"JSON_NUMBER","lexical":"0"}}')).value), false);
});
test('runtime response rejects duplicates, nonfinite numbers, invalid UTF8/BOM and oversized body', () => {
  for (const source of ['{"value":1,"value":2}', '{"value":1e1000}', '{"value":01}', '{"value":true} []']) {
    assert.throws(() => parseDashboardRuntimeResponse(bytes(source)));
  }
  assert.throws(() => parseDashboardRuntimeResponse(new Uint8Array([0xff])));
  assert.throws(() => parseDashboardRuntimeResponse(new Uint8Array([0xef, 0xbb, 0xbf, 123, 125])));
  assert.throws(() => parseDashboardRuntimeResponse(new Uint8Array(4 * 1024 * 1024 + 1)));
});
