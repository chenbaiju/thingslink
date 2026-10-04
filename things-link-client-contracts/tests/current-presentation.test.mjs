import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  parseDashboardRuntimeResponse, createRuntimeNumber, isRuntimeNumber,
  parseCompositeValue, serializeRuntimeValue, runtimeChildren, runtimeContainer,
  initiallyExpanded, gaugePosition, localTablePage,
} from '../dist/dashboard/v1/index.js';
const input = source => parseDashboardRuntimeResponse(new TextEncoder().encode(`{"value":${source}}`)).value;
const number = source => createRuntimeNumber(input(source));

test('shared numeric brand cannot be forged by ordinary business objects', () => {
  assert.equal(isRuntimeNumber(number('9007199254740993')), true);
  assert.equal(isRuntimeNumber({ kind: 'NUMBER', lexical: '9007199254740993' }), false);
  assert.throws(() => createRuntimeNumber({ kind: 'NUMBER', lexical: '1' }));
  const value = parseCompositeValue(input('{"kind":"NUMBER","lexical":"9007199254740993"}'), 'OBJECT');
  assert.equal(serializeRuntimeValue(value), '{"kind":"NUMBER","lexical":"9007199254740993"}');
  assert.equal(runtimeContainer(value), true);
});
test('shared composite preserves exact lexical values and own text keys', () => {
  const value = parseCompositeValue(input('{"__proto__":{"n":9007199254740993.123456789},"text":"<script>"}'), 'OBJECT');
  assert.equal(Object.getPrototypeOf(value), null);
  assert.equal(serializeRuntimeValue(value), '{"__proto__":{"n":9007199254740993.123456789},"text":"<script>"}');
  assert.deepEqual(runtimeChildren(value).map(item => item.label), ['__proto__', 'text']);
  assert.deepEqual(runtimeChildren(number('1')), []);
  assert.equal(runtimeContainer(number('1')), false);
  assert.equal(initiallyExpanded(0, 1), true);
  assert.equal(initiallyExpanded(1, 1), false);
});
test('shared composite rejects oversize, null and heterogeneous list rather than truncating', () => {
  assert.throws(() => parseCompositeValue(input('[1,"2"]'), 'LIST'));
  assert.throws(() => parseCompositeValue(input('{"value":null}'), 'OBJECT'));
  assert.throws(() => parseCompositeValue(input(JSON.stringify(Array(257).fill(1))), 'LIST'));
  const values = ['x'.repeat(4096), 'x'.repeat(4096), 'x'.repeat(4096), 'x'.repeat(4083)];
  assert.equal(new TextEncoder().encode(JSON.stringify(values)).length, 16384);
  assert.doesNotThrow(() => parseCompositeValue(input(JSON.stringify(values)), 'LIST'));
  values[3] += 'x';
  assert.throws(() => parseCompositeValue(input(JSON.stringify(values)), 'LIST'));
});
test('shared gauge compares original decimals outside range while clipping only geometry', () => {
  const value = number('1.0000000000000000000001');
  assert.deepEqual(gaugePosition(value, 0, 1), { minimum: '0', maximum: '1', percent: 100, outOfRange: true });
  assert.equal(gaugePosition(value, null, 1), null);
  assert.equal(gaugePosition(value, 1, 1), null);
  assert.equal(gaugePosition(value, 0, 1e13), null);
  assert.equal(serializeRuntimeValue(value), '1.0000000000000000000001');
});
test('shared table paging preserves all rows with explicit local pages', () => {
  const rows = Array.from({ length: 256 }, (_, index) => index);
  assert.deepEqual(localTablePage(rows, 12, 20), { rows: rows.slice(240), index: 12, count: 13, total: 256 });
  assert.deepEqual(localTablePage(rows, 99, 20), localTablePage(rows, 12, 20));
  assert.deepEqual(localTablePage([], 0, 20), { rows: [], index: 0, count: 1, total: 0 });
});
