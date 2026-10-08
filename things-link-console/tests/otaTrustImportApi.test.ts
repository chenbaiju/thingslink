import { expect, it, vi } from 'vitest'
import request from '@/utils/http'
import { fetchOtaTrustDomain, importOtaTrustBundle } from '@/api/ota'
import { material } from './helpers/otaTrustPublicFixture'
vi.mock('@/utils/http', () => ({ default: { get: vi.fn(), post: vi.fn() } }))
it('当前登记只GET精确域；POST精确三字段正文与原键，不把正文放查询串', async () => {
  vi.mocked(request.get).mockResolvedValue({})
  vi.mocked(request.post).mockResolvedValue({})
  await fetchOtaTrustDomain('project/one', 'domain/one')
  expect(request.get).toHaveBeenCalledExactlyOnceWith({
    url: '/api/v1/projects/project%2Fone/ota/trust-domains/domain%2Fone',
    showErrorMessage: false
  })
  const body = material('2') as Required<ReturnType<typeof material>>
  await importOtaTrustBundle('project/one', 'domain/one', body, 'original-key')
  expect(request.post).toHaveBeenCalledExactlyOnceWith({
    url: '/api/v1/projects/project%2Fone/ota/trust-domains/domain%2Fone/bundles',
    params: body,
    headers: { 'Idempotency-Key': 'original-key' },
    showErrorMessage: false
  })
})
