import { expect, it } from 'vitest'
import {
  isOtaRollbackPreflight,
  rollbackPreflightDispositionLabel,
  rollbackPreflightReasonLabel,
  rollbackPreflightStale
} from '@/features/ota/rollback-preflight-model'
export const preflight = {
  queryId: '11111111-1111-4111-8111-111111111111',
  reportId: '22222222-2222-4222-8222-222222222222',
  observedDisposition: 'PREPARABLE',
  observedReason: 'ATOMIC_FENCE_STILL_REQUIRED',
  currentDisposition: 'INELIGIBLE',
  currentReason: 'QUERY_NOT_CURRENT_OR_FRESH',
  observedAt: '2026-10-06T20:00:00.123456789Z',
  queryExpiresAt: '2026-10-06T20:02:00Z',
  checkedAt: '2026-10-06T20:01:00Z',
  operationRevision: '1',
  sourceSlot: 'A',
  targetSlot: 'B',
  executionAuthorized: false,
  requiresAtomicCommitFence: true
} as const
it('精确公开14字段和false/true恒量，UTC纳秒及0/最大安全修订接受', () => {
  expect(isOtaRollbackPreflight(preflight)).toBe(true)
  expect(isOtaRollbackPreflight({ ...preflight, operationRevision: '0' })).toBe(true)
  expect(isOtaRollbackPreflight({ ...preflight, operationRevision: '9007199254740991' })).toBe(true)
})
it.each(Object.keys(preflight))('必填字段%s不能缺失', (field) => {
  const copy: any = { ...preflight }
  delete copy[field]
  expect(isOtaRollbackPreflight(copy)).toBe(false)
})
it.each([
  { queryId: null },
  { reportId: 'bad' },
  { observedDisposition: 'READY' },
  { currentDisposition: true },
  { observedReason: null },
  { currentReason: '<secret>' },
  { executionAuthorized: true },
  { requiresAtomicCommitFence: false },
  { executionAuthorized: 'false' },
  { requiresAtomicCommitFence: null },
  { observedAt: '2026-02-30T20:00:00Z' },
  { checkedAt: '2026-10-06T20:00:00+00:00' },
  { queryExpiresAt: 'invalid' },
  { checkedAt: '2026-10-06T19:00:00Z' },
  { operationRevision: 1 },
  { operationRevision: '01' },
  { operationRevision: '-1' },
  { operationRevision: '9007199254740992' },
  { sourceSlot: 'C' },
  { targetSlot: 'A' },
  { rawEvidence: {} },
  { leaseToken: 'secret' }
])('坏值或额外能力不进入公开模型 %j', (change) =>
  expect(isOtaRollbackPreflight({ ...preflight, ...change })).toBe(false)
)
it('三类分类独立，稳定新原因保留原码的展示口径', () => {
  expect(rollbackPreflightDispositionLabel('PREPARABLE')).toBe('可准备')
  expect(rollbackPreflightDispositionLabel('INELIGIBLE')).toBe('不可准备')
  expect(rollbackPreflightDispositionLabel('UNKNOWN')).toBe('未知')
  expect(rollbackPreflightReasonLabel('WRITER_STATE_UNKNOWN')).toContain('写入方')
  expect(rollbackPreflightReasonLabel('FUTURE_STABLE_REASON')).toContain('新的稳定原因')
  expect(isOtaRollbackPreflight({ ...preflight, currentReason: 'FUTURE_STABLE_REASON' })).toBe(true)
})
it('服务端时效失效和原截止分别提示，不把历史分类改写', () => {
  expect(rollbackPreflightStale(preflight, Date.parse(preflight.observedAt))).toBe(true)
  const available = {
    ...preflight,
    currentDisposition: 'PREPARABLE',
    currentReason: 'ATOMIC_FENCE_STILL_REQUIRED'
  } as const
  expect(rollbackPreflightStale(available, Date.parse(available.checkedAt))).toBe(false)
  expect(rollbackPreflightStale(available, Date.parse(available.queryExpiresAt))).toBe(true)
  expect(available.observedDisposition).toBe('PREPARABLE')
})
it('拒绝毫秒内纳秒逆序，不借Date.parse截断放行', () => {
  expect(
    isOtaRollbackPreflight({
      ...preflight,
      observedAt: '2026-10-06T20:00:00.123456789Z',
      checkedAt: '2026-10-06T20:00:00.123400000Z'
    })
  ).toBe(false)
})
it.each([
  ['2026-10-06T20:00:00.123Z', '2026-10-06T20:00:00.123000000Z'],
  ['2026-10-06T20:00:00.123000000Z', '2026-10-06T20:00:00.123Z'],
  ['2026-10-06T20:00:00.999999999Z', '2026-10-06T20:00:01Z']
])('不同小数精度同瞬间或跨秒顺序仍接受 %s → %s', (observedAt, checkedAt) => {
  expect(isOtaRollbackPreflight({ ...preflight, observedAt, checkedAt })).toBe(true)
})
