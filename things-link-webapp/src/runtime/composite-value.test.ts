import { describe, expect, it } from 'vitest';
import { parseDashboardRuntimeResponse } from '@things-link/client-contracts/dashboard/v1';
import { isRuntimeNumber, parseCompositeValue, serializeRuntimeValue } from './composite-value';
const input = (source: string) => parseDashboardRuntimeResponse(new TextEncoder().encode(`{"value":${source}}`)).value;
const parse = (source: string, type: 'OBJECT' | 'LIST' = 'OBJECT') => parseCompositeValue(input(source), type);
describe('composite property global profile', () => {
  it('keeps nested exact numbers and serializes only safe JSON text', () => {
    const value = parse('{"number":9007199254740993.123456789,"text":"<script>\\\"\\n","nested":{"n":1e-1000}}');
    expect(serializeRuntimeValue(value)).toBe('{"nested":{"n":1e-1000},"number":9007199254740993.123456789,"text":"<script>\\\"\\n"}');
    expect(Object.isFrozen(value)).toBe(true);
  });
  it('does not mistake ordinary kind/lexical business fields for trusted numeric tokens', () => {
    const value = parse('{"kind":"NUMBER","lexical":"9007199254740993"}');
    expect(isRuntimeNumber(value)).toBe(false);
    expect(serializeRuntimeValue(value)).toBe('{"kind":"NUMBER","lexical":"9007199254740993"}');
    const proto = parse('{"__proto__":{"safe":true}}');
    expect(Object.getPrototypeOf(proto)).toBe(null);
    expect(serializeRuntimeValue(proto)).toBe('{"__proto__":{"safe":true}}');
  });
  it('counts scalar leaves in the eight-level depth bound', () => {
    expect(() => parse(`${'['.repeat(7)}1${']'.repeat(7)}`, 'LIST')).not.toThrow();
    expect(() => parse(`${'['.repeat(8)}1${']'.repeat(8)}`, 'LIST')).toThrow();
    expect(() => parse(`${'['.repeat(8)}${']'.repeat(8)}`, 'LIST')).not.toThrow();
  });
  it('enforces 64 object members and 256 array items exactly', () => {
    const object = (n: number) => JSON.stringify(Object.fromEntries(Array.from({ length: n }, (_, i) => [`key_${i}`, true])));
    expect(() => parse(object(64))).not.toThrow(); expect(() => parse(object(65))).toThrow();
    expect(() => parse(JSON.stringify(Array(256).fill(false)), 'LIST')).not.toThrow();
    expect(() => parse(JSON.stringify(Array(257).fill(false)), 'LIST')).toThrow();
  });
  it('counts Unicode code points instead of UTF16 code units and enforces compact UTF8 bytes separately', () => {
    expect(() => parse(JSON.stringify({ text: 'é'.repeat(4096) }))).not.toThrow();
    expect(() => parse(JSON.stringify({ text: 'é'.repeat(4097) }))).toThrow();
    expect(() => parse(JSON.stringify({ text: '😀'.repeat(4096) }))).toThrow(); // 码点合法但16KiB信封超限。
    const escaped = '{"text":"\\u00e9\\ud83d\\ude00\\u2028"}';
    expect(serializeRuntimeValue(parse(escaped))).toBe(JSON.stringify({ text: 'é😀\u2028' }));
  });
  it('accepts 16384 compact bytes and rejects one additional byte before building a full copy', () => {
    const array = ['x'.repeat(4096), 'x'.repeat(4096), 'x'.repeat(4096), 'x'.repeat(4083)];
    const json = JSON.stringify(array); expect(new TextEncoder().encode(json).length).toBe(16384);
    expect(() => parse(json, 'LIST')).not.toThrow();
    array[3] += 'x'; expect(() => parse(JSON.stringify(array), 'LIST')).toThrow();
  });
  it('rejects null, wrong roots, invalid keys and mixed JSON types while allowing optional object member differences', () => {
    for (const source of ['null', 'true', '1', '[]', '{"bad.key":1}', '{"x":null}']) expect(() => parse(source)).toThrow();
    expect(() => parse('[1,"1"]', 'LIST')).toThrow();
    expect(() => parse('[{"a":true},{"b":false}]', 'LIST')).not.toThrow();
    expect(() => parse('[]', 'LIST')).not.toThrow(); expect(() => parse('{}')).not.toThrow();
  });
  it('does not accept ordinary JavaScript numbers or nonfinite values as parser-proven numeric input', () => {
    expect(() => parseCompositeValue({ n: 1 }, 'OBJECT')).toThrow();
    expect(() => parseCompositeValue({ n: Infinity }, 'OBJECT')).toThrow();
    expect(() => parse('{"n":1e1000}')).toThrow();
  });
});
