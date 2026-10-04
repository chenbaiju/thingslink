import assert from 'node:assert/strict';
import test from 'node:test';
import { parseDashboardSchemaEnvelope, validateDashboardSchemaV1 } from '../dist/dashboard/v1/index.js';
const bytes = (text) => new TextEncoder().encode(text);
const schema = '{ "schemaVersion":"tc.dashboard/v1", "presentation":{"mode":"RESPONSIVE_GRID"},"pages":[{"id":"main","title":"首页","components":[]}] }';
test('schema envelope preserves source whitespace, Unicode escapes and numeric lexemes', () => {
  const source = schema.replace('首页', '\\u9996页');
  const result = parseDashboardSchemaEnvelope(bytes(`{"schema":${source},"revision":"9007199254740993"}`));
  assert.equal(new TextDecoder().decode(result.schemaSource), source);
  assert.equal(result.envelope.revision, '9007199254740993');
  assert.equal(validateDashboardSchemaV1(result.schemaSource).schema.pages[0].title, '首页');
});
test('schema envelope rejects duplicate fields, invalid UTF8, BOM, trailing and missing root', () => {
  for (const source of [
    `{"schema":${schema},"schema":${schema}}`,
    `{"schema":${schema},"x":1,"\\u0078":2}`,
    `{"schema":${schema.replace('"id":"main"', '"id":"main","id":"other"')}}`,
    `{"schema":${schema}} true`, '{"schema":[]}', '{}',
  ]) assert.throws(() => parseDashboardSchemaEnvelope(bytes(source)));
  assert.throws(() => parseDashboardSchemaEnvelope(new Uint8Array([0xef, 0xbb, 0xbf, ...bytes(`{"schema":${schema}}`)])));
  assert.throws(() => parseDashboardSchemaEnvelope(new Uint8Array([0xff])));
});
test('schema envelope keeps distinct raw schema and complete envelope byte budgets', () => {
  assert.throws(() => parseDashboardSchemaEnvelope(bytes(`{"schema":{${' '.repeat(512000)}}}`)));
  assert.throws(() => parseDashboardSchemaEnvelope(bytes(`{"schema":${schema},"extra":"${'x'.repeat(768 * 1024)}"}`)));
  const result = parseDashboardSchemaEnvelope(bytes(`{"schema":${schema},"extra":"${'x'.repeat(520000)}"}`));
  assert.equal(new TextDecoder().decode(result.schemaSource), schema);
});
test('schema envelope does not subtract its own wrapper from the existing schema depth allowance', () => {
  const nested = (n) => `{"schemaVersion":"tc.dashboard/v1","x":${'['.repeat(n)}0${']'.repeat(n)}}`;
  assert.doesNotThrow(() => parseDashboardSchemaEnvelope(bytes(`{"schema":${nested(15)}}`)));
  assert.throws(() => parseDashboardSchemaEnvelope(bytes(`{"schema":${nested(16)}}`)));
  assert.throws(() => parseDashboardSchemaEnvelope(bytes(`{"schema":${schema},"extra":${'['.repeat(16)}0${']'.repeat(16)}}`)));
});
