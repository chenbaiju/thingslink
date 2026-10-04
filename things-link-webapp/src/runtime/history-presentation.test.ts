import { describe, expect, it } from 'vitest'
import { historyGeometry, historyRange, historySegments, interactionStateLabel, type HistoryPoint } from './history-presentation'
function point(ts: string, modelVersion = 'v1', value = '1', thingModelVersionId: string | null = 'version-a'): HistoryPoint {
  return { ts, modelVersion, thingModelVersionId, value: { kind: 'NUMBER', lexical: value }, sampleCount: '9007199254740993' }
}

describe('历史事实纯呈现', () => {
  it('按完整版本pair分段，同timestamp不同版本不去重', () => {
    const points = [point('2026-09-01T00:00:00Z'), point('2026-09-01T00:00:00Z', 'v2', '2', 'version-b'), point('2026-09-01T00:01:00Z', 'LEGACY_UNVERSIONED', '3', null), point('2026-09-01T00:02:00Z')]
    expect(historySegments(points, 'RAW').map(segment => segment.length)).toEqual([1, 1, 1, 1])
    expect(historyGeometry(points)).toHaveLength(4)
  })
  it('按实际聚合粒度缺桶断线，不猜RAW采样周期、不补零', () => {
    const points = [point('2026-09-01T00:00:00Z'), point('2026-09-01T00:01:00Z'), point('2026-09-01T00:03:00Z')]
    expect(historySegments(points, 'ONE_MINUTE').map(segment => segment.length)).toEqual([2, 1])
    expect(historySegments(points, 'ONE_HOUR').map(segment => segment.length)).toEqual([3])
    expect(historySegments(points, 'RAW').map(segment => segment.length)).toEqual([3])
  })
  it('极端有限数归一化几何不产生Infinity/NaN，原词法不变', () => {
    const points = [point('2026-09-01T00:00:00.000000001Z', 'v1', '-1e308'), point('2026-09-01T00:00:00.000000002Z', 'v1', '1e308')]
    const geometry = historyGeometry(points)
    expect(geometry.every(entry => Number.isFinite(entry.x) && Number.isFinite(entry.y))).toBe(true)
    expect(geometry[0].x).toBe(geometry[1].x)
    expect(geometry).toHaveLength(2)
    expect(geometry.map(entry => entry.point.value.lexical)).toEqual(['-1e308', '1e308'])
  })
  it('非数值全序列失败与配置预算错误明确区分，不伪装空序列', () => {
    expect(interactionStateLabel('30058')).toContain('非数值')
    expect(interactionStateLabel('10001')).toContain('配置或查询预算')
    expect(interactionStateLabel('EMPTY')).not.toBe(interactionStateLabel('30058'))
  })
  it('范围来自真实点，保留IEEE754不能区分的词法极值且空序列不造范围', () => {
    expect(historyRange([])).toBeNull()
    const points = [point('2026-09-01T00:00:00Z', 'v1', '9007199254740993'), point('2026-09-01T00:01:00Z', 'v1', '9007199254740992')]
    expect(historyRange(points)).toEqual({ from: points[0].ts, to: points[1].ts, minimum: '9007199254740992', maximum: '9007199254740993' })
  })

})
