import { expect, it, vi } from 'vitest'
const calls = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() }))
vi.mock('@/utils/http', () => ({ default: calls }))
import * as api from '@/api/assistant-knowledge'
it('uses only explicit project/key selectors and sealed bodies without identity or credentials', async () => {
  const signal = new AbortController().signal
  await api.listKnowledge('p/a', signal)
  await api.readKnowledge('p/a', 'guide/key', signal)
  await api.publishKnowledge('p/a', 'guide/key', 'version', '批准正文', signal)
  await api.deleteKnowledge('p/a', 'guide/key', 'version', signal)
  const keys = ['灌溉']
  await api.searchKnowledge('p/a', keys, signal)
  keys.push('排水')
  expect(calls.get.mock.calls.map((c) => c[0].url)).toEqual([
    '/api/v1/projects/p%2Fa/assistant/knowledge/sources',
    '/api/v1/projects/p%2Fa/assistant/knowledge/sources/guide%2Fkey'
  ])
  expect(calls.put).toHaveBeenCalledExactlyOnceWith({
    url: '/api/v1/projects/p%2Fa/assistant/knowledge/sources/guide%2Fkey',
    data: {
      expectedCurrentVersionId: 'version',
      content: '批准正文',
      approvedForProjectMembers: true
    },
    signal,
    showErrorMessage: false
  })
  expect(calls.del).toHaveBeenCalledExactlyOnceWith({
    url: '/api/v1/projects/p%2Fa/assistant/knowledge/sources/guide%2Fkey',
    params: { expectedCurrentVersionId: 'version' },
    signal,
    showErrorMessage: false
  })
  expect(calls.post).toHaveBeenCalledExactlyOnceWith({
    url: '/api/v1/projects/p%2Fa/assistant/knowledge/search',
    data: { keywords: ['灌溉'] },
    signal,
    showErrorMessage: false
  })
})
