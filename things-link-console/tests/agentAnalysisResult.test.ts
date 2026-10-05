import { expect, it } from 'vitest'
import { decodeAnalysisCall, decodeAnalysisRun } from '@/features/agent/analysis-result'

function call(status = 'SUCCEEDED') {
  return {
    id: '019c1234-5678-7890-8123-456789abcdef',
    status,
    createdAt: '2026-10-05T00:00:00.123456789Z',
    deadline: '2026-10-05T00:01:00.123456789Z',
    expiresAt: '2026-10-06T00:00:00.123456789Z',
    dispatchedAt: '2026-10-05T00:00:01Z',
    finishedAt: '2026-10-05T00:00:02Z'
  }
}
function result() {
  return {
    model: 'deepseek-flash',
    promptVersion: 'thingslink-agent-single-analysis-v1',
    summary: '<script>private-text</script>，不能当作指令执行',
    findings: ['FACT', 'HYPOTHESIS', 'RECOMMENDATION'].map((kind) => ({
      kind,
      statement: '<img src=x onerror=private-text>原文',
      evidenceIds: ['e-device', 'e-alarm', 'e-property-10']
    })),
    limitations: [' **原文** 不改写，仍需人工判断 '],
    usage: {
      promptTokens: 200,
      completionTokens: 10,
      totalTokens: 210,
      cacheHitTokens: 100,
      cacheMissTokens: 100
    }
  }
}
function succeeded() {
  return { call: call(), category: 'SUCCEEDED', result: result() }
}
function rejects(value: unknown) {
  try {
    decodeAnalysisRun(value)
    throw new Error('must reject')
  } catch (error) {
    expect(error).toBeInstanceOf(Error)
    expect((error as Error).message).toBe('INVALID_ANALYSIS_RUN')
    expect((error as Error).cause).toBeUndefined()
    expect(JSON.stringify(error)).not.toContain('private')
  }
}

it('首次成功保留文本与类别，复制嵌套对象且不从结构推导质量', () => {
  const source = succeeded()
  const value = decodeAnalysisRun(source)
  expect(value).toEqual(source)
  source.call.status = 'FAILED'
  source.result.summary = 'changed'
  source.result.findings[0].evidenceIds.push('foreign')
  source.result.findings[0].statement = 'changed'
  source.result.limitations.push('changed')
  source.result.usage.totalTokens = 0
  expect(value).toEqual(succeeded())
  expect(value.result?.summary).toContain('<script>')
})
it.each(['RESERVED', 'DISPATCHED', 'SUCCEEDED', 'FAILED', 'UNKNOWN'])(
  '元数据重放%s没有正文，不补造旧结果或用量',
  (status) => {
    const value = { call: call(status), category: 'REPLAY', result: null }
    expect(decodeAnalysisRun(value)).toEqual(value)
    expect(decodeAnalysisCall(value.call)).toEqual(value.call)
    rejects({ ...value, result: result() })
  }
)
it.each(['UNQUALIFIED', 'TRANSPORT_UNKNOWN'])('类别%s仅允许空正文', (category) => {
  const value = { call: call('UNKNOWN'), category, result: null }
  expect(decodeAnalysisRun(value)).toEqual(value)
  rejects({ ...value, result: result() })
  rejects({ ...value, result: undefined })
})
it('关闭类别必须同时无调用和正文，空数组/空白正文沿后端原合同', () => {
  expect(decodeAnalysisRun({ call: null, category: 'UNAVAILABLE', result: null })).toEqual({
    call: null,
    category: 'UNAVAILABLE',
    result: null
  })
  const source = succeeded()
  source.result.summary = ' '
  source.result.findings = []
  source.result.limitations = []
  expect(decodeAnalysisRun(source)).toEqual(source)
})
it.each([
  null,
  [],
  {},
  { ...succeeded(), category: 'NEW_SUCCESS' },
  { ...succeeded(), category: true },
  { ...succeeded(), call: null },
  { ...succeeded(), call: call('UNKNOWN') },
  { ...succeeded(), result: null },
  { ...succeeded(), apiKey: 'private-key' },
  { ...succeeded(), category: 'UNAVAILABLE', result: null },
  { call: null, category: 'UNAVAILABLE', result: result() },
  { ...succeeded(), call: { ...call(), extra: 'private-id' } },
  { ...succeeded(), call: { ...call(), finishedAt: 0 } }
])('不接受矛盾或越界回包 %#', rejects)

it.each([
  { model: 'other' },
  { promptVersion: 'future' },
  { summary: '' },
  { summary: 0 },
  { secret: 'private-key' },
  { usage: null },
  { limitations: [null] },
  { limitations: [''] },
  { findings: [null] },
  { findings: [{ kind: 'EXECUTE', statement: 'private', evidenceIds: ['e-device'] }] },
  { findings: [{ kind: 'FACT', statement: '', evidenceIds: ['e-device'] }] },
  { findings: [{ kind: 'FACT', statement: 'private', evidenceIds: [] }] },
  { findings: [{ kind: 'FACT', statement: 'private', evidenceIds: ['e-property-11'] }] },
  { findings: [{ kind: 'FACT', statement: 'private', evidenceIds: ['foreign'] }] },
  { findings: [{ kind: 'FACT', statement: 'private', evidenceIds: ['e-device'], html: 'private' }] }
])('结果闭集与引用编号拒绝未知结构 %#', (patch) => {
  rejects({ ...succeeded(), result: { ...result(), ...patch } })
})
it.each([
  { promptTokens: 0 },
  { promptTokens: 4097 },
  { promptTokens: '200' },
  { promptTokens: 200.5 },
  { completionTokens: 1025 },
  { completionTokens: -1 },
  { totalTokens: 0 },
  { totalTokens: Number.MAX_SAFE_INTEGER + 1 },
  { totalTokens: NaN },
  { cacheMissTokens: 99 },
  { cacheHitTokens: true },
  { cacheHitTokens: Infinity },
  { amount: 0 }
])('计数不强制转换或补零，严格验证预算与等式 %#', (patch) => {
  rejects({ ...succeeded(), result: { ...result(), usage: { ...result().usage, ...patch } } })
})
it('正文预算按UTF8字节计算，不按字符数或悄悄截断', () => {
  const source = succeeded()
  source.result.findings = []
  source.result.limitations = []
  const overhead = new TextEncoder().encode(
    JSON.stringify({ summary: '', findings: [], limitations: [] })
  ).byteLength
  source.result.summary = 'a'.repeat(16 * 1024 - overhead)
  expect(decodeAnalysisRun(source).result?.summary).toBe(source.result.summary)
  source.result.summary += 'a'
  rejects(source)
  source.result.summary = '中'.repeat(6000)
  rejects(source)
})
it('解析异常不保留原对象、错误原因或私有内容', () => {
  const source = succeeded()
  Object.defineProperty(source.result, 'summary', {
    get() {
      throw new Error('private-source-value')
    }
  })
  rejects(source)
})
