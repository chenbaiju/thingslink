import { expect, it, vi } from 'vitest'
import request from '@/utils/http'
import { fetchOtaRollbackPreflight } from '@/api/ota'
vi.mock('@/utils/http', () => ({ default: { get: vi.fn() } }))
it('只GET完整精确三轴路径，编码各ID，无写入/幂等参数', async () => {
  vi.mocked(request.get).mockResolvedValue({})
  await fetchOtaRollbackPreflight('project/one', 'campaign/one', 'job/one')
  expect(request.get).toHaveBeenCalledExactlyOnceWith({
    url: '/api/v1/projects/project%2Fone/ota/campaigns/campaign%2Fone/jobs/job%2Fone/rollback-preflight',
    showErrorMessage: false
  })
})
