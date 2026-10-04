import { memoryStorage } from './commercialStorageFixture'
import { beforeEach, expect, it, vi } from 'vitest'
import { commercialPendingStorage } from '@/features/commercial/pending-storage'
import type { PendingAdjustment } from '@/features/commercial/adjustment-model'
beforeEach(() => {
  vi.stubGlobal('localStorage', memoryStorage())
  localStorage.clear()
})
const pending = (key: string): PendingAdjustment => ({
  tenantId: 'tenant',
  request: {
    idempotencyKey: key,
    dimensionCode: 'DEVICES_MAX',
    amount: '1',
    expectedAssignmentVersion: '1',
    startsAt: 'a',
    endsAt: 'b',
    reason: 'r'
  }
})
it('账号隔离，重新创建存储对象可恢复，不覆盖其他未决申请', () => {
  const first = commercialPendingStorage(localStorage, 'one')
  first.save(pending('a'))
  expect(commercialPendingStorage(localStorage, 'one').first()).toEqual(pending('a'))
  expect(commercialPendingStorage(localStorage, 'two').first()).toBeNull()
  expect(() => first.save(pending('b'))).toThrow('尚未确认')
  expect(first.first()).toEqual(pending('a'))
  first.remove('a')
  expect(first.first()).toBeNull()
})
it('多标签已写入的独立申请逐个回收，不能清掉其他申请', () => {
  localStorage.setItem('tc-commercial-pending:one:a', JSON.stringify(pending('a')))
  localStorage.setItem('tc-commercial-pending:one:b', JSON.stringify(pending('b')))
  const store = commercialPendingStorage(localStorage, 'one')
  store.remove('a')
  expect(store.first()).toEqual(pending('b'))
})
