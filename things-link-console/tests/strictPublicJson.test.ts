import { expect, it } from 'vitest'
import { canonicalPublicJson, parseStrictPublicJson } from '@/features/ota/strict-public-json'
import { bytes } from './helpers/otaTrustPublicFixture'

it.each([
  '{"bundle":1,"bundle":2}',
  '{"bundle":1,"\\u0062undle":2}',
  '{"k":{"x":1,"\\u0078":2}}',
  '{"n":1.0}',
  '{"n":1e0}',
  '{"n":1E+0}',
  '{"n":-0}',
  '{"n":-1}',
  '{"n":01}',
  '{"n":9007199254740992}',
  '{"n":null}',
  '{"x":"\\ud800"}',
  '{"x":"\\udc00"}',
  '{"x":"\\ud800a"}',
  '{"x":"\n"}',
  '{"x":1,}',
  '{"x":[1,]}',
  '{"x":truefalse}',
  '{}{}',
  '[]',
  'true',
  '\uFEFF{}',
  '{"x":1\u00a0}',
  '{"x":1\u000b}'
])('原文拒绝重复字段、非法数值或非严格JSON %j', (raw) => {
  expect(() => parseStrictPublicJson(bytes(raw))).toThrow('公开JSON材料不符合严格格式或预算')
})
it('保留数组顺序、规范对象键且不允许原型字段修改解析对象', () => {
  const parsed = parseStrictPublicJson(
    bytes('{"z":[2,1],"a":"\\ud83d\\ude00","__proto__":{"safe":true}}')
  )
  expect(Object.getPrototypeOf(parsed)).toBe(null)
  expect(canonicalPublicJson(parsed)).toBe('{"__proto__":{"safe":true},"a":"😀","z":[2,1]}')
  expect(
    parseStrictPublicJson(bytes('{"n":9007199254740991,"zero":0,"yes":true,"no":false}'))
  ).toEqual({ n: 9007199254740991, zero: 0, yes: true, no: false })
})
it('UTF8解码、单字符串、整个正文、节点和嵌套均执行独立预算', () => {
  expect(() =>
    parseStrictPublicJson(new Uint8Array([123, 34, 120, 34, 58, 34, 0xc0, 0xaf, 34, 125]))
  ).toThrow()
  expect(() => parseStrictPublicJson(bytes({ x: '汉'.repeat(5462) }))).toThrow()
  expect(() => parseStrictPublicJson(bytes({ x: 'a'.repeat(16385) }))).toThrow()
  expect(() => parseStrictPublicJson(bytes('{}' + ' '.repeat(65535)))).toThrow()
  expect(parseStrictPublicJson(bytes('{}' + ' '.repeat(65534)))).toEqual({})
  expect(parseStrictPublicJson(bytes({ x: Array(4093).fill(0) }))).toHaveProperty('x')
  expect(() => parseStrictPublicJson(bytes({ x: Array(4094).fill(0) }))).toThrow()
  expect(
    parseStrictPublicJson(bytes('{"x":' + '['.repeat(31) + '0' + ']'.repeat(31) + '}'))
  ).toHaveProperty('x')
  expect(() =>
    parseStrictPublicJson(bytes('{"x":' + '['.repeat(32) + '0' + ']'.repeat(32) + '}'))
  ).toThrow()
})
it('失败不携带原文、解码异常或秘密样式内容', () => {
  try {
    parseStrictPublicJson(bytes('{"privateKey":"PRIVATE_SECRET", "privateKey":1}'))
  } catch (error) {
    expect(String(error)).not.toContain('PRIVATE_SECRET')
    expect((error as Error).cause).toBeUndefined()
  }
})
