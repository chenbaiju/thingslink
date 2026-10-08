const encoder = new TextEncoder()
/** 与服务端严格子集一致的原文解析，错误只返回固定分类，不附输入或下层异常。 */
export function parseStrictPublicJson(bytes: Uint8Array): unknown {
  const invalid = () => new Error('公开JSON材料不符合严格格式或预算')
  if (bytes.byteLength > 65_536) throw invalid()
  let raw: string
  try {
    raw = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes)
  } catch {
    throw invalid()
  }
  let index = 0,
    nodes = 0
  function node() {
    if (++nodes > 4096) throw invalid()
  }
  function space() {
    while (' \t\n\r'.includes(raw[index] ?? '\u0000')) index++
  }
  function take(token: string) {
    space()
    if (raw[index++] !== token) throw invalid()
  }
  function string(): string {
    space()
    const start = index
    if (raw[index++] !== '"') throw invalid()
    while (index < raw.length) {
      const character = raw[index++]
      if (character === '\\') {
        index++
        continue
      }
      if (character !== '"') continue
      let value: string
      try {
        value = JSON.parse(raw.slice(start, index))
      } catch {
        throw invalid()
      }
      if (value.length > 16_384 || encoder.encode(value).length > 16_384) throw invalid()
      for (let i = 0; i < value.length; i++) {
        const code = value.charCodeAt(i)
        if (code >= 0xd800 && code <= 0xdbff) {
          const low = value.charCodeAt(++i)
          if (!(low >= 0xdc00 && low <= 0xdfff)) throw invalid()
        } else if (code >= 0xdc00 && code <= 0xdfff) throw invalid()
      }
      return value
    }
    throw invalid()
  }
  function value(depth: number): unknown {
    space()
    node()
    const first = raw[index]
    if (first === '{' || first === '[') {
      if (depth > 32) throw invalid()
      index++
      if (first === '{') {
        const result: Record<string, unknown> = Object.create(null)
        space()
        if (raw[index] === '}') {
          index++
          return result
        }
        while (true) {
          node()
          const key = string()
          if (Object.hasOwn(result, key)) throw invalid()
          take(':')
          result[key] = value(depth + 1)
          space()
          if (raw[index] === '}') {
            index++
            return result
          }
          take(',')
        }
      }
      const result: unknown[] = []
      space()
      if (raw[index] === ']') {
        index++
        return result
      }
      while (true) {
        result.push(value(depth + 1))
        space()
        if (raw[index] === ']') {
          index++
          return result
        }
        take(',')
      }
    }
    if (first === '"') return string()
    for (const [literal, parsed] of [
      ['true', true],
      ['false', false]
    ] as const) {
      if (raw.startsWith(literal, index)) {
        index += literal.length
        return parsed
      }
    }
    const token = raw.slice(index).match(/^[^\s,\]}]+/)?.[0]
    if (!token || !/^(0|[1-9][0-9]{0,15})$/.test(token) || !Number.isSafeInteger(Number(token)))
      throw invalid()
    index += token.length
    return Number(token)
  }
  const result = value(1)
  space()
  if (index !== raw.length || !result || typeof result !== 'object' || Array.isArray(result))
    throw invalid()
  return result
}

/** 调用方先通过闭集公开模型；仅安全整数、合法字符串及数组组成的根签包JCS子集。 */
export function canonicalPublicJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonicalPublicJson).join(',')}]`
  if (value && typeof value === 'object')
    return `{${Object.keys(value)
      .sort()
      .map(
        (key) =>
          `${JSON.stringify(key)}:${canonicalPublicJson((value as Record<string, unknown>)[key])}`
      )
      .join(',')}}`
  return JSON.stringify(value)
}
