import { expect, it, vi } from 'vitest'
import { createOtaReleaseDownload } from '@/api/ota'
import request from '@/utils/http'

vi.mock('@/utils/http', () => ({ default: { post: vi.fn() } }))

it('申领固定版本下载保持空正文、原幂等键及私密错误处理', async () => {
  vi.mocked(request.post).mockResolvedValue({})
  await createOtaReleaseDownload('project/one', 'firmware/one', 'same-key')
  expect(request.post).toHaveBeenCalledExactlyOnceWith({
    url: '/api/v1/projects/project%2Fone/ota/firmwares/firmware%2Fone/release/downloads',
    headers: { 'Idempotency-Key': 'same-key' },
    showErrorMessage: false
  })
})
