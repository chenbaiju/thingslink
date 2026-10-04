import { describe, expect, it } from 'vitest'
import { isMultiSeries, isSeriesEmpty } from '@/components/core/charts/art-line-chart/series-shape'

/** A6-06：`typeof null === 'object'` 会把首个缺失点误判为多序列，必须显式排除 null。 */
describe('折线图序列形状与空判定（A6-06 null 陷阱）', () => {
  it('[null] 为单序列且视为空', () => {
    expect(isMultiSeries([null])).toBe(false)
    expect(isSeriesEmpty([null])).toBe(true)
  })

  it('[null, 1] 为单序列且非空（缺失点是断点而非空）', () => {
    expect(isMultiSeries([null, 1])).toBe(false)
    expect(isSeriesEmpty([null, 1])).toBe(false)
  })

  it('[0, null] 为单序列且有数据（真实读数 0 与缺值共存）', () => {
    expect(isMultiSeries([0, null])).toBe(false)
    expect(isSeriesEmpty([0, null])).toBe(false)
  })

  it('[0] 为单序列且有数据（单一真实读数 0）', () => {
    expect(isMultiSeries([0])).toBe(false)
    expect(isSeriesEmpty([0])).toBe(false)
  })

  it('[0, 0] 为单序列且有数据（真实读数 0 不是「无数据」）', () => {
    expect(isMultiSeries([0, 0])).toBe(false)
    expect(isSeriesEmpty([0, 0])).toBe(false)
  })

  it('[0, 1] 为单序列且非空', () => {
    expect(isMultiSeries([0, 1])).toBe(false)
    expect(isSeriesEmpty([0, 1])).toBe(false)
  })

  it('真正的多序列被识别，并按内部 data 判空', () => {
    const multi = [
      { name: 'a', data: [1, 2] },
      { name: 'b', data: [3, 4] }
    ]
    expect(isMultiSeries(multi)).toBe(true)
    expect(isSeriesEmpty(multi)).toBe(false)
  })

  it('多序列含 0 读数视为有数据', () => {
    expect(isSeriesEmpty([{ name: 'a', data: [0, 0] }])).toBe(false)
  })

  it('多序列全空 data 视为空', () => {
    expect(isSeriesEmpty([{ name: 'a', data: [] }])).toBe(true)
  })

  it('空数组视为空', () => {
    expect(isSeriesEmpty([])).toBe(true)
  })
})
