import { describe, expect, it } from 'vitest'
import {
  OTA_TRUST_KEY_STATE_LABELS,
  emptyOtaTrustDomainHistory,
  emptyOtaTrustKeyHistory,
  mergeOtaTrustDomainPage,
  mergeOtaTrustKeyPage,
  otaTrustKeyStateLabel,
  otaTrustKeyStateTag,
  otaTrustKeyValidity
} from '@/features/ota/trust-model'

describe('OTA信任域与密钥只读分页', () => {
  it('域集合续页追加、末页清游标，切换项目时首页替换', () => {
    type Domain = { trustDomain: string }
    expect(emptyOtaTrustDomainHistory<Domain>()).toEqual({ items: [], cursor: '', hasMore: false })

    const a = { trustDomain: 'a' }
    const b = { trustDomain: 'b' }
    const first = mergeOtaTrustDomainPage(
      emptyOtaTrustDomainHistory<Domain>(),
      { items: [a], nextCursor: 'c1', hasMore: true },
      false
    )
    expect(first).toEqual({ items: [a], cursor: 'c1', hasMore: true })

    const second = mergeOtaTrustDomainPage(
      first,
      { items: [b], nextCursor: 'c2', hasMore: true },
      true
    )
    expect(second.items.map((domain) => domain.trustDomain)).toEqual(['a', 'b'])

    // 末页必须清空游标，避免用一个已到末页的游标继续请求。
    const last = mergeOtaTrustDomainPage(
      second,
      { items: [], nextCursor: 'stale', hasMore: false },
      true
    )
    expect(last).toEqual({ items: [a, b], cursor: '', hasMore: false })

    // 切换项目走替换而不是追加：不能把上一个项目的域混进当前表。
    expect(mergeOtaTrustDomainPage(second, { items: [b], hasMore: false }, false).items).toEqual([
      b
    ])
  })

  it('密钥集合续页追加、末页清游标，包轮换后首页替换', () => {
    type Key = { keyVersion: string }
    expect(emptyOtaTrustKeyHistory<Key>()).toEqual({ items: [], cursor: '', hasMore: false })

    const k1 = { keyVersion: 'k1' }
    const k3 = { keyVersion: 'k3' }
    const first = mergeOtaTrustKeyPage(
      emptyOtaTrustKeyHistory<Key>(),
      { items: [k1], nextCursor: 'c1', hasMore: true },
      false
    )
    const second = mergeOtaTrustKeyPage(
      first,
      { items: [k3], nextCursor: 'c2', hasMore: true },
      true
    )
    expect(second.items.map((key) => key.keyVersion)).toEqual(['k1', 'k3'])

    // 服务端游标绑定包版本：轮换后必须替换，不能把两代键拼成一张表。
    expect(mergeOtaTrustKeyPage(second, { items: [k3], hasMore: false }, false)).toEqual({
      items: [k3],
      cursor: '',
      hasMore: false
    })
  })

  it('缺失items与缺省hasMore按空末页处理，不产生半截状态', () => {
    expect(mergeOtaTrustDomainPage(emptyOtaTrustDomainHistory<unknown>(), {}, true)).toEqual({
      items: [],
      cursor: '',
      hasMore: false
    })
    expect(mergeOtaTrustKeyPage(emptyOtaTrustKeyHistory<unknown>(), {}, true)).toEqual({
      items: [],
      cursor: '',
      hasMore: false
    })
  })

  it('密钥状态标签闭集覆盖四态且未知状态原样透传', () => {
    expect(Object.keys(OTA_TRUST_KEY_STATE_LABELS)).toEqual([
      'PREPARED',
      'ACTIVE',
      'VERIFY_ONLY',
      'REVOKED'
    ])
    expect(otaTrustKeyStateLabel('ACTIVE')).toBe('使用中')
    expect(otaTrustKeyStateLabel('FUTURE_STATE')).toBe('FUTURE_STATE')
    expect(otaTrustKeyStateLabel(null)).toBe('—')
    expect(otaTrustKeyStateTag('REVOKED')).toBe('danger')
    expect(otaTrustKeyStateTag('FUTURE_STATE')).toBe('info')
  })

  it('有效期窗口只做格式化，不推断当前资格', () => {
    expect(otaTrustKeyValidity(0, 253402300799)).toBe(
      '1970-01-01T00:00:00.000Z → 9999-12-31T23:59:59.000Z（不含终点）'
    )
    expect(otaTrustKeyValidity(undefined, 1)).toBe('—')
  })
})
