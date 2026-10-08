import { expect, it, vi } from 'vitest'
import request from '@/utils/http'
import { fetchOtaTypeBaseline, registerOtaTypeBaseline } from '@/api/ota'
vi.mock('@/utils/http', () => ({ default: { get: vi.fn(), post: vi.fn() } }))
it('当前登记GET与POST绑定精确编码scope，正文只有冻结修订及原键', async () => {
  vi.mocked(request.get).mockResolvedValue({})
  vi.mocked(request.post).mockResolvedValue({})
  await fetchOtaTypeBaseline('project/one', 'type/one')
  expect(request.get).toHaveBeenCalledExactlyOnceWith({
    url: '/api/v1/projects/project%2Fone/ota/device-types/type%2Fone/baseline',
    showErrorMessage: false
  })
  const body = Object.freeze({ expectedRevision: '2' })
  await registerOtaTypeBaseline('project/one', 'type/one', body, 'original-key')
  expect(request.post).toHaveBeenCalledExactlyOnceWith({
    url: '/api/v1/projects/project%2Fone/ota/device-types/type%2Fone/baseline/registrations',
    params: body,
    headers: { 'Idempotency-Key': 'original-key' },
    showErrorMessage: false
  })
})
