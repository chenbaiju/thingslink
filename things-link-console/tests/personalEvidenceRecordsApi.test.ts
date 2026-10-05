import { expect, it, vi } from 'vitest'
const calls = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), del: vi.fn() }))
vi.mock('@/utils/http', () => ({ default: calls }))
import {
  savePersonalRecord,
  listPersonalRecords,
  readPersonalRecord,
  deletePersonalRecord,
  generatePersonalFactReport
} from '@/api/assistant-records'
it('all operations use the current project namespace and explicit cancel signal', async () => {
  const signal = new AbortController().signal
  await savePersonalRecord('p', 'd', 'm', ['n'], signal)
  expect(calls.post.mock.calls[0][0]).toEqual({
    url: '/api/v1/projects/p/assistant/evidence-records',
    data: { deviceId: 'd', expectedModelVersionId: 'm', propertyKeys: ['n'] },
    signal,
    showErrorMessage: false
  })
  await listPersonalRecords('p', signal)
  await readPersonalRecord('p', 'r', signal)
  await deletePersonalRecord('p', 'r', signal)
  expect(calls.get.mock.calls.map((c) => c[0].url)).toEqual([
    '/api/v1/projects/p/assistant/evidence-records',
    '/api/v1/projects/p/assistant/evidence-records/r'
  ])
  expect(calls.del.mock.calls[0][0].url).toBe('/api/v1/projects/p/assistant/evidence-records/r')
})
it('manual report is a closed GET bound to the selected project and record', async () => {
  const signal = new AbortController().signal
  await generatePersonalFactReport('project/name', 'record/name', signal)
  expect(calls.get.mock.lastCall?.[0]).toEqual({
    url: '/api/v1/projects/project%2Fname/assistant/evidence-records/record%2Fname/fact-report',
    signal,
    showErrorMessage: false
  })
})
it('collection sends only a copied selector array with explicit cancellation', async () => {
  const signal = new AbortController().signal
  const ids = ['a', 'b']
  const { generatePersonalFactCollection } = await import('@/api/assistant-records')
  await generatePersonalFactCollection('project/name', ids, signal)
  const input = calls.post.mock.lastCall?.[0]
  expect(input).toEqual({
    url: '/api/v1/projects/project%2Fname/assistant/fact-reports/collection',
    data: { recordIds: ['a', 'b'] },
    signal,
    showErrorMessage: false
  })
  ids.push('c')
  expect(input.data.recordIds).toEqual(['a', 'b'])
})
