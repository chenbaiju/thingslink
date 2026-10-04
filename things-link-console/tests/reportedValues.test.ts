import { describe, expect, it } from 'vitest'
import {
  ReportedValues,
  reportedRevision,
  compareReportedRevision
} from '../src/features/device/reported-values'
const source = '11111111-1111-4111-8111-111111111111'
function fact(
  revision?: string,
  value: unknown = revision
): import('../src/features/device/reported-values').ReportedProjection {
  return {
    values: { temperature: value },
    occurredAt: { temperature: '2026-09-12T00:00:00Z' },
    reportedRevisions: revision ? { temperature: revision } : {},
    thingModelVersionIds: { temperature: source }
  }
}
describe('逐属性接受事实', () => {
  it.each(['0', '01', '-1', '1.0', '1e3', '9223372036854775808', '', 1, null])(
    '拒绝非规范序号 %s',
    (value) => expect(reportedRevision(value)).toBeUndefined()
  )
  it('精确比较安全整数以外的相邻 Long', () => {
    expect(compareReportedRevision('9007199254740993', '9007199254740992')).toBeGreaterThan(0)
    expect(reportedRevision('9223372036854775807')).toBe('9223372036854775807')
  })
  it('新 WS 之后迟到 REST 与同序号不同内容均不能回退', () => {
    const model = new ReportedValues()
    model.merge(fact('9007199254740993', '新'), ['temperature'], true)
    model.merge(fact('9007199254740992', '旧'), ['temperature'])
    model.merge(fact('9007199254740993', '重复变造'), ['temperature'], true)
    expect(model.values()).toEqual({ temperature: '新' })
    expect(model.entries()[0]![1].thingModelVersionId).toBe(source)
  })
  it('新 REST 后旧 WS 不覆盖且各属性独立', () => {
    const model = new ReportedValues()
    model.merge(fact('5', '新'), ['temperature'])
    model.merge(fact('4', '旧'), ['temperature'], true)
    model.merge({ values: { humidity: 25 }, reportedRevisions: { humidity: '1' } }, ['humidity'])
    expect(model.values()).toEqual({ temperature: '新', humidity: 25 })
    expect(model.entries()[1]![1].thingModelVersionId).toBeUndefined()
  })
  it('无序号 WS 只置 dirty，未知 REST 不覆盖已知', () => {
    const model = new ReportedValues()
    expect(model.merge(fact(undefined, 1), ['temperature'], true)).toBe(true)
    expect(model.values()).toEqual({})
    model.merge(fact(undefined, 2), ['temperature'])
    model.merge(fact('1', 3), ['temperature'])
    model.merge(fact(undefined, 4), ['temperature'])
    expect(model.values()).toEqual({ temperature: 3 })
    model.clear()
    expect(model.values()).toEqual({})
  })
  it('候选外键不进入视图，非法来源不写入', () => {
    const model = new ReportedValues()
    expect(
      model.merge({ ...fact('1'), thingModelVersionIds: { temperature: '假来源' } }, [
        'temperature'
      ])
    ).toBe(true)
    model.merge(fact('1'), ['humidity'])
    expect(model.values()).toEqual({})
  })
})

it('另一属性随模型升级不会改写尚未重新上报属性的真实来源', () => {
  const model = new ReportedValues()
  model.merge(fact('1', 1), ['temperature'])
  const nextSource = '22222222-2222-4222-8222-222222222222'
  model.merge(
    {
      values: { humidity: 2 },
      reportedRevisions: { humidity: '2' },
      thingModelVersionIds: { humidity: nextSource }
    },
    ['humidity']
  )
  expect(
    Object.fromEntries(model.entries().map(([key, value]) => [key, value.thingModelVersionId]))
  ).toEqual({ temperature: source, humidity: nextSource })
})
