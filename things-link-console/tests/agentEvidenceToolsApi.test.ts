import { expect, it, vi } from 'vitest'
const get = vi.hoisted(() => vi.fn())
vi.mock('@/utils/http', () => ({ default: { get } }))
import { readHistoryEvidence, readAlarmEvidence } from '@/api/assistant-tools'

it('single property history uses the closed query and abort signal', async () => {
  const signal = new AbortController().signal
  await readHistoryEvidence(
    'p/x',
    'd/y',
    'm',
    'zero',
    '2026-10-04T00:00:00Z',
    '2026-10-04T01:00:00Z',
    signal
  )
  const call = get.mock.calls[0][0]
  expect(call.url).toBe('/api/v1/projects/p%2Fx/assistant/devices/d%2Fy/history')
  expect([...call.params.keys()]).toEqual(['expectedModelVersionId', 'propertyKey', 'from', 'to'])
  expect(call.params.get('propertyKey')).toBe('zero')
  expect(call.signal).toBe(signal)
  expect(call).not.toHaveProperty('data')
  expect(call.showErrorMessage).toBe(false)
})
it('alarm page fixes size 20 and carries opaque cursor unchanged', async () => {
  get.mockClear()
  const signal = new AbortController().signal
  await readAlarmEvidence('p', 'd', 'm', signal)
  await readAlarmEvidence('p', 'd', 'm', signal, 'signed+/=cursor')
  expect(get.mock.calls[0][0].params.has('cursor')).toBe(false)
  const call = get.mock.calls[1][0]
  expect([...call.params.keys()]).toEqual(['expectedModelVersionId', 'limit', 'cursor'])
  expect(call.params.get('limit')).toBe('20')
  expect(call.params.get('cursor')).toBe('signed+/=cursor')
  expect(call.signal).toBe(signal)
  expect(call).not.toHaveProperty('data')
})
