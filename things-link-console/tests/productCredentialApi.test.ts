import { expect, it, vi } from 'vitest'
import { fetchDeviceTypeDetail, generateProductCredential } from '@/api/device'
import http from '@/utils/http'
vi.mock('@/utils/http', () => ({ default: { get: vi.fn(), post: vi.fn() } }))
it('类型详情只读，生成无幂等键且错误正文不弹出', async () => {
  await fetchDeviceTypeDetail('project', 'type')
  await generateProductCredential('project', 'type')
  expect(http.get).toHaveBeenCalledWith({
    url: '/api/v1/projects/project/device-types/type',
    showErrorMessage: false
  })
  expect(http.post).toHaveBeenCalledWith({
    url: '/api/v1/projects/project/device-types/type/product-credential',
    showErrorMessage: false
  })
})
