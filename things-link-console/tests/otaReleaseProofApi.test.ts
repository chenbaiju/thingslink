import { expect, it, vi } from 'vitest'
import { fetchOtaRelease } from '@/api/ota'
import request from '@/utils/http'
vi.mock('@/utils/http', () => ({ default: { get: vi.fn() } }))
it('公开证明仅GET指定发布物，编码身份并由局部页面处理拒绝', async () => {
  vi.mocked(request.get).mockResolvedValue({})
  await fetchOtaRelease('project/one', 'firmware/one')
  expect(request.get).toHaveBeenCalledExactlyOnceWith({
    url: '/api/v1/projects/project%2Fone/ota/firmwares/firmware%2Fone/release',
    showErrorMessage: false
  })
})
