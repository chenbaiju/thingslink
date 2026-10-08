import { memoryStorage } from './commercialStorageFixture'
import { beforeEach, expect, it, vi } from 'vitest'
import {
  recordRecentResource,
  readRecentResources,
  clearRecentResources
} from '@/utils/workbench-recent'
const scope = { userId: 'u', tenantId: 't', projectId: 'p' }
beforeEach(() => {
  vi.stubGlobal('localStorage', memoryStorage())
  localStorage.clear()
  vi.useRealTimers()
})
it('isolates accounts and projects and bounds retained references', () => {
  for (let i = 0; i < 8; i++)
    recordRecentResource(scope, { kind: 'device', id: `d-${i}`, label: `设备 ${i}` })
  expect(readRecentResources(scope)).toHaveLength(6)
  expect(readRecentResources({ ...scope, userId: 'other' })).toEqual([])
  expect(readRecentResources({ ...scope, projectId: 'other' })).toEqual([])
  recordRecentResource(scope, { kind: 'device', id: 'd-4', label: '更新名称' })
  expect(readRecentResources(scope).filter((row) => row.id === 'd-4')).toHaveLength(1)
  expect(readRecentResources(scope)[0].label).toBe('更新名称')
})
it('expires records and tolerates malformed storage', () => {
  vi.useFakeTimers()
  vi.setSystemTime(1000000)
  recordRecentResource(scope, { kind: 'type', id: 'type', label: '模型' })
  vi.advanceTimersByTime(7 * 86400000)
  expect(readRecentResources(scope)).toEqual([])
  localStorage.setItem(
    'thingslink-recent-v1:u:t:p',
    '[{"kind":"secret","id":"x","label":"x","visitedAt":1}]'
  )
  expect(readRecentResources(scope)).toEqual([])
  localStorage.setItem('thingslink-recent-v1:u:t:p', '{')
  expect(readRecentResources(scope)).toEqual([])
  vi.useRealTimers()
})
it('clears references without removing unrelated preferences', () => {
  recordRecentResource(scope, { kind: 'type', id: 'a', label: 'a' })
  localStorage.setItem('theme', 'dark')
  clearRecentResources()
  expect(readRecentResources(scope)).toEqual([])
  expect(localStorage.getItem('theme')).toBe('dark')
})
