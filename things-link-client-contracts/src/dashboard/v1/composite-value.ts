import { isStrictJsonNumber, type StrictJsonValue } from "./strict-json.js";
/** 数字词法只来自严格解析器；不可用用户对象中的同名字段识别数值。 */
export interface RuntimeNumber { readonly kind: 'NUMBER'; readonly lexical: string }
export type RuntimeValue = RuntimeNumber | string | boolean | readonly RuntimeValue[] | { readonly [key: string]: RuntimeValue };
const numbers = new WeakSet<object>();
export class CompositeValueError extends Error {
  constructor() { super('Composite value violates runtime profile'); }
}
function requireThat(value: unknown): asserts value { if (!value) throw new CompositeValueError(); }
export function isRuntimeNumber(value: unknown): value is RuntimeNumber {
  return value !== null && typeof value === 'object' && numbers.has(value);
}
/** 供标量和复合值共用；普通{type,lexical}对象不能获得可信数字身份。 */
export function createRuntimeNumber(value: unknown): RuntimeNumber {
  requireThat(isStrictJsonNumber(value as StrictJsonValue));
  const lexical = (value as { lexical: string }).lexical;
  requireThat(Number.isFinite(Number(lexical)));
  const number: RuntimeNumber = Object.freeze({ kind: 'NUMBER', lexical });
  numbers.add(number); return number;
}
function stringLength(value: string): number {
  let count = 0;
  for (const character of value) {
    const code = character.codePointAt(0)!;
    requireThat(code !== 0 && !(code >= 0xd800 && code <= 0xdfff));
    count++;
  }
  return count;
}
/** 安全JSON文本只用于文本节点及预算；数组顺序保留、数字不经IEEE754重序列化。 */
export function serializeRuntimeValue(value: RuntimeValue): string {
  if (isRuntimeNumber(value)) return value.lexical;
  if (typeof value === 'string') return JSON.stringify(value);
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  requireThat(value !== null && typeof value === 'object');
  if (Array.isArray(value)) return `[${value.map(serializeRuntimeValue).join(',')}]`;
  // 键排序不改变字节数，但使本地文本稳定；不宣称这是PG摘要的规范输入。
  const object = value as { readonly [key: string]: RuntimeValue };
  return `{${Object.keys(object).sort().map(key => `${JSON.stringify(key)}:${serializeRuntimeValue(object[key]!)}`).join(',')}}`;
}
/** 仅证明复合Profile硬上限，嵌套Schema/required/enum由服务端VALUE资格保证。 */
export function parseCompositeValue(value: unknown, expected: 'OBJECT' | 'LIST'): RuntimeValue {
  requireThat(value !== null && typeof value === 'object' && !isStrictJsonNumber(value as StrictJsonValue)
    && (expected === 'LIST' ? Array.isArray(value) : !Array.isArray(value)));
  let bytes = 0;
  const charge = (size: number): void => { bytes += size; requireThat(bytes <= 16 * 1024); };
  const textBytes = (text: string): number => new TextEncoder().encode(JSON.stringify(text)).byteLength;
  const kind = (node: unknown): string => isStrictJsonNumber(node as StrictJsonValue) ? 'number'
    : Array.isArray(node) ? 'array' : node === null ? 'null' : typeof node;
  const visit = (node: unknown, depth: number): RuntimeValue => {
    // 后端从根=1计数，scalar叶同样占一层，不能只计算容器。
    requireThat(depth <= 8 && node !== null);
    if (isStrictJsonNumber(node as StrictJsonValue)) {
      charge((node as { lexical: string }).lexical.length); return createRuntimeNumber(node);
    }
    if (typeof node === 'string') { requireThat(stringLength(node) <= 4096); charge(textBytes(node)); return node; }
    if (typeof node === 'boolean') { charge(node ? 4 : 5); return node; }
    requireThat(typeof node === 'object');
    if (Array.isArray(node)) {
      requireThat(node.length <= 256 && Object.keys(node).length === node.length
        && Array.from({ length: node.length }, (_, index) => Object.hasOwn(node, index)).every(Boolean));
      requireThat(node.every(child => kind(child) === kind(node[0])));
      charge(2 + Math.max(0, node.length - 1));
      return Object.freeze(node.map(child => visit(child, depth + 1)));
    }
    requireThat(Object.getPrototypeOf(node) === null || Object.getPrototypeOf(node) === Object.prototype);
    const keys = Object.keys(node as object); requireThat(keys.length <= 64);
    charge(2 + Math.max(0, keys.length - 1));
    const result: Record<string, RuntimeValue> = Object.create(null);
    for (const key of keys) {
      requireThat(/^[A-Za-z0-9_-]{1,64}$/.test(key));
      charge(textBytes(key) + 1); result[key] = visit((node as Record<string, unknown>)[key], depth + 1);
    }
    return Object.freeze(result);
  };
  // 逐节点累计wire词法紧凑UTF-8字节，超限即停，不先复制完整超限树。
  // 与Java canonicalize/实际HTTP对齐由跨语言黄金样本证明。
  return visit(value, 1);
}
