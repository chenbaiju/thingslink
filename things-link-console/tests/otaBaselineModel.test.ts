import { describe, expect, it } from 'vitest'
import {
  emptyOtaBaselineVersionHistory,
  mergeOtaBaselineVersionPage
} from '@/features/ota/baseline-model'

describe('OTA类型基线历史分页', () => {
  it('续页追加、末页清游标，切换设备类型时首页替换', () => {
    type Version = { baselineVersion: number }
    expect(emptyOtaBaselineVersionHistory<Version>()).toEqual({
      items: [],
      cursor: '',
      hasMore: false
    })

    const v3 = { baselineVersion: 3 }
    const v2 = { baselineVersion: 2 }
    const first = mergeOtaBaselineVersionPage(
      emptyOtaBaselineVersionHistory<Version>(),
      { items: [v3], nextCursor: 'c1', hasMore: true },
      false
    )
    expect(first).toEqual({ items: [v3], cursor: 'c1', hasMore: true })

    const second = mergeOtaBaselineVersionPage(
      first,
      { items: [v2], nextCursor: 'c2', hasMore: true },
      true
    )
    expect(second.items.map((version) => version.baselineVersion)).toEqual([3, 2])
    expect(second.cursor).toBe('c2')

    // 末页必须清空游标，避免用一个已到末页的游标继续请求。
    const last = mergeOtaBaselineVersionPage(
      second,
      { items: [], nextCursor: 'stale', hasMore: false },
      true
    )
    expect(last).toEqual({ items: [v3, v2], cursor: '', hasMore: false })

    // 首页走替换而不是追加：切换设备类型后不能混入上一类型的版本历史。
    expect(
      mergeOtaBaselineVersionPage(second, { items: [v2], hasMore: false }, false).items
    ).toEqual([v2])
  })

  it('缺失items与缺省hasMore按空末页处理，不产生半截状态', () => {
    const page = mergeOtaBaselineVersionPage(emptyOtaBaselineVersionHistory<unknown>(), {}, true)
    expect(page).toEqual({ items: [], cursor: '', hasMore: false })
  })
})
