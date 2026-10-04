import { describe, expect, it } from 'vitest'
import { toSeriesValue } from '@/utils/series'

/** A6-06：历史序列缺值不得被 0 补齐，否则「无数据」与「读数 0」不可区分。 */
describe('历史序列值规整（A6-06）', () => {
  it('缺值保持 null 而非 0', () => {
    expect(toSeriesValue(undefined)).toBeNull()
    expect(toSeriesValue(null)).toBeNull()
  })

  it('真实读数 0 必须原样保留', () => {
    expect(toSeriesValue(0)).toBe(0)
  })

  it('普通数值原样保留', () => {
    expect(toSeriesValue(23.5)).toBe(23.5)
  })
})
