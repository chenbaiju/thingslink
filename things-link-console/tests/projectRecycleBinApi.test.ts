import { expect, it, vi } from 'vitest'
vi.mock('@/utils/http', () => ({ default: { get: vi.fn(), post: vi.fn() } }))
import request from '@/utils/http'
import { fetchProjectRecycleBin, fetchRestoreProject } from '@/api/project'
it('uses account recycle-bin and project-specific restore endpoints', () => {
  fetchProjectRecycleBin()
  fetchRestoreProject('p')
  expect(request.get).toHaveBeenCalledWith({ url: '/api/v1/projects/recycle-bin' })
  expect(request.post).toHaveBeenCalledWith({ url: '/api/v1/projects/p/restore' })
})
